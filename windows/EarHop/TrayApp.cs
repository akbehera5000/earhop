using System.Diagnostics;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Windows.Forms.Integration;
using EarHop.Win.UI;

namespace EarHop.Win;

/// <summary>The tray icon and the main loop: four times a second it reads the local facts (playing, call, earbuds
/// connected here), runs the controller, and sends this PC's status at least once a second and at once on a change.
/// A left click opens the Fluent flyout, a right click a short menu; Settings is a Fluent window.</summary>
sealed class TrayApp : ApplicationContext
{
    private Config cfg;
    private readonly NotifyIcon icon;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 250 };
    private readonly AudioMonitor audio = new();
    private readonly BtAudio bt = new();
    private readonly BudsLink buds = new();
    private readonly NetCheck net = new();
    public NetCheck Net => net;
    private string lastBattKey = "";
    private DateTime autoCheckedAt = DateTime.MinValue;
    private EarReading reading = EarReading.Unknown;
    private readonly UdpLink link;
    private readonly BleWatch ble;
    private readonly BleAdvert bleOut;
    private readonly ClipSync clip;
    private readonly Controller controller;
    private readonly ClipboardWindow clipWindow;
    private int seq;
    private DateTime lastSent = DateTime.MinValue;
    private string lastKey = "";
    private bool owner;
    private DateTime ownerCheckedAt = DateTime.MinValue;
    private bool known;
    private DateTime knownCheckedAt = DateTime.MinValue;
    private int ticks;
    private string? lastFileShown;
    private readonly List<(string Time, string Kind, string Text)> events = new();
    private readonly Dictionary<string, string> kinds = new();
    private DateTime lastHello = DateTime.MinValue;
    private HashSet<string> helloPeers = new();
    private Flyout? flyout;
    private DateTime flyoutClosedAt = DateTime.MinValue;
    private SettingsWindow? settings;
    public static readonly string Version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.4.0";

    // What the windows read.
    public Config Cfg => cfg;
    public Controller Controller => controller;
    public ClipSync Clip => clip;
    public bool Owner => owner;
    public bool Playing => audio.Playing;
    public bool EarbudsKnown => known;
    public string? LastClipEvent { get; private set; }
    public DateTime LastClipEventAt { get; private set; }
    private string? peerBattery;
    public BudsLink Buds => buds;
    public EarReading Reading => reading;
    /// <summary>Battery text: from this PC's own Buds channel when it is open, else from the device that holds them.</summary>
    public string? LastBattery => cfg.IsBuds && buds.Open && buds.State.BattL >= 0 ? BattText(buds.State)
                                  : !cfg.IsBuds && ble.AirPodsBattery is string ab ? ab : peerBattery;
    public static string BattText(BudsProtocol.State s)
    {
        string P(string l, int v, bool ch) => v >= 0 ? $"{l} {v}%{(ch ? " charging" : "")}" : "";
        return string.Join(", ", new[] { P("Left", s.BattL, s.ChargeL), P("Right", s.BattR, s.ChargeR), P("Case", s.BattCase, s.ChargeCase) }.Where(x => x.Length > 0));
    }

    public TrayApp(Config cfg)
    {
        this.cfg = cfg;
        Log.Write($"EarHop for Windows {Version} start dev={cfg.DeviceName} earbuds={cfg.Earbuds}");
        Fluent.Start(cfg.IsBuds);
        NameBook.Shared.Setup(cfg.Secret, cfg.KnownDevices);
        NameBook.Shared.Changed += all => { this.cfg.KnownDevices = all.Where(n => n is not ("mac" or "phone" or "tab")).ToList(); this.cfg.Save(); };
        bt.NameFilter = cfg.EarbudsName;
        buds.NameFilter = cfg.BudsName;
        icon = new NotifyIcon { Icon = AppIcon(), Text = "EarHop", Visible = true };
        icon.MouseUp += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) ToggleFlyout();
            else if (e.Button == MouseButtons.Right) ShowMenu();
        };
        icon.BalloonTipClicked += (_, _) => { if (lastFileShown is string f && File.Exists(f)) Process.Start("explorer.exe", $"/select,\"{f}\""); };

        link = new UdpLink(cfg.Secret, cfg.DeviceName);
        ble = new BleWatch(cfg.Secret, cfg.DeviceName);
        bleOut = new BleAdvert(cfg.Secret);
        controller = new Controller(cfg, bt);
        controller.Nudge = () => this.cfg.IsBuds ? buds.OpenChannel("claim") : Task.FromResult(false);
        controller.BeforeRelease = () => buds.Suspend(5);
        buds.OnState += _ => { ShareBattery(); flyout?.Refresh(); };
        clip = new ClipSync(cfg, link);
        clip.BlePeers = () => ble.Peers();
        Wire();
        clipWindow = new ClipboardWindow(() => clip.ClipboardChanged());
        timer.Tick += (_, _) => Tick();

        if (cfg.Secret.Length == 0)
        {
            Notify("EarHop needs the pairing secret", "Paste the pairing link from the Mac or Android app on the Pairing page.");
            Post(() => ShowSettings("Pairing"));
        }
        else StartLinks();
        timer.Start();
        SendTo.Ensure();
        Startup.Apply(cfg.StartAtLogin);
    }

    private void Wire()
    {
        link.OnStatus += s => controller.Receive(s, "wifi");
        link.OnDuplicate += d => Post(() => Notify("Two devices share a name", $"Two devices are both named \"{d}\". Give each device its own name."));
        link.OnRejected += d => Post(() => Notify("Secret does not match", $"Packets from {DeviceNames.Pretty(d)} were refused: it uses a different secret. Pair it again."));
        ble.OnStatus += s => controller.Receive(s, "ble");
        controller.OnEvent += (kind, text) =>
        {
            lock (events) { events.Add(($"{DateTime.Now:t}", kind, text)); if (events.Count > 60) events.RemoveAt(0); }
            if (kind is "claim" or "release" && !text.StartsWith("would")) Notify(kind == "claim" ? $"{cfg.EarbudsShort} here" : $"{cfg.EarbudsShort} released", Capital(text));
        };
        clip.OnEvent += t => { LastClipEvent = t; LastClipEventAt = DateTime.UtcNow; icon.Text = Trim("EarHop: " + t); };
        clip.OnNotify += Notify;
        clip.OnFileReceived += (path, from) =>
        {
            lastFileShown = path;
            Notify($"{Path.GetFileName(path)} from {DeviceNames.Pretty(from)}", "Saved in your Received files folder. Click to show it in File Explorer.");
        };
        clip.OnControl += Control;
        clip.Diagnostics = Diagnostics;
    }

    private bool started;
    private void StartLinks()
    {
        if (started) return;
        started = true;
        link.Start();
        ble.Start();
        clip.Start();
        PipeServer.Start(paths => Post(() => PickAndSend(paths, null)));
    }

    private readonly SynchronizationContext ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
    private void Post(Action a) => ui.Post(_ => a(), null);

    private static string Trim(string s) => s.Length > 63 ? s[..63] : s;
    private static string Capital(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];

    // MARK: control messages (hello, batt)

    private void Control(ClipItem item)
    {
        try
        {
            switch (item.Kind)
            {
                case "hello":
                    using (var d = JsonDocument.Parse(item.Data))
                        if (d.RootElement.TryGetProperty("type", out var t) && t.GetString() is string k && k.Length > 0)
                        {
                            if (!kinds.TryGetValue(item.Dev, out var old) || old != k) Log.Write($"hello: {item.Dev} is a {k}");
                            kinds[item.Dev] = k;
                        }
                    break;
                case "batt":
                    var p = Encoding.UTF8.GetString(item.Data).Split(',');
                    if (p.Length >= 6)
                    {
                        string Part(string label, string v, string ch) => int.TryParse(v, out var n) && n >= 0 ? $"{label} {n}%{(ch == "1" ? " charging" : "")}" : "";
                        var parts = new[] { Part("Left", p[0], p[3]), Part("Right", p[1], p[4]), Part("Case", p[2], p[5]) }.Where(x => x.Length > 0);
                        peerBattery = $"{string.Join(", ", parts)} (from {DeviceNames.Pretty(item.Dev)})";
                    }
                    break;
            }
        }
        catch (Exception e) { Log.Write($"control {item.Kind}: {e.Message}"); }
    }

    public string KindOf(string dev) => kinds.TryGetValue(dev, out var k) ? k : dev;

    /// <summary>Tells the other devices this is a Windows PC (for its icon there): at start, when a device joins and
    /// every 10 minutes (Devices.swift).</summary>
    private void SendHello(string? to)
    {
        var data = JsonSerializer.SerializeToUtf8Bytes(new SortedDictionary<string, string> { ["app"] = "windows", ["type"] = "windows", ["version"] = Version });
        clip.SendControl("hello", data, to);
        if (to == null) lastHello = DateTime.UtcNow;
    }

    // MARK: loop

    private void Tick()
    {
        ticks++;
        audio.Tick();
        var now = DateTime.UtcNow;
        if ((now - ownerCheckedAt).TotalSeconds >= 0.5)
        {
            ownerCheckedAt = now;
            bool o = bt.Connected();
            if (o != owner) Log.Write($"owner: {owner} -> {o}");
            owner = o;
        }
        if ((now - knownCheckedAt).TotalSeconds >= 5)
        {
            knownCheckedAt = now;
            try { known = bt.Known(); } catch { known = false; }
        }
        if (ticks % 4 == 0) { buds.Update(owner, cfg.IsBuds); net.Tick(); }
        if ((now - autoCheckedAt).TotalSeconds >= 2) { autoCheckedAt = now; AutoEarbuds(); }
        bool attention = Native.IdleSeconds() < 30;
        if (cfg.Secret.Length == 0) return;
        reading = cfg.IsBuds ? buds.Reading : ble.AirPodsReading;
        controller.Tick(audio.Playing, reading, owner, audio.InCall, attention);
        if (ticks % 2 == 0) clip.Tick();

        var st = new Status(cfg.DeviceName, Proto.NowMs(), 0, controller.Core.AdvertisedPlaying(audio.Playing), reading.Trusted ? reading.Ear : Ear.Unknown, owner,
                            audio.InCall, cfg.Earbuds, attention);
        string key = $"{st.Playing}{st.Owner}{st.Call}{st.Att}{st.Prof}";
        if (key != lastKey || (now - lastSent).TotalSeconds >= 1)
        {
            lastKey = key;
            lastSent = now;
            seq++;
            link.Send(st with { Seq = seq });
        }
        if (started) bleOut.Update(st with { Seq = seq });
        if (ticks % 4 == 0)
        {
            var phase = controller.Stage ?? PhaseText.Label(controller.Core.Phase);
            icon.Text = Trim($"EarHop: {phase}");
            var reachable = clip.Devices().ToHashSet();
            foreach (var d in reachable.Except(helloPeers)) SendHello(d);
            helloPeers = reachable;
            if ((now - lastHello).TotalMinutes >= 10 && reachable.Count > 0) SendHello(null);
        }
    }

    /// <summary>Automatic earbuds (main.swift detectEarbuds): the pair connected to this PC, else the pair the device
    /// that holds the earbuds reports.</summary>
    private void AutoEarbuds()
    {
        if (cfg.EarbudsMode != "auto") return;
        string? want = null;
        try
        {
            if (new BtAudio { NameFilter = cfg.BudsName }.Connected()) want = "buds";
            else if (new BtAudio { NameFilter = cfg.AirPodsName }.Connected()) want = "airpods";
        }
        catch { }
        want ??= controller.OwnerProfile();
        if (want != null && want != cfg.Earbuds)
        {
            Log.Write($"earbuds: automatic, now {want}");
            Update(c => c.Earbuds = want);
            Fluent.Apply(cfg.IsBuds);
            flyout?.Refresh();
        }
    }

    /// <summary>Sends the Buds' battery to the other devices while this PC holds them, like the Mac ("batt").</summary>
    private void ShareBattery()
    {
        var s = buds.State;
        if (!owner || s.BattL < 0 && s.BattR < 0) return;
        var key = $"{s.BattL},{s.BattR},{s.BattCase},{(s.ChargeL ? 1 : 0)},{(s.ChargeR ? 1 : 0)},{(s.ChargeCase ? 1 : 0)}";
        if (key == lastBattKey) return;
        lastBattKey = key;
        clip.SendControl("batt", Encoding.UTF8.GetBytes(key), null);
    }

    public void SetEarbudsMode(string mode)
    {
        Update(c => c.EarbudsMode = mode);
        if (mode == "auto") AutoEarbuds();
    }

    // MARK: windows

    public void ShowFlyout() { if (flyout == null) ToggleFlyout(); }

    private void ToggleFlyout()
    {
        if (flyout != null) { flyout.Close(); return; }
        // The click on the icon took the focus from the open flyout and closed it already: that click means close.
        if ((DateTime.UtcNow - flyoutClosedAt).TotalMilliseconds < 350) return;
        try
        {
            // A local reference: the flyout can lose the focus and close while it opens, which clears the field
            // (that was the NullReferenceException in ToggleFlyout).
            var f = new Flyout(this);
            flyout = f;
            ElementHost.EnableModelessKeyboardInterop(f);
            f.Closed += (_, _) => { if (flyout == f) flyout = null; flyoutClosedAt = DateTime.UtcNow; };
            f.SizeChanged += (_, _) => f.PlaceNearTray();
            f.Left = -10000;
            f.Show();
            if (!f.IsVisible) return;
            f.PlaceNearTray();
            Native.SetForegroundWindow(new System.Windows.Interop.WindowInteropHelper(f).Handle);
            f.Activate();
        }
        catch (Exception e) { Log.Write($"flyout: {e}"); flyout = null; }
    }

    private System.Windows.Window? menuHost;
    private void ShowMenu()
    {
        // A WPF menu needs a focused window to close when you click elsewhere: an invisible one anchors it.
        menuHost ??= new System.Windows.Window { WindowStyle = System.Windows.WindowStyle.None, ShowInTaskbar = false, Width = 0, Height = 0, AllowsTransparency = true, Background = System.Windows.Media.Brushes.Transparent, Topmost = true };
        var m = new System.Windows.Controls.ContextMenu { FontFamily = Fluent.Font, Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint };
        void Item(string text, Action a, bool enabled = true)
        {
            var i = new System.Windows.Controls.MenuItem { Header = text, IsEnabled = enabled };
            i.Click += (_, _) => a();
            m.Items.Add(i);
        }
        string phase = controller.Stage ?? PhaseText.Label(controller.Core.Phase);
        Item($"{phase}{(owner ? $", {cfg.EarbudsShort} here" : "")}", () => { }, false);
        m.Items.Add(new System.Windows.Controls.Separator { Margin = new System.Windows.Thickness(4, 2, 4, 2) });
        Item($"Take {cfg.EarbudsShort}", () => controller.ClaimNow(), !owner);
        Item($"Release {cfg.EarbudsShort}", () => controller.ReleaseNow(), owner);
        Item(cfg.AutoSwitch ? "Turn automatic switching off" : "Turn automatic switching on", () => Update(c => c.AutoSwitch = !c.AutoSwitch));
        Item("Send clipboard now", () => clip.SendNow());
        Item("Send files...", () => ChooseFiles());
        m.Items.Add(new System.Windows.Controls.Separator { Margin = new System.Windows.Thickness(4, 2, 4, 2) });
        Item("Settings", () => ShowSettings());
        Item("Quit EarHop", Quit);
        m.Closed += (_, _) => menuHost?.Hide();
        menuHost.Left = System.Windows.Forms.Cursor.Position.X;
        menuHost.Top = System.Windows.Forms.Cursor.Position.Y;
        menuHost.Show();
        menuHost.Activate();
        m.PlacementTarget = menuHost;
        m.IsOpen = true;
    }

    public void ShowSettings(string page = "General")
    {
        if (settings != null)
        {
            settings.Select(page);
            if (settings.WindowState == System.Windows.WindowState.Minimized) settings.WindowState = System.Windows.WindowState.Normal;
            settings.Activate();
            return;
        }
        settings = new SettingsWindow(this, page);
        ElementHost.EnableModelessKeyboardInterop(settings);
        settings.Closed += (_, _) => settings = null;
        settings.Show();
        settings.Activate();
    }

    public void ChooseFiles(string? to = null)
    {
        using var d = new OpenFileDialog { Multiselect = true, Title = "Send files with EarHop" };
        if (d.ShowDialog() == DialogResult.OK) PickAndSend(d.FileNames, to);
    }

    private void PickAndSend(IList<string> paths, string? to)
    {
        var files = paths.Where(File.Exists).ToList();
        Log.Write($"share: {files.Count} file(s) to send");
        if (files.Count == 0) { Notify("Not sent", "Folders cannot be sent, only files."); return; }
        if (to != null) { clip.SendFiles(files, new[] { to }); return; }
        var devs = clip.WifiDevices();
        if (devs.Count == 0) { Notify("Not sent", "Files go over Wi-Fi, and no other device is on Wi-Fi right now."); return; }
        string title = files.Count == 1 ? Path.GetFileName(files[0]) : $"{files.Count} files";
        SendPicker.Open(title, devs, dev =>
        {
            Log.Write($"share: sending to {dev ?? "all devices"}");
            clip.SendFiles(files, dev == null ? null : new[] { dev });
        });
    }

    // MARK: settings

    /// <summary>Applies a change to the settings at once and saves them.</summary>
    public void Update(Action<Config> change)
    {
        var c = JsonSerializer.Deserialize<Config>(JsonSerializer.Serialize(cfg))!;
        change(c);
        Apply(c);
    }

    public void SetEarbuds(string kind)
    {
        if (cfg.Earbuds == kind && cfg.EarbudsMode == "manual") return;
        Update(c => { c.Earbuds = kind; c.EarbudsMode = "manual"; });
        Fluent.Apply(cfg.IsBuds);
        flyout?.Refresh();
    }

    public void PauseSwitching(TimeSpan d)
    {
        controller.PausedUntil = d == TimeSpan.Zero ? DateTime.MinValue : DateTime.UtcNow + d;
        Log.Write(d == TimeSpan.Zero ? "handoff: switching resumed" : $"handoff: switching paused for {d.TotalMinutes:0} min");
    }

    /// <summary>New name or secret: saved, then EarHop restarts so every link uses them (like the Mac).</summary>
    public void ApplyIdentity(string name, string secret) => Update(c => { c.DeviceName = name; c.Secret = secret; });

    private void Apply(Config c)
    {
        bool relink = c.Secret != cfg.Secret || c.DeviceName != cfg.DeviceName;
        cfg = c;
        cfg.Save();
        bt.NameFilter = cfg.EarbudsName;
        buds.NameFilter = cfg.BudsName;
        controller.Cfg = cfg;
        clip.Cfg = cfg;
        Startup.Apply(cfg.StartAtLogin);
        if (relink)
        {
            NameBook.Shared.Setup(cfg.Secret, cfg.KnownDevices);
            if (started)
            {
                Log.Write("settings: name or secret changed, restarting EarHop");
                Program.RestartRequested = true;
                Program.RestartPage = "Pairing";
                ExitThread();
                return;
            }
        }
        if (cfg.Secret.Length > 0) StartLinks();
    }

    public static string PairLink(string secret) => $"earhop://pair?secret={Uri.EscapeDataString(secret)}&port=47474";

    /// <summary>The secret from plain text or from an earhop://pair?secret=...&amp;port=... link.</summary>
    public static string ParseSecret(string s)
    {
        s = s.Trim();
        if (s.StartsWith("earhop://pair", StringComparison.OrdinalIgnoreCase) && Uri.TryCreate(s, UriKind.Absolute, out var u))
        {
            foreach (var part in u.Query.TrimStart('?').Split('&'))
            {
                var kv = part.Split('=', 2);
                if (kv.Length == 2 && kv[0] == "secret") return Uri.UnescapeDataString(kv[1]);
            }
        }
        return s;
    }

    // MARK: facts for the windows

    public string LinkSummary()
    {
        var (w, b) = HeardAges();
        bool wifi = w is < 15, le = b is < 90;
        return wifi && le ? "Wi-Fi and Bluetooth LE" : wifi ? "Wi-Fi" : le ? "Bluetooth LE" : "No device heard";
    }

    public string BleSending => bleOut.State;

    public (double? Wifi, double? Ble) HeardAges()
    {
        double? Age(DateTime t) => t == DateTime.MinValue ? null : (DateTime.UtcNow - t).TotalSeconds;
        return (Age(link.LastHeard), Age(ble.LastHeard));
    }

    public List<(string Time, string Kind, string Text)> RecentEvents()
    {
        lock (events) return events.AsEnumerable().Reverse().ToList();
    }

    public void CopyDiagnostics()
    {
        try { Clipboard.SetText(Diagnostics()); Notify("Diagnostics copied", "Paste them where you need them."); }
        catch (Exception e) { Log.Write($"diag copy: {e.Message}"); }
    }

    public void Quit() => ExitThread();

    private void Notify(string title, string body)
    {
        icon.BalloonTipTitle = title;
        icon.BalloonTipText = body.Length == 0 ? " " : body;
        icon.ShowBalloonTip(5000);
    }

    private string Diagnostics()
    {
        var sb = new StringBuilder();
        sb.AppendLine($"EarHop for Windows {Version} dev={cfg.DeviceName} earbuds={cfg.Earbuds} filter=\"{cfg.EarbudsName}\"");
        sb.AppendLine($"phase {PhaseText.Raw(controller.Core.Phase)} owner {owner} playing {audio.Playing} ({audio.Sources}) call {audio.InCall} auto {!controller.AutoOff} safe {controller.Core.SafeMode}");
        sb.AppendLine($"hold {controller.HoldReason ?? "-"} last failure {controller.LastFailure ?? "-"} earbuds known to Windows {known}");
        sb.AppendLine($"wifi heard {(link.LastHeard == DateTime.MinValue ? "never" : $"{(DateTime.UtcNow - link.LastHeard).TotalSeconds:0} s ago")} ble heard {(ble.LastHeard == DateTime.MinValue ? "never" : $"{(DateTime.UtcNow - ble.LastHeard).TotalSeconds:0} s ago")} clip port {(clip.Listening ? "open" : "closed")}");
        foreach (var p in controller.PeerInfos())
            sb.AppendLine($"peer {p.Name} ({KindOf(p.Name)}) playing={p.Playing} owner={p.Owner} call={p.Call} ear={EarText.Raw(p.Ear)} via={p.Via} age={p.Age}s offset={ClockGuard.Shared.Offset(p.Name)}ms");
        sb.AppendLine("--- log");
        var tail = Log.Tail(600);
        if (tail.Length > 50_000) tail = tail[^50_000..];
        sb.Append(tail);
        return sb.ToString();
    }

    public static Icon AppIcon()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("EarHop.Win.EarHop.ico");
        return s != null ? new Icon(s, SystemInformation.SmallIconSize) : SystemIcons.Application;
    }

    protected override void ExitThreadCore()
    {
        timer.Stop();
        try { flyout?.Close(); settings?.Close(); menuHost?.Close(); } catch { }
        icon.Visible = false;
        clipWindow.Dispose();
        link.Dispose();
        ble.Dispose();
        bleOut.Dispose();
        buds.Dispose();
        clip.Dispose();
        audio.Dispose();
        Log.Write("EarHop quit");
        base.ExitThreadCore();
    }
}

/// <summary>A hidden window that receives WM_CLIPBOARDUPDATE whenever the clipboard changes.</summary>
sealed class ClipboardWindow : NativeWindow, IDisposable
{
    private readonly Action changed;
    public ClipboardWindow(Action changed)
    {
        this.changed = changed;
        CreateHandle(new CreateParams { Parent = new IntPtr(-3) }); // HWND_MESSAGE: a message-only window
        if (!Native.AddClipboardFormatListener(Handle)) Log.Write("clip: could not watch the clipboard");
    }
    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Native.WmClipboardUpdate) { try { changed(); } catch (Exception e) { Log.Write($"clip: {e.Message}"); } }
        base.WndProc(ref m);
    }
    public void Dispose() { Native.RemoveClipboardFormatListener(Handle); DestroyHandle(); }
}
