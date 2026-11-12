using System.Drawing.Imaging;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;

namespace EarHop.Win;

/// <summary>Clipboard sync on Windows over Wi-Fi: watches the clipboard, sends text and images to the other devices,
/// applies what arrives, answers with receipts and diagnostics, opens links and saves files (ClipSync in
/// Clipboard.swift). Never logs clipboard contents, only kinds and sizes.</summary>
sealed class ClipSync : IDisposable
{
    public const int MaxText = 1024 * 1024;
    public const int MaxImage = 10 * 1024 * 1024;
    private Config cfg;
    private readonly UdpLink link;
    private readonly ClipTcp tcp = new();
    public readonly Receipts Receipts = new();
    private readonly Dictionary<string, (byte[] Frame, string Kind, DateTime At)> outbox = new();
    private readonly List<string> seen = new();
    private readonly Dictionary<string, DateTime> ackedAt = new();
    private readonly object gate = new();
    private readonly SynchronizationContext ui;
    private uint ownWrite;
    public DateTime PausedUntil = DateTime.MinValue;
    private bool Paused => PausedUntil > DateTime.UtcNow;
    public Func<string> Diagnostics = () => "";
    public event Action<string>? OnEvent;
    public event Action<string, string>? OnNotify; // (title, body)
    public event Action<string, string>? OnFileReceived; // (path, from)
    public event Action<ClipItem>? OnControl;
    public bool Listening => tcp.Listening;

    public ClipSync(Config cfg, UdpLink link)
    {
        this.cfg = cfg;
        this.link = link;
        ui = SynchronizationContext.Current ?? new SynchronizationContext();
    }

    public Config Cfg { set { cfg = value; tcp.Secret = value.Secret; } }

    public void Start()
    {
        tcp.Secret = cfg.Secret;
        tcp.Allowed = link.Allowed;
        tcp.OnFrame = f => Receive(f);
        tcp.AcceptFile = AcceptFile;
        tcp.OnFile = (item, path) => ui.Post(_ => FileDone(item, path), null);
        tcp.Start();
    }

    /// <summary>Devices reachable over Wi-Fi now, without this one and the excluded ones (unless named in `only`).</summary>
    private List<(string Dev, IPAddress Host)> Targets(ICollection<string>? only)
    {
        var skip = cfg.ClipExclude.ToHashSet();
        return link.PeerHostsByDev().Where(p => p.Dev != cfg.DeviceName && (only?.Contains(p.Dev) ?? !skip.Contains(p.Dev))).ToList();
    }

    public List<string> Devices() => Targets(null).Select(t => t.Dev).Distinct().OrderBy(x => x, StringComparer.Ordinal).ToList();

    private int Deliver(byte[] frame, ICollection<string>? only)
    {
        var targets = Targets(only);
        // One address per device is enough when it works; try the next ones only after a failure.
        foreach (var g in targets.GroupBy(t => t.Dev))
        {
            var hosts = g.Select(x => x.Host).ToList();
            _ = Task.Run(async () => { foreach (var h in hosts) if (await tcp.Send(frame, h)) break; });
        }
        return targets.Select(t => t.Dev).Distinct().Count();
    }

    public void SendControl(string kind, byte[] data, string? to)
    {
        var item = new ClipItem(ClipCodec.NewId(), cfg.DeviceName, kind, "application/octet-stream", Proto.NowMs(), data, to ?? "");
        if (ClipCodec.Encode(item, cfg.Secret) is not byte[] frame) return;
        Remember(item.Id);
        Deliver(frame, to == null ? null : new[] { to });
    }

    /// <summary>Called twice a second: quiet retries for devices that did not confirm, and give-ups after 60 s.</summary>
    public void Tick()
    {
        var now = DateTime.UtcNow;
        foreach (var (id, devs) in Receipts.Due(now))
        {
            if (!outbox.TryGetValue(id, out var o)) continue;
            Deliver(o.Frame, devs);
            Log.Write($"clip: no receipt from {string.Join(", ", devs)} yet, sending again");
        }
        foreach (var (id, o) in outbox.ToList())
        {
            if ((now - o.At).TotalSeconds < Receipts.GiveUp) continue;
            outbox.Remove(id);
            var st = Receipts.State(id, now);
            if (st.Failed.Count > 0) Log.Write($"clip: not delivered to {string.Join(", ", st.Failed)} (no receipt in 60 s)");
        }
    }

    // MARK: sending

    /// <summary>The clipboard changed (WM_CLIPBOARDUPDATE). Sends it when automatic sending is on.</summary>
    public void ClipboardChanged()
    {
        if (!cfg.ClipSync || !cfg.ClipAuto || Paused) return;
        if (Native.GetClipboardSequenceNumber() == ownWrite) return;
        SendCurrent(false);
    }

    /// <summary>"Send clipboard now" from the tray menu, also when automatic sending is off.</summary>
    public void SendNow()
    {
        if (!cfg.ClipSync) { OnEvent?.Invoke("Clipboard sync is off"); return; }
        SendCurrent(true);
    }

    private void SendCurrent(bool manual)
    {
        try
        {
            var data = Clipboard.GetDataObject();
            if (data == null) return;
            // Password managers mark secrets so clipboard tools leave them alone: never send those.
            if (data.GetDataPresent("ExcludeClipboardContentFromMonitorProcessing") || data.GetDataPresent("Clipboard Viewer Ignore")
                || (data.GetData("CanIncludeInClipboardHistory") is MemoryStream m && m.Length >= 4 && BitConverter.ToInt32(m.ToArray(), 0) == 0))
            {
                Log.Write("clip: skipped an item marked private (password manager)");
                return;
            }
            if (Clipboard.ContainsText())
            {
                var t = Clipboard.GetText();
                if (t.Length == 0) return;
                if (!manual && cfg.ClipSkipPasswords && PasswordLike.Looks(t)) { Log.Write("clip: skipped text that looks like a password (Skip password-like text is on)"); return; }
                var d = Encoding.UTF8.GetBytes(t);
                if (d.Length > MaxText) { Log.Write($"clip: text too large to send ({d.Length} bytes)"); return; }
                Send("text", "text/plain", d);
            }
            else if (cfg.ClipImages && Clipboard.ContainsImage() && Clipboard.GetImage() is Image img)
            {
                using var ms = new MemoryStream();
                img.Save(ms, ImageFormat.Png);
                img.Dispose();
                if (ms.Length > MaxImage) { Log.Write($"clip: image too large to send ({ms.Length / 1024} KB)"); return; }
                Send("image", "image/png", ms.ToArray());
            }
            else if (manual) OnEvent?.Invoke("Nothing to send");
        }
        catch (ExternalException e) { Log.Write($"clip: could not read the clipboard ({e.Message})"); }
    }

    private void Send(string kind, string mime, byte[] data)
    {
        var item = new ClipItem(ClipCodec.NewId(), cfg.DeviceName, kind, mime, Proto.NowMs(), data);
        if (ClipCodec.Encode(item, cfg.Secret) is not byte[] frame) return;
        Remember(item.Id);
        var targets = Devices();
        string what = kind == "text" ? $"text ({data.Length} bytes)" : $"image ({data.Length / 1024} KB)";
        if (Deliver(frame, null) == 0)
        {
            Log.Write($"clip: {what} not sent, no other device on Wi-Fi");
            OnEvent?.Invoke("Not sent, no device on Wi-Fi");
            return;
        }
        Log.Write($"clip: sent {what} over Wi-Fi");
        Receipts.Track(item.Id, targets, DateTime.UtcNow);
        outbox[item.Id] = (frame, kind, DateTime.UtcNow);
        OnEvent?.Invoke($"Sent {(kind == "text" ? "text" : "an image")}");
    }

    /// <summary>Sends files (any size, Wi-Fi only) to the given devices, one stream per device.</summary>
    public void SendFiles(IList<string> paths, ICollection<string>? only)
    {
        var targets = Targets(only).GroupBy(t => t.Dev).ToList();
        if (targets.Count == 0) { OnNotify?.Invoke("Not sent", "No other device is on Wi-Fi right now."); Log.Write("file: not sent, no device on Wi-Fi"); return; }
        foreach (var path in paths)
        {
            long size = new FileInfo(path).Length;
            string name = FileCrypt.SafeName(Path.GetFileName(path));
            string id = ClipCodec.NewId();
            Receipts.Track(id, targets.Select(g => g.Key), DateTime.UtcNow);
            foreach (var g in targets)
            {
                var meta = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object> { ["name"] = name, ["size"] = size });
                var item = new ClipItem(id, cfg.DeviceName, "file", "application/octet-stream", Proto.NowMs(), meta, g.Key);
                if (ClipCodec.Encode(item, cfg.Secret) is not byte[] header) continue;
                string dev = g.Key;
                var hosts = g.Select(x => x.Host).ToList();
                Log.Write($"file: sending {name} ({size / 1024} KB) to {dev}");
                _ = Task.Run(async () =>
                {
                    bool ok = false;
                    foreach (var h in hosts) if (ok = await tcp.SendFile(path, header, id, h)) break;
                    Log.Write($"file: {name} to {dev} {(ok ? "sent, waiting for the receipt" : "failed")}");
                    if (!ok) ui.Post(_ => OnNotify?.Invoke("Not sent", $"{name} could not be sent to {DeviceNames.Pretty(dev)}."), null);
                });
            }
        }
        OnEvent?.Invoke(paths.Count == 1 ? $"Sending {Path.GetFileName(paths[0])}" : $"Sending {paths.Count} files");
    }

    /// <summary>Opens a web link on another device ("continue on").</summary>
    public void SendLink(string url, string dev)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u) || (u.Scheme != "http" && u.Scheme != "https")) { OnEvent?.Invoke("Not a web link"); return; }
        var item = new ClipItem(ClipCodec.NewId(), cfg.DeviceName, "link", "text/uri-list", Proto.NowMs(), Encoding.UTF8.GetBytes(url), dev);
        if (ClipCodec.Encode(item, cfg.Secret) is not byte[] frame) return;
        Remember(item.Id);
        if (Deliver(frame, new[] { dev }) == 0) { OnEvent?.Invoke($"{DeviceNames.Pretty(dev)} is not in reach"); return; }
        Receipts.Track(item.Id, new[] { dev }, DateTime.UtcNow);
        outbox[item.Id] = (frame, "link", DateTime.UtcNow);
        Log.Write($"link: sent to {dev}");
    }

    public void RequestDiagnostics() => SendControl("diag-req", Array.Empty<byte>(), null);

    // MARK: receiving

    private void Remember(string id)
    {
        lock (gate) { seen.Add(id); if (seen.Count > 100) seen.RemoveRange(0, seen.Count - 100); }
    }

    private bool ReceiptDue(string id)
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            if (ackedAt.TryGetValue(id, out var at) && (now - at).TotalSeconds < 3) return false;
            ackedAt[id] = now;
            if (ackedAt.Count > 200) foreach (var k in ackedAt.Where(kv => (now - kv.Value).TotalSeconds >= 60).Select(kv => kv.Key).ToList()) ackedAt.Remove(k);
            return true;
        }
    }

    private void Receive(byte[] frame)
    {
        if (ClipCodec.Decode(frame, cfg.Secret) is not ClipItem item) return;
        bool content = item.Kind is "text" or "image" or "link";
        if (content && !(cfg.ClipSync && cfg.ClipReceive && !Paused)) return;
        if (item.Dev == cfg.DeviceName || !ClockGuard.Shared.Fresh(item.Dev, item.Ts, Proto.NowMs(), ClipCodec.MaxAgeMs)) return;
        if (item.To.Length > 0 && item.To != cfg.DeviceName) return;
        NameBook.Shared.Add(item.Dev);
        bool dup;
        lock (gate)
        {
            dup = seen.Contains(item.Id);
            if (!dup) { seen.Add(item.Id); if (seen.Count > 100) seen.RemoveRange(0, seen.Count - 100); }
        }
        if (dup)
        {
            if (content && ReceiptDue(item.Id)) ui.Post(_ => SendControl("ack", Encoding.UTF8.GetBytes(item.Id), item.Dev), null);
            return;
        }
        ui.Post(_ =>
        {
            switch (item.Kind)
            {
                case "ack":
                    var id = Encoding.UTF8.GetString(item.Data);
                    if (Receipts.Ack(id, item.Dev)) Log.Write($"clip: {DeviceNames.Pretty(item.Dev)} confirmed {(outbox.TryGetValue(id, out var o) ? o.Kind : "a file")}");
                    break;
                case "diag-req":
                    Log.Write($"diag: {item.Dev} asked for diagnostics");
                    SendControl("diag", Encoding.UTF8.GetBytes(Diagnostics()), item.Dev);
                    break;
                case "link":
                    OpenLink(item);
                    break;
                case "text":
                case "image":
                    Apply(item);
                    break;
                default:
                    OnControl?.Invoke(item);
                    break;
            }
        }, null);
    }

    private void Apply(ClipItem item)
    {
        try
        {
            if (item.Kind == "text") Clipboard.SetText(Encoding.UTF8.GetString(item.Data));
            else if (item.Kind == "image" && cfg.ClipImages)
            {
                using var ms = new MemoryStream(item.Data);
                using var img = Image.FromStream(ms);
                Clipboard.SetImage(img);
            }
            else return;
        }
        catch (Exception e) when (e is ExternalException or ArgumentException) { Log.Write($"clip: could not set the clipboard ({e.Message})"); return; }
        ownWrite = Native.GetClipboardSequenceNumber();
        Log.Write($"clip: received {item.Kind} from {item.Dev} over Wi-Fi");
        SendControl("ack", Encoding.UTF8.GetBytes(item.Id), item.Dev);
        OnEvent?.Invoke($"Received {(item.Kind == "text" ? "text" : "an image")} from {DeviceNames.Pretty(item.Dev)}");
        if (cfg.ClipNotify)
        {
            var preview = item.Kind == "text" ? new string(Encoding.UTF8.GetString(item.Data).Take(80).ToArray()) : "An image";
            OnNotify?.Invoke($"Clipboard from {DeviceNames.Pretty(item.Dev)}", preview);
        }
    }

    private void OpenLink(ClipItem item)
    {
        var s = Encoding.UTF8.GetString(item.Data);
        if (!Uri.TryCreate(s, UriKind.Absolute, out var u) || (u.Scheme != "http" && u.Scheme != "https")) return;
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(u.AbsoluteUri) { UseShellExecute = true }); }
        catch (Exception e) { Log.Write($"link: could not open ({e.Message})"); return; }
        Log.Write($"link: opened a link from {item.Dev}");
        SendControl("ack", Encoding.UTF8.GetBytes(item.Id), item.Dev);
    }

    public string DownloadDir => cfg.DownloadFolder.Length > 0 ? cfg.DownloadFolder : Native.DownloadsFolder();

    private string? AcceptFile(ClipItem item)
    {
        if (!cfg.ClipSync || !cfg.ClipReceive || Paused || item.Dev == cfg.DeviceName || (item.To.Length > 0 && item.To != cfg.DeviceName)
            || !ClockGuard.Shared.Fresh(item.Dev, item.Ts, Proto.NowMs(), ClipCodec.MaxAgeMs)) return null;
        try { Directory.CreateDirectory(DownloadDir); } catch { return null; }
        return Path.Combine(DownloadDir, $".earhop-{item.Id}.part");
    }

    private void FileDone(ClipItem item, string? part)
    {
        string name = "file";
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(item.Data);
            if (doc.RootElement.TryGetProperty("name", out var n) && n.GetString() is string s) name = s;
        }
        catch (System.Text.Json.JsonException) { }
        name = FileCrypt.SafeName(name);
        if (part == null) { Log.Write($"file: {name} from {item.Dev} broke off"); return; }
        var dest = FileCrypt.FreePath(Path.GetDirectoryName(part)!, name);
        try { File.Move(part, dest); File.SetAttributes(dest, FileAttributes.Normal); }
        catch (IOException e) { Log.Write($"file: could not save {name}: {e.Message}"); return; }
        Log.Write($"file: received {Path.GetFileName(dest)} from {item.Dev}");
        SendControl("ack", Encoding.UTF8.GetBytes(item.Id), item.Dev);
        OnFileReceived?.Invoke(dest, item.Dev);
    }

    public void Dispose() => tcp.Dispose();
}

static class Native
{
    [DllImport("user32.dll")] public static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll", SetLastError = true)] public static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] public static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    public const int WmClipboardUpdate = 0x031D;

    [StructLayout(LayoutKind.Sequential)] private struct LastInputInfo { public uint Size; public uint Time; }
    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LastInputInfo info);

    /// <summary>Seconds since the last key press or mouse move on this PC (Follow my attention).</summary>
    public static double IdleSeconds()
    {
        var i = new LastInputInfo { Size = (uint)Marshal.SizeOf<LastInputInfo>() };
        if (!GetLastInputInfo(ref i)) return double.MaxValue;
        return unchecked((uint)Environment.TickCount - i.Time) / 1000.0;
    }

    [DllImport("shell32.dll")]
    private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out IntPtr path);

    public static string DownloadsFolder()
    {
        if (SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out var p) == 0)
        {
            var s = Marshal.PtrToStringUni(p);
            Marshal.FreeCoTaskMem(p);
            if (!string.IsNullOrEmpty(s)) return s;
        }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }
}
