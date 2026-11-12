using Windows.Devices.Bluetooth.Advertisement;

namespace EarHop.Win;

/// <summary>Listens for the other devices' Bluetooth LE status (the 16 byte payload carried as a 128 bit service UUID).
/// This PC does not advertise its own status over Bluetooth yet, so the other devices hear it over Wi-Fi only.</summary>
sealed class BleWatch : IDisposable
{
    private readonly string secret, selfName;
    private BluetoothLEAdvertisementWatcher? watcher;
    public event Action<Status>? OnStatus;
    public DateTime LastHeard { get; private set; } = DateTime.MinValue;

    public BleWatch(string secret, string selfName) { this.secret = secret; this.selfName = selfName; }

    public void Start()
    {
        try
        {
            watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Passive };
            watcher.Received += (_, e) =>
            {
                foreach (var g in e.Advertisement.ServiceUuids) Handle(g);
            };
            watcher.Stopped += (_, e) => Log.Write($"ble: scan stopped ({e.Error})");
            watcher.Start();
            Log.Write("ble: listening for the other devices' status");
        }
        catch (Exception e) { Log.Write($"ble: not available ({e.Message})"); }
    }

    private void Handle(Guid g)
    {
        // The payload is the UUID in its written order (as CoreBluetooth and Android build it), not Guid's byte order.
        var b = Convert.FromHexString(g.ToString("N"));
        if ((b[0] & 0xFE) != 0xEA) return;
        Status? s = null;
        if (b[0] == 0xEB && BlePayload.Decode4(b, secret) is BlePayload.V4 m && NameBook.Shared.Name(m.Id) is string name) s = BlePayload.Status4(m, name);
        else if (b[0] == 0xEA) s = BlePayload.DecodeOld(b, secret, BlePayload.Minute(Proto.NowMs()));
        if (s is Status st && st.Dev != selfName) { LastHeard = DateTime.UtcNow; OnStatus?.Invoke(st); }
    }

    public void Dispose() { try { watcher?.Stop(); } catch { } }
}
