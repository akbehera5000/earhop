using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace EarHop;

/// <summary>One clip or control message. Kinds: text, image, link, file (clips); ack, diag-req, diag, batt, vol,
/// settings (control). `To` names the one device it is for, or "" for every device.</summary>
public sealed record ClipItem(string Id, string Dev, string Kind, string Mime, long Ts, byte[] Data, string To = "");

/// <summary>The encrypted frame, identical on the Mac and Android: "EHC1" | nonce (12) | AES-256-GCM ciphertext | tag (16),
/// "EHC1" as associated data. Plaintext: header length (2, big endian) | header JSON | data.
/// Key: HMAC-SHA256(secret, "earhop clip v1").</summary>
public static class ClipCodec
{
    public static readonly byte[] Magic = Encoding.ASCII.GetBytes("EHC1");
    /// <summary>Clips older than this (by the sender's clock) are refused.</summary>
    public const long MaxAgeMs = 300_000;

    public static byte[] Key(string secret) => Proto.SignRaw(secret, Encoding.UTF8.GetBytes("earhop clip v1"));

    public static string NewId() => Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();

    /// <summary>AES-GCM in CryptoKit's "combined" layout: nonce | ciphertext | tag.</summary>
    public static byte[] Seal(byte[] key, byte[] plain, byte[] aad, byte[]? nonce = null)
    {
        nonce ??= RandomNumberGenerator.GetBytes(12);
        var ct = new byte[plain.Length];
        var tag = new byte[16];
        using var g = new AesGcm(key, 16);
        g.Encrypt(nonce, plain, ct, tag, aad);
        return nonce.Concat(ct).Concat(tag).ToArray();
    }

    public static byte[]? Open(byte[] key, ReadOnlySpan<byte> combined, byte[] aad)
    {
        if (combined.Length < 12 + 16) return null;
        var nonce = combined[..12];
        var ct = combined[12..^16];
        var tag = combined[^16..];
        var plain = new byte[ct.Length];
        try
        {
            using var g = new AesGcm(key, 16);
            g.Decrypt(nonce, ct, tag, plain, aad);
            return plain;
        }
        catch (CryptographicException) { return null; }
    }

    public static byte[]? Encode(ClipItem item, string secret, byte[]? nonce = null)
    {
        var header = new SortedDictionary<string, object>(StringComparer.Ordinal)
        {
            ["dev"] = item.Dev, ["id"] = item.Id, ["kind"] = item.Kind, ["mime"] = item.Mime, ["ts"] = item.Ts, ["v"] = 1,
        };
        if (item.To.Length > 0) header["to"] = item.To;
        var h = JsonSerializer.SerializeToUtf8Bytes(header);
        if (h.Length >= 65536) return null;
        var plain = new byte[2 + h.Length + item.Data.Length];
        plain[0] = (byte)(h.Length >> 8); plain[1] = (byte)(h.Length & 0xFF);
        h.CopyTo(plain, 2);
        item.Data.CopyTo(plain, 2 + h.Length);
        return Magic.Concat(Seal(Key(secret), plain, Magic, nonce)).ToArray();
    }

    public static ClipItem? Decode(byte[] frame, string secret)
    {
        if (frame.Length <= 4 + 12 + 16 || !frame.AsSpan(0, 4).SequenceEqual(Magic)) return null;
        var p = Open(Key(secret), frame.AsSpan(4), Magic);
        if (p is null || p.Length < 2) return null;
        int hl = p[0] << 8 | p[1];
        if (p.Length < 2 + hl) return null;
        try
        {
            using var doc = JsonDocument.Parse(p.AsMemory(2, hl));
            var o = doc.RootElement;
            string S(string k) => o.TryGetProperty(k, out var e) && e.ValueKind == JsonValueKind.String ? e.GetString() ?? "" : "";
            if (!o.TryGetProperty("ts", out var tsE) || !tsE.TryGetInt64(out long ts)) return null;
            foreach (var k in new[] { "id", "dev", "kind", "mime" })
                if (!o.TryGetProperty(k, out var e) || e.ValueKind != JsonValueKind.String) return null;
            return new ClipItem(S("id"), S("dev"), S("kind"), S("mime"), ts, p[(2 + hl)..], S("to"));
        }
        catch (JsonException) { return null; }
    }
}

/// <summary>Files on the Wi-Fi port: "EHF1" | header length (4) | header frame (kind file, data {"name","size"}) |
/// records | 0. Each record: length (4) | AES-GCM combined of up to 1 MB, associated data "EHF1" + id + record number (4).
/// Same as FileStream in FileTransfer.swift.</summary>
public static class FileCrypt
{
    public static readonly byte[] Magic = Encoding.ASCII.GetBytes("EHF1");
    public const int Chunk = 1 << 20;

    public static byte[] Aad(string id, uint index) =>
        Magic.Concat(Encoding.UTF8.GetBytes(id)).Concat(Be32((int)index)).ToArray();

    public static byte[] Seal(byte[] data, string secret, string id, uint index) => ClipCodec.Seal(ClipCodec.Key(secret), data, Aad(id, index));

    public static byte[]? Open(byte[] combined, string secret, string id, uint index) => ClipCodec.Open(ClipCodec.Key(secret), combined, Aad(id, index));

    public static byte[] Be32(int n) => new[] { (byte)((n >> 24) & 0xFF), (byte)((n >> 16) & 0xFF), (byte)((n >> 8) & 0xFF), (byte)(n & 0xFF) };
    public static int Int(ReadOnlySpan<byte> d) { int v = 0; foreach (var b in d) v = v << 8 | b; return v; }

    /// <summary>A file name safe to save: no folders, no hidden names, no characters Windows refuses, at most 120.</summary>
    public static string SafeName(string n)
    {
        var s = n.Replace('\\', '/');
        s = s[(s.LastIndexOf('/') + 1)..];
        foreach (var c in Path.GetInvalidFileNameChars().Concat(new[] { ':', '*', '?', '"', '<', '>', '|' })) s = s.Replace(c, '-');
        s = s.TrimStart('.').TrimEnd(' ', '.');
        if (s.Length == 0) s = "file";
        return s.Length > 120 ? s[..120] : s;
    }

    /// <summary>A free path in dir for name ("photo.jpg", then "photo 2.jpg", ...).</summary>
    public static string FreePath(string dir, string name)
    {
        string b = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name);
        string p = Path.Combine(dir, name);
        for (int n = 2; File.Exists(p); n++) p = Path.Combine(dir, $"{b} {n}{ext}");
        return p;
    }
}

/// <summary>Delivery receipts (Receipts in Clipboard.swift): retry after 5 and 15 s, "not delivered" after 60 s.</summary>
public sealed class Receipts
{
    public sealed class Entry { public HashSet<string> Targets = new(); public HashSet<string> Delivered = new(); public DateTime SentAt; public int Retries; }
    public Dictionary<string, Entry> Entries { get; private set; } = new();
    public static readonly double[] RetryAfter = { 5, 15 };
    public const double GiveUp = 60;
    private readonly object gate = new();

    public void Track(string id, IEnumerable<string> targets, DateTime now)
    {
        lock (gate)
        {
            Entries = Entries.Where(kv => (now - kv.Value.SentAt).TotalSeconds < 600).ToDictionary(kv => kv.Key, kv => kv.Value);
            Entries[id] = new Entry { Targets = targets.ToHashSet(), SentAt = now };
        }
    }

    public bool Ack(string id, string dev)
    {
        lock (gate)
        {
            if (!Entries.TryGetValue(id, out var e) || !e.Targets.Contains(dev) || e.Delivered.Contains(dev)) return false;
            e.Delivered.Add(dev);
            return true;
        }
    }

    public List<(string Id, List<string> Devs)> Due(DateTime now)
    {
        var output = new List<(string, List<string>)>();
        lock (gate)
        {
            foreach (var (id, e) in Entries)
            {
                var waiting = e.Targets.Except(e.Delivered).OrderBy(x => x, StringComparer.Ordinal).ToList();
                if (waiting.Count == 0 || e.Retries >= RetryAfter.Length || (now - e.SentAt).TotalSeconds < RetryAfter[e.Retries]) continue;
                e.Retries += 1;
                output.Add((id, waiting));
            }
        }
        return output;
    }

    public (List<string> Delivered, List<string> Waiting, List<string> Failed) State(string id, DateTime now)
    {
        lock (gate)
        {
            if (!Entries.TryGetValue(id, out var e)) return (new(), new(), new());
            var rest = e.Targets.Except(e.Delivered).OrderBy(x => x, StringComparer.Ordinal).ToList();
            bool over = (now - e.SentAt).TotalSeconds >= GiveUp;
            return (e.Delivered.OrderBy(x => x, StringComparer.Ordinal).ToList(), over ? new() : rest, over ? rest : new());
        }
    }

    public string Words(string id, DateTime now)
    {
        var st = State(id, now);
        var parts = new List<string>();
        if (st.Delivered.Count > 0) parts.Add("Delivered to " + string.Join(", ", st.Delivered.Select(d => DeviceNames.Pretty(d) + " ✓")));
        if (st.Waiting.Count > 0) parts.Add("sending to " + string.Join(", ", st.Waiting.Select(DeviceNames.Pretty)));
        if (st.Failed.Count > 0) parts.Add("not delivered to " + string.Join(", ", st.Failed.Select(DeviceNames.Pretty)));
        if (parts.Count == 0) return "";
        parts[0] = char.ToUpperInvariant(parts[0][0]) + parts[0][1..];
        return string.Join("; ", parts);
    }
}

/// <summary>Text that looks like a password (PasswordLike in Clipboard.swift); used only when the setting is on.</summary>
public static class PasswordLike
{
    public static bool Looks(string t)
    {
        if (t.Length < 8 || t.Length > 64 || t.Any(char.IsWhiteSpace)) return false;
        if (t.Contains("://") || t.ToLowerInvariant().StartsWith("www.")) return false;
        if (Regex.IsMatch(t, @"^[^@\s]+@[^@\s]+\.[^@\s]+$")) return false;
        int kinds = new[] { t.Any(char.IsLower), t.Any(char.IsUpper), t.Any(char.IsDigit), t.Any(c => !char.IsLetterOrDigit(c)) }.Count(x => x);
        return kinds >= 3;
    }
}
