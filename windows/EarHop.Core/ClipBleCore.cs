namespace EarHop;

/// <summary>Bluetooth LE chunks of a clip frame (ClipChunks in Clipboard.swift): tag (4) | index (2) | count (2) | payload.
/// The payload size is the sender's choice; 172 bytes makes 180 byte writes that fit the usual negotiated MTU.</summary>
public static class ClipChunks
{
    public const int Payload = 172;

    public static List<byte[]> Split(byte[] frame, int payload, uint tag)
    {
        int size = Math.Max(1, payload);
        int count = (frame.Length + size - 1) / size;
        var list = new List<byte[]>();
        if (count <= 0 || count > 0xFFFF) return list;
        for (int i = 0; i < count; i++)
        {
            int start = i * size, len = Math.Min(size, frame.Length - start);
            var c = new byte[8 + len];
            c[0] = (byte)(tag >> 24); c[1] = (byte)(tag >> 16); c[2] = (byte)(tag >> 8); c[3] = (byte)tag;
            c[4] = (byte)(i >> 8); c[5] = (byte)i; c[6] = (byte)(count >> 8); c[7] = (byte)count;
            Array.Copy(frame, start, c, 8, len);
            list.Add(c);
        }
        return list;
    }

    /// <summary>Puts chunks from each sender back together; a frame not completed within 30 s is dropped.</summary>
    public sealed class Assembler
    {
        private readonly Dictionary<string, (int Count, Dictionary<int, byte[]> Got, DateTime At)> parts = new();
        private readonly object gate = new();

        public byte[]? Add(byte[] chunk, string sender, DateTime now)
        {
            lock (gate)
            {
                foreach (var k in parts.Where(p => (now - p.Value.At).TotalSeconds >= 30).Select(p => p.Key).ToList()) parts.Remove(k);
                if (chunk.Length < 8) return null;
                string tag = $"{sender}/{chunk[0]}.{chunk[1]}.{chunk[2]}.{chunk[3]}";
                int index = chunk[4] << 8 | chunk[5], count = chunk[6] << 8 | chunk[7];
                if (count <= 0 || index >= count) return null;
                if (!parts.TryGetValue(tag, out var e)) e = (count, new Dictionary<int, byte[]>(), now);
                e.Got[index] = chunk[8..];
                e.At = now;
                if (e.Got.Count == e.Count)
                {
                    parts.Remove(tag);
                    return Enumerable.Range(0, e.Count).SelectMany(i => e.Got[i]).ToArray();
                }
                parts[tag] = e;
                return null;
            }
        }
    }
}

/// <summary>Which link carries a clipboard message to which device (ClipRoute.swift, same rule on every platform).
/// "both": text and small control messages over Bluetooth, Wi-Fi only for devices Bluetooth cannot reach right now;
/// images and large replies over Wi-Fi, Bluetooth only for devices with no Wi-Fi route (when small enough).
/// "ble": Bluetooth only for what fits, Wi-Fi only when Bluetooth reaches nobody. "wifi": Wi-Fi only.</summary>
public static class ClipRoute
{
    public const int MaxBle = 64 * 1024;
    private static readonly HashSet<string> Large = new() { "image", "diag" };

    public static (HashSet<string> Ble, HashSet<string> Wifi) Plan(string kind, int size, string mode, IEnumerable<string> wanted,
                                                                    IEnumerable<string> ble, IEnumerable<string> wifi, int maxBle = MaxBle)
    {
        var want = wanted.ToHashSet();
        bool fits = size <= maxBle;
        var b = want.Intersect(ble).ToHashSet();
        var w = want.Intersect(wifi).ToHashSet();
        if (mode == "wifi") return (new(), w);
        if (Large.Contains(kind))
        {
            if (mode == "ble") return fits && b.Count > 0 ? (b, new()) : (new(), w);
            return (fits ? b.Except(w).ToHashSet() : new(), w);
        }
        if (!fits) return (new(), w);
        if (mode == "ble") return (b, b.Count == 0 ? w : new());
        return (b, w.Except(b).ToHashSet());
    }
}

/// <summary>Bluetooth health per device (BleHealth in ClipRoute.swift). A device that refused for lack of pairing gets
/// no Bluetooth tries for 6 hours; other failures rest it 2, 4, 8 and at most 10 minutes from the second in a row.</summary>
public sealed class BleHealth
{
    private readonly Dictionary<string, DateTime> until = new();
    private readonly Dictionary<string, int> fails = new();
    private readonly HashSet<string> auth = new(), worked = new();
    private readonly object gate = new();

    public bool Usable(string dev, DateTime now) { lock (gate) return !until.TryGetValue(dev, out var u) || now >= u; }
    public bool NeedsPairing(string dev) { lock (gate) return auth.Contains(dev); }
    public bool KnownGood(string dev) { lock (gate) return worked.Contains(dev); }

    public string? Report(string dev, bool ok, bool pairing, DateTime now)
    {
        lock (gate)
        {
            if (ok)
            {
                worked.Add(dev);
                bool was = until.Remove(dev) | (fails.Remove(dev, out var f) && f >= 2) | auth.Remove(dev);
                return was ? $"clip: Bluetooth to {dev} works again" : null;
            }
            if (pairing)
            {
                auth.Add(dev);
                until[dev] = now.AddHours(6);
                return $"clip: Bluetooth to {dev} needs pairing (accept the pairing request on both devices); no Bluetooth tries to it for 6 hours, Wi-Fi carries its clips";
            }
            int n = (fails.TryGetValue(dev, out var c) ? c : 0) + 1;
            fails[dev] = n;
            if (n < 2) return null;
            double wait = Math.Min(600, 120 * Math.Pow(2, Math.Min(n - 2, 3)));
            until[dev] = now.AddSeconds(wait);
            return $"clip: Bluetooth to {dev} failed {n} times in a row; next try in {(int)(wait / 60)} min, Wi-Fi carries its clips";
        }
    }

    public void Paired(string dev) { lock (gate) { until.Remove(dev); fails.Remove(dev); auth.Remove(dev); worked.Add(dev); } }
}
