using System.Security.Cryptography;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace EarHop.Win;

/// <summary>
/// The clipboard over Bluetooth LE (ClipBle in Clipboard.swift), so clips reach the Mac, phone and tablet without Wi-Fi.
/// Server: the GATT service EAC1B000-5EED-4C1E-8A00-000000000001 with an inbox (B001, write, encrypted) that other
/// devices write chunks into, and this PC's name (B002, read, encrypted) so they can map its Bluetooth LE id to it. It is
/// published but not advertised: the others find the PC through its status advertisement and connect to it.
/// Client: one queue per device (AGENTS.md mistake 31), connect, write the chunks, disconnect; a device that fails is
/// handed to Wi-Fi at once and rested by BleHealth.
/// </summary>
sealed class ClipBle : IDisposable
{
    public static readonly Guid ServiceUuid = new("EAC1B000-5EED-4C1E-8A00-000000000001");
    public static readonly Guid InboxUuid = new("EAC1B001-5EED-4C1E-8A00-000000000001");
    public static readonly Guid NameUuid = new("EAC1B002-5EED-4C1E-8A00-000000000001");

    private GattServiceProvider? provider;
    private readonly ClipChunks.Assembler assembler = new();
    private readonly Dictionary<string, SemaphoreSlim> queues = new();
    private readonly Dictionary<string, int> pending = new();
    public readonly BleHealth Health = new();
    public string SelfName = "";
    public bool ServiceUp { get; private set; }
    /// <summary>A whole clip frame arrived over Bluetooth (called on a worker thread).</summary>
    public Action<byte[]>? OnFrame;

    // MARK: server

    public async Task Start()
    {
        try
        {
            var r = await GattServiceProvider.CreateAsync(ServiceUuid);
            if (r.Error != BluetoothError.Success) { Log.Write($"clip: Bluetooth clipboard service not available ({r.Error})"); return; }
            provider = r.ServiceProvider;
            var inbox = await provider.Service.CreateCharacteristicAsync(InboxUuid, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
                // Encrypted, like the Mac and Android: the first connection between two devices pairs them once.
                WriteProtectionLevel = GattProtectionLevel.EncryptionRequired,
            });
            if (inbox.Error != BluetoothError.Success) { Log.Write($"clip: Bluetooth inbox not created ({inbox.Error})"); return; }
            inbox.Characteristic.WriteRequested += OnWrite;
            var name = await provider.Service.CreateCharacteristicAsync(NameUuid, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.EncryptionRequired,
            });
            if (name.Error == BluetoothError.Success) name.Characteristic.ReadRequested += OnRead;
            provider.AdvertisementStatusChanged += (_, e) =>
            {
                bool up = e.Status is GattServiceProviderAdvertisementStatus.Started or GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData;
                if (up != ServiceUp) Log.Write(up ? "clip: Bluetooth clipboard service up" : $"clip: Bluetooth clipboard service {e.Status} ({e.Error})");
                ServiceUp = up;
            };
            provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = false });
        }
        catch (Exception e) { Log.Write($"clip: Bluetooth clipboard service not started ({e.Message.Trim()})"); }
    }

    private async void OnWrite(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        try
        {
            var req = await args.GetRequestAsync();
            if (req == null) return;
            var reader = DataReader.FromBuffer(req.Value);
            var chunk = new byte[reader.UnconsumedBufferLength];
            reader.ReadBytes(chunk);
            if (req.Option == GattWriteOption.WriteWithResponse) req.Respond();
            var from = args.Session.DeviceId.Id;
            if (assembler.Add(chunk, from, DateTime.UtcNow) is byte[] frame) OnFrame?.Invoke(frame);
        }
        catch (Exception e) { Log.Write($"clip: Bluetooth write from another device failed ({e.Message.Trim()})"); }
        finally { deferral.Complete(); }
    }

    private async void OnRead(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        try
        {
            var req = await args.GetRequestAsync();
            if (req == null) return;
            var v = System.Text.Encoding.UTF8.GetBytes(SelfName);
            int off = (int)Math.Min(req.Offset, (uint)v.Length);
            var w = new DataWriter();
            w.WriteBytes(v[off..]);
            req.RespondWithValue(w.DetachBuffer());
        }
        catch (Exception e) { Log.Write($"clip: Bluetooth name read failed ({e.Message.Trim()})"); }
        finally { deferral.Complete(); }
    }

    // MARK: client

    /// <summary>Sends a frame to each device over Bluetooth, each on its own queue. A device that cannot be reached
    /// (or is resting after failures) goes to onFail, which sends it over Wi-Fi instead.</summary>
    public void Send(byte[] frame, IEnumerable<(string Dev, ulong Address)> peers, bool urgent, Action<string> onFail)
    {
        uint tag = BitConverter.ToUInt32(RandomNumberGenerator.GetBytes(4));
        foreach (var (dev, address) in peers)
        {
            if (!Health.Usable(dev, DateTime.UtcNow)) { onFail(dev); continue; }
            SemaphoreSlim q;
            lock (queues)
            {
                if (!queues.TryGetValue(dev, out q!)) queues[dev] = q = new SemaphoreSlim(1, 1);
                int n = pending.TryGetValue(dev, out var p) ? p : 0;
                // A safety cap: with more than 30 messages waiting for one device, new control messages go over Wi-Fi.
                if (!urgent && n > 30) { Log.Write($"clip: Bluetooth queue to {dev} is full, a control message goes over Wi-Fi"); onFail(dev); continue; }
                pending[dev] = n + 1;
            }
            string d = dev;
            ulong a = address;
            _ = Task.Run(async () =>
            {
                await q.WaitAsync();
                try
                {
                    var (ok, why) = await SendOne(a, frame, tag);
                    bool pairing = !ok && why.StartsWith("not paired");
                    Log.Write($"clip: Bluetooth send to {d} {(ok ? "done" : $"failed ({why})")}");
                    if (Health.Report(d, ok, pairing, DateTime.UtcNow) is string line) Log.Write(line);
                    if (!ok) onFail(d);
                }
                finally
                {
                    lock (queues) pending[d] = Math.Max(0, pending[d] - 1);
                    q.Release();
                }
            });
        }
    }

    private static async Task<T> Within<T>(Windows.Foundation.IAsyncOperation<T> op, int seconds, string stage)
    {
        try { return await op.AsTask().WaitAsync(TimeSpan.FromSeconds(seconds)); }
        catch (TimeoutException) { throw new TimeoutException($"timed out while {stage}"); }
    }

    /// <summary>Connects, finds the inbox, writes every chunk with a response, disconnects.</summary>
    private static async Task<(bool, string)> SendOne(ulong address, byte[] frame, uint tag)
    {
        BluetoothLEDevice? dev = null;
        GattDeviceService? svc = null;
        try
        {
            dev = await Within(BluetoothLEDevice.FromBluetoothAddressAsync(address), 6, "connecting");
            if (dev == null) return (false, "not reachable right now");
            var sr = await Within(dev.GetGattServicesForUuidAsync(ServiceUuid, BluetoothCacheMode.Uncached), 10, "finding the clipboard service");
            if (sr.Status != GattCommunicationStatus.Success || sr.Services.Count == 0) return (false, $"no clipboard service ({sr.Status})");
            svc = sr.Services[0];
            var cr = await Within(svc.GetCharacteristicsForUuidAsync(InboxUuid, BluetoothCacheMode.Uncached), 10, "finding the inbox");
            if (cr.Status != GattCommunicationStatus.Success || cr.Characteristics.Count == 0) return (false, $"no inbox ({cr.Status})");
            var ch = cr.Characteristics[0];
            ch.ProtectionLevel = GattProtectionLevel.EncryptionRequired;
            // 180 byte writes need an MTU of 183; with a smaller one the chunks get smaller (the receiver takes any size).
            int pdu = svc.Session?.MaxPduSize ?? 185;
            int payload = Math.Clamp(pdu - 3 - 8, 12, ClipChunks.Payload);
            var started = DateTime.UtcNow;
            foreach (var c in ClipChunks.Split(frame, payload, tag))
            {
                if ((DateTime.UtcNow - started).TotalSeconds > 30) return (false, "timed out while writing");
                var w = new DataWriter();
                w.WriteBytes(c);
                var res = await Within(ch.WriteValueWithResultAsync(w.DetachBuffer(), GattWriteOption.WriteWithResponse), 30, "writing (accept the pairing request if one appears)");
                if (res.Status == GattCommunicationStatus.Success) continue;
                if (res.Status == GattCommunicationStatus.AccessDenied || res.ProtocolError is 5 or 15 or 8)
                    return (false, "not paired: accept the pairing request on both devices");
                return (false, $"write refused ({res.Status}{(res.ProtocolError is byte pe ? $", error {pe}" : "")})");
            }
            return (true, "");
        }
        catch (TimeoutException e) { return (false, e.Message); }
        catch (Exception e) { return (false, e.Message.Trim()); }
        finally
        {
            try { svc?.Dispose(); } catch { }
            try { dev?.Dispose(); } catch { }
        }
    }

    public void Dispose()
    {
        try { provider?.StopAdvertising(); } catch { }
    }
}
