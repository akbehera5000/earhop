using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace EarHop.Win;

/// <summary>Listens for the other devices' Bluetooth LE status (the 16 byte payload carried as a 128 bit service UUID).
/// BleAdvert below sends this PC's own status the same way.</summary>
sealed class BleWatch : IDisposable
{
    private readonly string secret, selfName;
    private BluetoothLEAdvertisementWatcher? watcher;
    public event Action<Status>? OnStatus;
    public DateTime LastHeard { get; private set; } = DateTime.MinValue;
    private readonly Dictionary<string, (ulong Address, DateTime At)> addresses = new();

    /// <summary>The newest Bluetooth address each device advertised from in the last 90 s (Android may change it each
    /// minute), for Bluetooth clipboard sends.</summary>
    public List<(string Dev, ulong Address)> Peers()
    {
        var now = DateTime.UtcNow;
        lock (addresses) return addresses.Where(kv => (now - kv.Value.At).TotalSeconds < 90).Select(kv => (kv.Key, kv.Value.Address)).ToList();
    }

    public ulong? AddressOf(string dev)
    {
        lock (addresses) return addresses.TryGetValue(dev, out var a) && (DateTime.UtcNow - a.At).TotalSeconds < 90 ? a.Address : null;
    }

    public BleWatch(string secret, string selfName) { this.secret = secret; this.selfName = selfName; }

    public void Start()
    {
        try
        {
            watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Passive };
            watcher.Received += (_, e) =>
            {
                foreach (var g in e.Advertisement.ServiceUuids) Handle(g, e.BluetoothAddress);
            };
            watcher.Stopped += (_, e) => Log.Write($"ble: scan stopped ({e.Error})");
            watcher.Start();
            Log.Write("ble: listening for the other devices' status");
        }
        catch (Exception e) { Log.Write($"ble: not available ({e.Message})"); }
    }

    private void Handle(Guid g, ulong address)
    {
        // The payload is the UUID in its written order (as CoreBluetooth and Android build it), not Guid's byte order.
        var b = Convert.FromHexString(g.ToString("N"));
        if ((b[0] & 0xFE) != 0xEA) return;
        Status? s = null;
        if (b[0] == 0xEB && BlePayload.Decode4(b, secret) is BlePayload.V4 m && NameBook.Shared.Name(m.Id) is string name) s = BlePayload.Status4(m, name);
        else if (b[0] == 0xEA) s = BlePayload.DecodeOld(b, secret, BlePayload.Minute(Proto.NowMs()));
        if (s is Status st && st.Dev != selfName)
        {
            LastHeard = DateTime.UtcNow;
            lock (addresses) addresses[st.Dev] = (address, DateTime.UtcNow);
            OnStatus?.Invoke(st);
        }
    }

    public void Dispose() { try { watcher?.Stop(); } catch { } }
}

/// <summary>
/// Sends this PC's status over Bluetooth LE: the 16 byte v4 payload as one 128 bit service UUID, in written order
/// (byte 0 = 0xEB first), like the Mac and Android. Devices set to Connection "Bluetooth" only listen there.
/// Windows does not let an advertisement publisher set service UUIDs on every stack, so it tries that first and falls
/// back to advertising a GATT service whose UUID is the payload. The payload is refreshed on every change and at least
/// every 30 s (receivers accept it within 90 s).
/// </summary>
sealed class BleAdvert : IDisposable
{
    private readonly string secret;
    private BluetoothLEAdvertisementPublisher? publisher;
    private GattServiceProvider? provider;
    private string mode = "gatt";
    private bool failed;
    private string lastPayload = "";
    private DateTime lastAt = DateTime.MinValue;
    private bool busy;
    private DateTime retryAt = DateTime.MinValue;
    private bool radioLogged;
    public string State { get; private set; } = "off";

    public BleAdvert(string secret) { this.secret = secret; }

    public void Update(Status s)
    {
        if (failed || busy || secret.Length == 0 || DateTime.UtcNow < retryAt) return;
        var payload = BlePayload.Encode4(s, secret, (uint)(Proto.NowMs() / 1000));
        // The sequence number and time stamp change every second; only a change of the device or its flags, or 30 s,
        // needs a new advertisement (receivers take any newer copy).
        var key = $"{payload[1]:X2}{payload[2]:X2}{payload[5]:X2}";
        if (key == lastPayload && (DateTime.UtcNow - lastAt).TotalSeconds < 30) return;
        lastPayload = key;
        lastAt = DateTime.UtcNow;
        var uuid = new Guid(Convert.ToHexString(payload));
        busy = true;
        _ = Task.Run(async () =>
        {
            try { await Advertise(uuid); }
            catch (Exception e) { Log.Write($"ble: advertising failed ({e.Message})"); }
            finally { busy = false; }
        });
    }

    private async Task Advertise(Guid uuid)
    {
        // Windows refuses a 128 bit service UUID in an advertisement publisher ("Value does not fall within the expected
        // range", seen on the owner's PC), so the status goes out as a GATT service advertisement from the start.
        if (mode == "publisher")
        {
            try
            {
                publisher?.Stop();
                var adv = new BluetoothLEAdvertisement();
                adv.ServiceUuids.Add(uuid);
                publisher = new BluetoothLEAdvertisementPublisher(adv);
                publisher.StatusChanged += (_, e) =>
                {
                    if (e.Error == Windows.Devices.Bluetooth.BluetoothError.Success) { if (e.Status == BluetoothLEAdvertisementPublisherStatus.Started) { State = "on"; radioLogged = false; } return; }
                    // No Bluetooth radio, or it is off: try again in a minute instead of every second.
                    State = e.Error == Windows.Devices.Bluetooth.BluetoothError.RadioNotAvailable ? "no radio" : "unavailable";
                    retryAt = DateTime.UtcNow.AddSeconds(60);
                    lastPayload = "";
                    if (!radioLogged) { radioLogged = true; Log.Write($"ble: cannot send status over Bluetooth LE now ({e.Error}), trying again every minute"); }
                };
                publisher.Start();
                if (mode == "") Log.Write("ble: sending this PC's status (advertisement publisher)");
                mode = "publisher";
                return;
            }
            catch (Exception e)
            {
                if (mode == "") Log.Write($"ble: publisher refused the service UUID ({e.Message}), trying a GATT service");
                publisher = null;
                mode = "gatt";
            }
        }
        try
        {
            if (provider != null) { try { provider.StopAdvertising(); } catch { } provider = null; }
            var r = await GattServiceProvider.CreateAsync(uuid);
            if (r.Error != Windows.Devices.Bluetooth.BluetoothError.Success) throw new InvalidOperationException(r.Error.ToString());
            provider = r.ServiceProvider;
            provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = true });
            if (State != "on") Log.Write("ble: sending this PC's status (GATT service advertisement)");
            State = "on";
            radioLogged = false;
        }
        catch (Exception e)
        {
            // Bluetooth off or busy: try again in a minute (logged once until it works).
            State = e.Message.Contains("RadioNotAvailable") ? "no radio" : "unavailable";
            retryAt = DateTime.UtcNow.AddSeconds(60);
            lastPayload = "";
            if (!radioLogged)
            {
                radioLogged = true;
                Log.Write($"ble: this PC cannot send its status over Bluetooth LE now ({e.Message}), trying again every minute. Devices set to Connection: Both still hear it over Wi-Fi.");
            }
        }
    }

    public void Dispose()
    {
        try { publisher?.Stop(); } catch { }
        try { provider?.StopAdvertising(); } catch { }
    }
}

/// <summary>
/// Why other devices may not reach this PC: the Wi-Fi network is Public, or Windows Firewall has no inbound Allow rule
/// for this EarHop.exe on the current network type (or a Block rule). Read only: EarHop never changes these settings,
/// it tells the owner what to click. Checked every 30 s on a worker thread.
/// </summary>
sealed class NetCheck
{
    public string? Problem { get; private set; }
    public string Fix { get; private set; } = "";
    public string NetworkName { get; private set; } = "";
    public bool PublicNetwork { get; private set; }
    private DateTime checkedAt = DateTime.MinValue;
    private bool running;
    private string? lastLogged = "unchecked";

    public void Tick()
    {
        if (running || (DateTime.UtcNow - checkedAt).TotalSeconds < 30) return;
        checkedAt = DateTime.UtcNow;
        running = true;
        var t = new Thread(() => { try { Check(); } catch (Exception e) { Log.Write($"netcheck: {e.Message}"); } finally { running = false; } }) { IsBackground = true };
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
    }

    private void Check()
    {
        // Network type of the connected networks (0 public, 1 private, 2 domain).
        int profileBit = 2;
        string name = "";
        bool pub = false;
        try
        {
            dynamic nlm = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B"))!)!;
            foreach (dynamic net in nlm.GetNetworks(1))
            {
                int cat = net.GetCategory();
                name = net.GetName();
                if (cat == 0) { pub = true; profileBit = 4; break; }
                profileBit = cat == 2 ? 1 : 2;
            }
        }
        catch (Exception e) { Log.Write($"netcheck: network type unknown ({e.Message})"); }
        NetworkName = name;
        PublicNetwork = pub;

        // Firewall rules for this exe on that network type.
        string exe = Environment.ProcessPath ?? "";
        bool allow = false, block = false, any = false;
        try
        {
            dynamic fw = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2")!)!;
            bool enabled = fw.FirewallEnabled[profileBit];
            if (!enabled) allow = true;
            foreach (dynamic r in fw.Rules)
            {
                string app = r.ApplicationName ?? "";
                if (!string.Equals(app, exe, StringComparison.OrdinalIgnoreCase)) continue;
                if (!(bool)r.Enabled || (int)r.Direction != 1) continue;
                any = true;
                if (((int)r.Profiles & profileBit) == 0 && (int)r.Profiles != 0x7FFFFFFF) continue;
                if ((int)r.Action == 1) allow = true; else block = true;
            }
        }
        catch (Exception e) { Log.Write($"netcheck: firewall rules unknown ({e.Message})"); allow = true; }

        // Only a real block is a problem: a Public network is fine when the firewall allows EarHop there.
        string? problem = null;
        string fix = "";
        if (block || !allow)
        {
            if (pub)
            {
                problem = $"\"{name}\" is a Public network and Windows Firewall keeps your other devices out, so no status or clipboard reaches this PC. Set it to Private (your home Wi-Fi), or allow EarHop on public networks.";
                fix = "network";
            }
            else
            {
                problem = block ? "Windows Firewall blocks EarHop, so no status or clipboard from your other devices reaches this PC. Allow EarHop on private networks."
                                : "Windows Firewall has not allowed EarHop yet, so your other devices cannot reach this PC. Allow EarHop on private networks.";
                fix = "firewall";
            }
        }
        Problem = problem;
        Fix = fix;
        if (problem != lastLogged) { Log.Write(problem == null ? $"netcheck: network \"{name}\" and firewall look fine" : $"netcheck: {problem}"); lastLogged = problem; }
    }

    /// <summary>Opens the Windows page where the owner fixes it (EarHop changes nothing itself).</summary>
    public void OpenFix()
    {
        try
        {
            if (Fix == "network") System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:network-status") { UseShellExecute = true });
            else System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("control.exe", "/name Microsoft.WindowsFirewall /page pageConfigureApps") { UseShellExecute = true });
        }
        catch (Exception e) { Log.Write($"netcheck: {e.Message}"); }
    }
}
