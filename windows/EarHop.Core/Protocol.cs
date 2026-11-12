using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace EarHop;

public enum Ear { Both, One, None, Unknown }

public static class EarText
{
    public static string Raw(Ear e) => e switch { Ear.Both => "both", Ear.One => "one", Ear.None => "none", _ => "unknown" };
    public static Ear? Parse(string s) => s switch { "both" => Ear.Both, "one" => Ear.One, "none" => Ear.None, "unknown" => Ear.Unknown, _ => null };
}

/// <summary>One device's status. Same fields as Status in Protocol.swift and Proto.kt.</summary>
public record struct Status(string Dev, long Ts, int Seq, bool Playing, Ear Ear, bool Owner, bool Call = false,
                            string Prof = "", bool Att = false, int V = 4);

/// <summary>Device names are free: 1 to 20 characters, lower case letters, digits and "-", starting with a letter or
/// digit. Bluetooth LE carries a 16 bit id instead: the first two bytes of HMAC(secret, "earhop dev v4|" + name).</summary>
public static class DeviceNames
{
    public static bool Valid(string n)
    {
        if (n.Length < 1 || n.Length > 20) return false;
        char f = n[0];
        if (f > 127 || !char.IsLetterOrDigit(f)) return false;
        foreach (char c in n)
            if (c > 127 || !(char.IsLower(c) || char.IsDigit(c) || c == '-')) return false;
        return true;
    }

    public static int Id(string name, string secret)
    {
        var m = Proto.SignRaw(secret, Encoding.UTF8.GetBytes("earhop dev v4|" + name));
        return m[0] << 8 | m[1];
    }

    /// <summary>"phone" to "Phone", "work-pc" to "Work-pc"; "mac" and "tab" read as Mac and Tablet.</summary>
    public static string Pretty(string n) => n switch
    {
        "mac" => "Mac", "tab" => "Tablet", "phone" => "Phone", "" => "",
        _ => char.ToUpperInvariant(n[0]) + n[1..],
    };
}

/// <summary>Every device name this device knows and the Bluetooth LE id of each (NameBook in Protocol.swift).</summary>
public sealed class NameBook
{
    public static readonly NameBook Shared = new();
    private readonly object gate = new();
    private readonly List<string> names = new() { "mac", "phone", "tab" };
    private Dictionary<int, string> ids = new();
    private string secret = "";
    public event Action<List<string>>? Changed;

    public void Setup(string secret, IEnumerable<string> known)
    {
        lock (gate)
        {
            this.secret = secret;
            foreach (var n in known) if (DeviceNames.Valid(n) && !names.Contains(n)) names.Add(n);
            ids = new();
            foreach (var n in names) ids.TryAdd(DeviceNames.Id(n, secret), n);
        }
    }

    public bool Add(string n)
    {
        if (!DeviceNames.Valid(n)) return false;
        List<string> all;
        lock (gate)
        {
            if (names.Contains(n)) return false;
            names.Add(n);
            ids[DeviceNames.Id(n, secret)] = n;
            all = new(names);
        }
        Changed?.Invoke(all);
        return true;
    }

    public string? Name(int id) { lock (gate) return ids.TryGetValue(id, out var n) ? n : null; }
    public List<string> All { get { lock (gate) return new(names); } }
}

/// <summary>Learns each device's clock offset and refuses copies far off it (ClockGuard in Protocol.swift).</summary>
public sealed class ClockGuard
{
    public static readonly ClockGuard Shared = new();
    private sealed class Entry { public long Offset; public long? Cand; public int CandCount; public int CandSeq; }
    private readonly Dictionary<string, Entry> map = new();
    private readonly object gate = new();
    public const long WifiTolerance = 15_000;
    public const long BleTolerance = 90_000;

    public bool Check(string dev, long senderMs, long nowMs, long toleranceMs, int seq)
    {
        lock (gate)
        {
            long seen = senderMs - nowMs;
            if (!map.TryGetValue(dev, out var e)) { map[dev] = new Entry { Offset = seen }; return true; }
            if (Math.Abs(seen - e.Offset) <= toleranceMs)
            {
                // Copies are never early, only late: the largest offset seen is the closest to the truth.
                if (seen > e.Offset) e.Offset = seen;
                e.Cand = null; e.CandCount = 0;
                return true;
            }
            if (e.Cand is long c && Math.Abs(seen - c) <= toleranceMs && BlePayload.IsNewer(seq & 0xFFFF, e.CandSeq & 0xFFFF))
            {
                e.CandCount += 1; e.CandSeq = seq;
                if (e.CandCount >= 3) { e.Offset = Math.Max(c, seen); e.Cand = null; e.CandCount = 0; return true; }
            }
            else if (e.Cand is not long c2 || Math.Abs(seen - c2) > toleranceMs)
            {
                e.Cand = seen; e.CandCount = 1; e.CandSeq = seq;
            }
            return false;
        }
    }

    public long? Offset(string dev) { lock (gate) return map.TryGetValue(dev, out var e) ? e.Offset : null; }

    /// <summary>For clips: within maxAgeMs of now by that device's clock; accepted before its first status.</summary>
    public bool Fresh(string dev, long senderMs, long nowMs, long maxAgeMs)
    {
        var o = Offset(dev);
        return o is null || Math.Abs(senderMs - nowMs - o.Value) <= maxAgeMs;
    }

    public void Reset() { lock (gate) map.Clear(); }
}

public static class Proto
{
    public static string Canonical(Status s, int v = 2)
    {
        string b = $"{v}|{s.Dev}|{s.Ts}|{s.Seq}|{(s.Playing ? 1 : 0)}|{EarText.Raw(s.Ear)}|{(s.Owner ? 1 : 0)}";
        return v switch
        {
            1 => b,
            2 => b + $"|{(s.Call ? 1 : 0)}",
            3 => b + $"|{(s.Call ? 1 : 0)}|{s.Prof}",
            _ => b + $"|{(s.Call ? 1 : 0)}|{s.Prof}|{(s.Att ? 1 : 0)}",
        };
    }

    public static byte[] SignRaw(string secret, byte[] data) => HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), data);

    public static string Sign(string secret, string text) => Convert.ToHexString(SignRaw(secret, Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    public static byte[] Encode(Status s, string secret)
    {
        var o = new SortedDictionary<string, object>(StringComparer.Ordinal)
        {
            ["att"] = s.Att ? 1 : 0, ["call"] = s.Call ? 1 : 0, ["dev"] = s.Dev, ["ear"] = EarText.Raw(s.Ear),
            ["owner"] = s.Owner ? 1 : 0, ["playing"] = s.Playing ? 1 : 0, ["prof"] = s.Prof, ["seq"] = s.Seq,
            ["sig"] = Sign(secret, Canonical(s, 4)), ["ts"] = s.Ts, ["v"] = 4,
        };
        return JsonSerializer.SerializeToUtf8Bytes(o);
    }

    private static bool Int(JsonElement o, string k, out long v)
    {
        v = 0;
        return o.TryGetProperty(k, out var e) && e.ValueKind == JsonValueKind.Number && e.TryGetInt64(out v);
    }

    private static bool Str(JsonElement o, string k, out string v)
    {
        v = "";
        if (!o.TryGetProperty(k, out var e) || e.ValueKind != JsonValueKind.String) return false;
        v = e.GetString() ?? "";
        return true;
    }

    public static Status? Decode(byte[] data, string secret, long nowMs)
    {
        try
        {
            using var doc = JsonDocument.Parse(data);
            var o = doc.RootElement;
            if (o.ValueKind != JsonValueKind.Object) return null;
            if (!Int(o, "v", out long v) || v < 1 || v > 4) return null;
            if (!Str(o, "dev", out var dev) || dev.Contains('|')) return null;
            if (!Int(o, "ts", out long ts) || !Int(o, "seq", out long seq) || !Int(o, "playing", out long playing)) return null;
            if (!Str(o, "ear", out var earRaw) || EarText.Parse(earRaw) is not Ear ear) return null;
            if (!Int(o, "owner", out long owner) || !Str(o, "sig", out var sig)) return null;
            bool call = false;
            if (v >= 2) { if (!Int(o, "call", out long c)) return null; call = c == 1; }
            string prof = "";
            if (v >= 3) { if (!Str(o, "prof", out prof) || !(prof is "" or "airpods" or "buds")) return null; }
            bool att = false;
            if (v >= 4) { if (!Int(o, "att", out long a) || !DeviceNames.Valid(dev)) return null; att = a == 1; }
            var s = new Status(dev, ts, (int)seq, playing == 1, ear, owner == 1, call, prof, att, (int)v);
            var expected = Encoding.UTF8.GetBytes(Sign(secret, Canonical(s, (int)v)));
            var got = Encoding.UTF8.GetBytes(sig);
            if (!CryptographicOperations.FixedTimeEquals(expected, got)) return null;
            // v4 carries the sender's clock (ClockGuard checks it); older versions need the clocks within 30 s.
            if (v < 4 && Math.Abs(nowMs - ts) > 30000) return null;
            return s;
        }
        catch (JsonException) { return null; }
    }

    /// <summary>For a refused packet: who sent it, if it is a well formed EarHop packet (shown to the user only).</summary>
    public static string? RejectedSender(byte[] data)
    {
        try
        {
            using var doc = JsonDocument.Parse(data);
            var o = doc.RootElement;
            return o.ValueKind == JsonValueKind.Object && Str(o, "dev", out var dev) && Str(o, "sig", out _) && Int(o, "ts", out _) ? dev : null;
        }
        catch (JsonException) { return null; }
    }

    public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}

/// <summary>The 16 byte Bluetooth LE status (BlePayload in Protocol.swift). v4 and the old format are read.</summary>
public static class BlePayload
{
    private static readonly Dictionary<string, byte> DevCodes = new() { ["mac"] = 0, ["phone"] = 1, ["tab"] = 2 };
    public static byte EarCode(Ear e) => e switch { Ear.Unknown => 0, Ear.None => 1, Ear.One => 2, _ => 3 };
    public static Ear EarOf(int c) => (c & 3) switch { 0 => Ear.Unknown, 1 => Ear.None, 2 => Ear.One, _ => Ear.Both };
    public static int Minute(long ms) => (int)((ms / 60000) & 0xFFFFFF);

    private static byte ProfBits(string p) => p == "buds" ? (byte)96 : p == "airpods" ? (byte)32 : (byte)0;

    public static byte[]? EncodeOld(Status s, string secret, int minute)
    {
        if (!DevCodes.TryGetValue(s.Dev, out byte dev)) return null;
        byte flags = (byte)((s.Playing ? 1 : 0) | (EarCode(s.Ear) << 1) | (s.Owner ? 8 : 0) | (s.Call ? 16 : 0) | ProfBits(s.Prof));
        byte[] head = { 0xEA, dev, (byte)((s.Seq >> 8) & 0xFF), (byte)(s.Seq & 0xFF), flags };
        byte[] stamp = { (byte)((minute >> 16) & 0xFF), (byte)((minute >> 8) & 0xFF), (byte)(minute & 0xFF) };
        var mac = Proto.SignRaw(secret, head.Concat(stamp).ToArray());
        return head.Concat(mac.Take(8)).Concat(stamp).ToArray();
    }

    public static byte[] Encode4(Status s, string secret, uint sec)
    {
        int id = DeviceNames.Id(s.Dev, secret);
        byte flags = (byte)((s.Playing ? 1 : 0) | (EarCode(s.Ear) << 1) | (s.Owner ? 8 : 0) | (s.Call ? 16 : 0) | ProfBits(s.Prof) | (s.Att ? 128 : 0));
        byte[] head = { 0xEB, (byte)(id >> 8), (byte)(id & 0xFF), (byte)((s.Seq >> 8) & 0xFF), (byte)(s.Seq & 0xFF), flags };
        byte[] stamp = { (byte)(sec >> 24), (byte)((sec >> 16) & 0xFF), (byte)((sec >> 8) & 0xFF), (byte)(sec & 0xFF) };
        var mac = Proto.SignRaw(secret, head.Concat(stamp).ToArray());
        return head.Concat(mac.Take(6)).Concat(stamp).ToArray();
    }

    public readonly record struct V4(int Id, int Seq, byte Flags, uint Sec);

    public static V4? Decode4(byte[] b, string secret)
    {
        if (b.Length != 16 || b[0] != 0xEB) return null;
        var mac = Proto.SignRaw(secret, b[..6].Concat(b[12..16]).ToArray());
        if (!CryptographicOperations.FixedTimeEquals(mac.AsSpan(0, 6), b.AsSpan(6, 6))) return null;
        uint sec = (uint)b[12] << 24 | (uint)b[13] << 16 | (uint)b[14] << 8 | b[15];
        return new V4(b[1] << 8 | b[2], b[3] << 8 | b[4], b[5], sec);
    }

    public static Status Status4(V4 m, string name)
    {
        byte f = m.Flags;
        string prof = (f & 32) == 0 ? "" : ((f & 64) != 0 ? "buds" : "airpods");
        return new Status(name, (long)m.Sec * 1000, m.Seq, (f & 1) != 0, EarOf(f >> 1), (f & 8) != 0, (f & 16) != 0, prof, (f & 128) != 0, 4);
    }

    /// <summary>The old format (0xEA, fixed device codes, minute stamp), refused when more than 2 minutes off.</summary>
    public static Status? DecodeOld(byte[] b, string secret, int nowMinute)
    {
        if (b.Length != 16 || b[0] != 0xEA) return null;
        var mac = Proto.SignRaw(secret, b[..5].Concat(b[13..16]).ToArray());
        if (!CryptographicOperations.FixedTimeEquals(mac.AsSpan(0, 8), b.AsSpan(5, 8))) return null;
        string? name = DevCodes.FirstOrDefault(kv => kv.Value == b[1]).Key;
        if (name is null) return null;
        int m = b[13] << 16 | b[14] << 8 | b[15];
        int d = (nowMinute - m) & 0xFFFFFF;
        if (Math.Min(d, 0x1000000 - d) > 2) return null;
        byte f = b[4];
        string prof = (f & 32) == 0 ? "" : ((f & 64) != 0 ? "buds" : "airpods");
        return new Status(name, Proto.NowMs(), b[2] << 8 | b[3], (f & 1) != 0, EarOf(f >> 1), (f & 8) != 0, (f & 16) != 0, prof, false, 3);
    }

    /// <summary>True when new is ahead of old in 16 bit wrapping order.</summary>
    public static bool IsNewer(int @new, int old)
    {
        int d = (@new - old) & 0xFFFF;
        return d != 0 && d < 0x8000;
    }
}

/// <summary>Merges the copies of one device's status from both links (PeerOrder in Protocol.swift).</summary>
public static class PeerOrder
{
    public const int RestartGap = 300;

    public static int Widen(int low16, int last)
    {
        int b = (last & ~0xFFFF) | (low16 & 0xFFFF);
        int[] c = { b - 0x10000, b, b + 0x10000 };
        return c.OrderBy(x => Math.Abs((long)x - last)).First();
    }

    public static (bool Take, int Seq) Accept(int? lastSeq, int seq, bool ble, long ts = long.MaxValue, long lastTs = long.MinValue)
    {
        if (lastSeq is not int last) return (true, seq);
        int full = ble ? Widen(seq, last) : seq;
        if (full > last) return (true, full);
        if (last - full > RestartGap && ts > lastTs) return (true, seq);
        return (false, full);
    }
}

/// <summary>Addresses heard within 10 minutes, newest first, or else only the newest (Link.current on the Mac).</summary>
public static class AddressBook
{
    public static List<T> Current<T>(IList<T> newestLast, IDictionary<T, DateTime> times, DateTime now) where T : notnull
    {
        DateTime At(T a) => times.TryGetValue(a, out var t) ? t : DateTime.MinValue;
        var newestFirst = newestLast.OrderByDescending(At).ToList();
        var fresh = newestFirst.Where(a => (now - At(a)).TotalSeconds < 600).ToList();
        return fresh.Count == 0 ? newestFirst.Take(1).ToList() : fresh;
    }
}
