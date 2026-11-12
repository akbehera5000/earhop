using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace EarHop;

/// <summary>Settings, saved as %APPDATA%\EarHop\config.json. Every field is optional when reading, so an older or
/// partial file still loads. The secret and earbud names never leave this computer.</summary>
public sealed class Config
{
    public string DeviceName { get; set; } = "pc";
    public string Secret { get; set; } = "";
    /// <summary>"buds" (Galaxy Buds2 Pro) or "airpods".</summary>
    public string Earbuds { get; set; } = "buds";
    /// <summary>Part of the earbuds' name as Windows shows it in Sound settings, per profile.</summary>
    public string BudsName { get; set; } = "Buds2 Pro";
    public string AirPodsName { get; set; } = "AirPods";
    public bool AutoSwitch { get; set; } = true;
    public bool LiveMode { get; set; } = true;
    public List<string> Priority { get; set; } = new() { "mac", "tab", "phone" };
    public double CooldownSeconds { get; set; } = 2;
    public double MinPlaySeconds { get; set; } = 1;
    public double QuietSeconds { get; set; } = 2;
    public double ReleaseAfterOutSeconds { get; set; } = 120;
    public int SafeModeAfterFailures { get; set; } = 3;
    public bool ClipSync { get; set; } = true;
    public bool ClipAuto { get; set; } = true;
    public bool ClipReceive { get; set; } = true;
    public bool ClipImages { get; set; } = true;
    public bool ClipNotify { get; set; } = false;
    public bool ClipSkipPasswords { get; set; } = false;
    public List<string> ClipExclude { get; set; } = new();
    public List<string> KnownDevices { get; set; } = new();
    public bool StartAtLogin { get; set; } = true;
    /// <summary>Where received files go; empty means the Downloads folder.</summary>
    public string DownloadFolder { get; set; } = "";

    [JsonIgnore] public bool IsBuds => Earbuds != "airpods";
    [JsonIgnore] public string EarbudsName => IsBuds ? BudsName : AirPodsName;
    [JsonIgnore] public string EarbudsShort => IsBuds ? "Buds" : "AirPods";

    /// <summary>Test runs set this so nothing touches the real settings (AGENTS.md section 9, mistake 3).</summary>
    public static bool ReadOnly;

    public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EarHop");
    public static string FilePath => Path.Combine(Dir, "config.json");

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase, ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    public static Config Load()
    {
        Config c;
        try { c = File.Exists(FilePath) ? JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath), Json) ?? new() : new(); }
        catch (Exception e) { Log.Write($"config: could not read ({e.Message}), using defaults"); c = new(); }
        if (!DeviceNames.Valid(c.DeviceName)) c.DeviceName = "pc";
        return c;
    }

    public void Save()
    {
        if (ReadOnly) return;
        try
        {
            Directory.CreateDirectory(Dir);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(this, Json));
            File.Move(tmp, FilePath, true);
        }
        catch (Exception e) { Log.Write($"config: could not save ({e.Message})"); }
    }

    public HandoffSettings HandoffSettings() => new()
    {
        DeviceName = DeviceName, Priority = new(Priority), Live = LiveMode, Cooldown = CooldownSeconds, MinPlay = MinPlaySeconds,
        Quiet = QuietSeconds, ReleaseAfterOut = ReleaseAfterOutSeconds, SafeModeAfter = SafeModeAfterFailures,
        // Windows has no ear reading yet (no Buds channel or AirPods beacon here), so an unknown ear counts as in,
        // like Android without a working ear source.
        RequireEar = false,
    };

    /// <summary>The secret as a pairing code the other apps accept (they show it as text and a QR code).</summary>
    public static string NewSecret() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(24)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}

/// <summary>File log with rotation at 2 MB (earhop.log, earhop.log.1), like the Mac. Never logs clipboard contents.</summary>
public static class Log
{
    public static bool ToFile = true;
    private static readonly object Gate = new();
    private static readonly List<string> Recent = new();
    public static string FilePath => Path.Combine(Config.Dir, "earhop.log");

    public static void Write(string line)
    {
        var s = $"{DateTime.Now:HH:mm:ss.fff} {line}";
        lock (Gate)
        {
            Recent.Add(s);
            if (Recent.Count > 2000) Recent.RemoveRange(0, Recent.Count - 2000);
            if (!ToFile) return;
            try
            {
                Directory.CreateDirectory(Config.Dir);
                var fi = new FileInfo(FilePath);
                if (fi.Exists && fi.Length > 2 * 1024 * 1024) File.Move(FilePath, FilePath + ".1", true);
                File.AppendAllText(FilePath, s + Environment.NewLine, Encoding.UTF8);
            }
            catch { }
        }
    }

    public static string Tail(int lines = 400)
    {
        lock (Gate) return string.Join(Environment.NewLine, Recent.Skip(Math.Max(0, Recent.Count - lines)));
    }
}
