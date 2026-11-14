using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace EarHop.Win;

/// <summary>
/// The Galaxy Buds status channel on Windows (BudsWear.swift): while the Buds are connected to this PC, opens Samsung's
/// serial service (RFCOMM, UUID 2e73a4ad-332d-41fc-90e2-16bef06523f2) and reads what they push: battery, which bud is in
/// an ear, noise control, equalizer, touch lock. The channel is never opened while the Buds are on another device, so
/// it cannot pull them over. It is closed before EarHop releases the Buds (AGENTS.md mistake 5).
/// </summary>
sealed class BudsLink : IDisposable
{
    public static readonly Guid ServiceUuid = new("2e73a4ad-332d-41fc-90e2-16bef06523f2");
    private StreamSocket? socket;
    private DataWriter? writer;
    private CancellationTokenSource? cts;
    private readonly List<byte> buffer = new();
    private BudsProtocol.CrcStats crc = new();
    private bool connecting;
    private int failures;
    private DateTime nextTryAt = DateTime.MinValue, holdUntil = DateTime.MinValue, seenAt = DateTime.MinValue, claimUntil = DateTime.MinValue;
    private Ear? ear;
    private readonly SynchronizationContext ui;
    public string NameFilter = "Buds2 Pro";
    public BudsProtocol.State State { get; private set; } = new();
    public string Status { get; private set; } = "off";
    public bool Open => socket != null;
    /// <summary>Called on the UI thread when the Buds report something new.</summary>
    public event Action<BudsProtocol.State>? OnState;

    public BudsLink() { ui = SynchronizationContext.Current ?? new SynchronizationContext(); }

    /// <summary>The wear state with its source, like the Mac: live from the channel (high), the last report for 20 s
    /// after it closes (low, never pauses or resumes), or assumed in the ears when nothing is known (low).</summary>
    public EarReading Reading
    {
        get
        {
            var now = DateTime.UtcNow;
            if (socket != null && ear is Ear e && (now - seenAt).TotalSeconds < 120) return new EarReading(e, "buds-rfcomm", EarConfidence.High);
            if (ear is Ear l && (now - seenAt).TotalSeconds < 20) return new EarReading(l, "last-known", EarConfidence.Low);
            return new EarReading(Ear.Both, "assumed", EarConfidence.Low);
        }
    }

    /// <summary>Call about once a second on the UI thread. Opens the channel only while the Buds are connected here.</summary>
    public void Update(bool owner, bool wanted)
    {
        // A channel opened by a claim stays open while the earbuds come over (up to 20 s).
        if (!wanted || (!owner && DateTime.UtcNow > claimUntil))
        {
            if (socket != null || Status != "off") { Close(); Status = "off"; }
            return;
        }
        if (socket != null || connecting || DateTime.UtcNow < nextTryAt || DateTime.UtcNow < holdUntil) return;
        connecting = true;
        Status = "connecting";
        _ = Task.Run(Connect);
    }

    /// <summary>Finds the paired Buds in Windows (name contains the filter).</summary>
    public static async Task<BluetoothDevice?> FindDevice(string filter)
    {
        var all = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
        foreach (var d in all)
            if (d.Name.Contains(filter, StringComparison.OrdinalIgnoreCase))
                return await BluetoothDevice.FromIdAsync(d.Id);
        return null;
    }

    /// <summary>Opens the serial channel. Also used during a claim: an open channel brings up the Bluetooth link, after
    /// which Windows connects the audio profiles of a paired headset.</summary>
    private readonly SemaphoreSlim opening = new(1, 1);

    public async Task<bool> OpenChannel(string why)
    {
        if (why == "claim") claimUntil = DateTime.UtcNow.AddSeconds(20);
        // One open at a time: the claim's open and the regular one ran together and the second failed with "Only one
        // usage of each socket address (protocol/network address/port) is normally permitted".
        await opening.WaitAsync();
        try
        {
            if (socket != null) return true;
            for (int tryNo = 1; ; tryNo++)
            {
                var r = await OpenOnce(why);
                if (r != null) return r.Value;
                if (tryNo >= 2) { Fail("the channel is still in use, will try again"); return false; }
                await Task.Delay(1200); // the previous socket is still being released by Windows
            }
        }
        finally { opening.Release(); }
    }

    /// <summary>One open. Null when the address was still in use (worth one more try after a moment).</summary>
    private async Task<bool?> OpenOnce(string why)
    {
        try
        {
            var dev = await FindDevice(NameFilter);
            if (dev == null) { Fail("the Buds are not paired with this PC"); return false; }
            var svcs = await dev.GetRfcommServicesForIdAsync(RfcommServiceId.FromUuid(ServiceUuid), BluetoothCacheMode.Uncached);
            if (svcs.Services.Count == 0)
            {
                svcs = await dev.GetRfcommServicesForIdAsync(RfcommServiceId.FromUuid(ServiceUuid), BluetoothCacheMode.Cached);
                if (svcs.Services.Count == 0) { Fail($"service not found ({svcs.Error})"); return false; }
            }
            var svc = svcs.Services[0];
            var s = new StreamSocket();
            await s.ConnectAsync(svc.ConnectionHostName, svc.ConnectionServiceName, SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
            socket = s;
            writer = new DataWriter(s.OutputStream);
            failures = 0;
            crc = new();
            connecting = false;
            Status = "connected";
            Log.Write($"buds: status channel open ({why})");
            cts = new CancellationTokenSource();
            var token = cts.Token;
            _ = Task.Run(() => ReadLoop(s, token));
            return true;
        }
        catch (Exception e) when ((uint)e.HResult == 0x80072740) // WSAEADDRINUSE
        {
            return null;
        }
        catch (Exception e)
        {
            Fail(e.Message.Trim());
            return false;
        }
    }

    private async Task Connect() => await OpenChannel("Buds connected here");

    private void Fail(string why)
    {
        connecting = false;
        failures++;
        Status = $"refused ({why})";
        if (failures == 1 || failures % 5 == 0) Log.Write($"buds: status channel failed: {why}");
        // Back off 2, 4, 8, 15 s so a refused channel never becomes a busy loop but comes back soon.
        nextTryAt = DateTime.UtcNow.AddSeconds(Math.Min(15, 2 * Math.Pow(2, Math.Min(failures - 1, 3))));
    }

    private async Task ReadLoop(StreamSocket s, CancellationToken token)
    {
        var reader = new DataReader(s.InputStream) { InputStreamOptions = InputStreamOptions.Partial };
        try
        {
            while (!token.IsCancellationRequested)
            {
                uint n = await reader.LoadAsync(1024);
                if (n == 0) break;
                var bytes = new byte[n];
                reader.ReadBytes(bytes);
                ui.Post(_ => Received(bytes), null);
            }
        }
        catch (Exception e) when (!token.IsCancellationRequested) { Log.Write($"buds: status channel read ended ({e.Message.Trim()})"); }
        ui.Post(_ =>
        {
            if (socket != s) return;
            Log.Write("buds: status channel closed");
            Close();
            Status = "closed";
            nextTryAt = DateTime.UtcNow.AddSeconds(3);
        }, null);
    }

    private void Received(byte[] bytes)
    {
        buffer.AddRange(bytes);
        int hadGood = crc.Good, hadBad = crc.Bad;
        var msgs = BudsProtocol.Extract(buffer, crc);
        if (hadGood == 0 && crc.Good > 0) Log.Write("buds: frame checksums verified, bad frames are now dropped");
        if (crc.Bad > hadBad && (crc.Bad == 1 || crc.Bad % 20 == 0)) Log.Write($"buds: {crc.Bad} frames with a bad checksum ({(crc.Enforcing ? "dropped" : "kept, checksum not confirmed yet")})");
        foreach (var m in msgs)
        {
            var before = State;
            State = BudsProtocol.Apply(m, State);
            if (before.Revision < 0 && State.Revision >= 0)
                Log.Write($"buds: status rev {State.Revision} noise={State.Noise} eq={State.Eq} battery L{State.BattL} R{State.BattR} case {State.BattCase} touch={State.TouchOn}");
            if (m.Id is BudsProtocol.IdStatus or BudsProtocol.IdExtended)
            {
                var e = State.Ear;
                if (e != ear) Log.Write($"buds: wear {(ear is Ear w ? EarText.Raw(w) : "?")} -> {EarText.Raw(e)} (L={State.PlaceL} R={State.PlaceR})");
                ear = e;
                seenAt = DateTime.UtcNow;
            }
            if (State != before) OnState?.Invoke(State);
        }
    }

    public bool Send(byte[] bytes)
    {
        var w = writer;
        if (w == null) return false;
        try { w.WriteBytes(bytes); _ = w.StoreAsync(); return true; }
        catch (Exception e) { Log.Write($"buds: send failed ({e.Message.Trim()})"); return false; }
    }

    public void SetNoise(string mode) { if (Send(BudsProtocol.NoiseCommand(mode))) { State = State with { Noise = mode }; Log.Write($"buds: noise control {mode}"); OnState?.Invoke(State); } }
    public void SetEq(string preset) { if (Send(BudsProtocol.EqCommand(preset))) { State = State with { Eq = preset }; Log.Write($"buds: equalizer {preset}"); OnState?.Invoke(State); } }
    public void SetTouch(bool on) { if (Send(BudsProtocol.TouchCommand(on, State))) { State = State with { TouchOn = on }; Log.Write($"buds: touch controls {(on ? "on" : "off")}"); OnState?.Invoke(State); } }

    /// <summary>Closes the status channel before EarHop disconnects the Buds and keeps it closed for a few seconds. An
    /// open channel keeps the Bluetooth link alive and makes the disconnect slow.</summary>
    public void Suspend(double seconds)
    {
        holdUntil = DateTime.UtcNow.AddSeconds(seconds);
        if (socket != null) { Log.Write("buds: closing status channel before disconnect"); Close(); Status = "off"; }
    }

    private void Close()
    {
        try { cts?.Cancel(); } catch { }
        try { writer?.DetachStream(); } catch { }
        try { socket?.Dispose(); } catch { }
        socket = null;
        writer = null;
        buffer.Clear();
        connecting = false;
    }

    public void Dispose() => Close();
}
