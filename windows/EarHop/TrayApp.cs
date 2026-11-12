using System.Diagnostics;
using System.Reflection;
using System.Text;

namespace EarHop.Win;

/// <summary>The tray icon and the main loop: four times a second it reads the local facts (playing, call, earbuds
/// connected here), runs the controller, and sends this PC's status at least once a second and at once on a change.</summary>
sealed class TrayApp : ApplicationContext
{
    private Config cfg;
    private readonly NotifyIcon icon;
    private readonly ContextMenuStrip menu = new();
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 250 };
    private readonly AudioMonitor audio = new();
    private readonly BtAudio bt = new();
    private UdpLink link;
    private BleWatch ble;
    private ClipSync clip;
    private readonly Controller controller;
    private readonly ClipboardWindow clipWindow;
    private int seq;
    private DateTime lastSent = DateTime.MinValue;
    private string lastKey = "";
    private bool owner;
    private DateTime ownerCheckedAt = DateTime.MinValue;
    private int ticks;
    private string? lastFileShown;
    private readonly List<string> events = new();
    public static readonly string Version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.4.0";

    public TrayApp(Config cfg)
    {
        this.cfg = cfg;
        Log.Write($"EarHop for Windows {Version} start dev={cfg.DeviceName} earbuds={cfg.Earbuds}");
        NameBook.Shared.Setup(cfg.Secret, cfg.KnownDevices);
        NameBook.Shared.Changed += all => { this.cfg.KnownDevices = all.Where(n => n is not ("mac" or "phone" or "tab")).ToList(); this.cfg.Save(); };
        bt.NameFilter = cfg.EarbudsName;
        icon = new NotifyIcon { Icon = AppIcon(), Text = "EarHop", Visible = true, ContextMenuStrip = menu };
        icon.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowMenu(); };
        icon.BalloonTipClicked += (_, _) => { if (lastFileShown is string f && File.Exists(f)) Process.Start("explorer.exe", $"/select,\"{f}\""); };
        // An empty menu is cancelled before it opens, so it is filled here and the cancel is undone.
        menu.Opening += (_, e) => { BuildMenu(); e.Cancel = false; };

        link = new UdpLink(cfg.Secret, cfg.DeviceName);
        ble = new BleWatch(cfg.Secret, cfg.DeviceName);
        controller = new Controller(cfg, bt);
        clip = new ClipSync(cfg, link);
        Wire();
        clipWindow = new ClipboardWindow(() => clip.ClipboardChanged());
        timer.Tick += (_, _) => Tick();

        if (cfg.Secret.Length == 0)
        {
            Notify("EarHop needs the pairing secret", "Open Settings and paste the secret from the Mac or Android app.");
            ShowSettings();
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
        link.OnRejected += d => Post(() => Notify("Secret does not match", $"Packets from {DeviceNames.Pretty(d)} were refused: it uses a different secret."));
        ble.OnStatus += s => controller.Receive(s, "ble");
        controller.OnEvent += (kind, text) => { lock (events) { events.Add($"{DateTime.Now:HH:mm:ss} {kind}: {text}"); if (events.Count > 30) events.RemoveAt(0); } };
        clip.OnEvent += t => icon.Text = Trim("EarHop: " + t);
        clip.OnNotify += Notify;
        clip.OnFileReceived += (path, from) =>
        {
            lastFileShown = path;
            Notify($"{Path.GetFileName(path)} from {DeviceNames.Pretty(from)}", "Saved in Downloads. Click to show it in File Explorer.");
        };
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
        PipeServer.Start(paths => Post(() => PickAndSend(paths)));
    }

    private readonly SynchronizationContext ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
    private void Post(Action a) => ui.Post(_ => a(), null);

    private static string Trim(string s) => s.Length > 63 ? s[..63] : s;

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
        bool attention = Native.IdleSeconds() < 30;
        if (cfg.Secret.Length == 0) return;
        controller.Tick(audio.Playing, EarReading.Unknown, owner, audio.InCall, attention);
        if (ticks % 2 == 0) clip.Tick();

        var st = new Status(cfg.DeviceName, Proto.NowMs(), 0, controller.Core.AdvertisedPlaying(audio.Playing), Ear.Unknown, owner,
                            audio.InCall, cfg.Earbuds, attention);
        string key = $"{st.Playing}{st.Owner}{st.Call}{st.Att}{st.Prof}";
        if (key != lastKey || (now - lastSent).TotalSeconds >= 1)
        {
            lastKey = key;
            lastSent = now;
            seq++;
            link.Send(st with { Seq = seq });
        }
        if (ticks % 4 == 0)
        {
            var phase = controller.Stage ?? PhaseText.Label(controller.Core.Phase);
            icon.Text = Trim($"EarHop: {phase}");
        }
    }

    // MARK: menu

    private void ShowMenu()
    {
        // NotifyIcon shows its menu on right click only; this opens it on a left click too.
        typeof(NotifyIcon).GetMethod("ShowContextMenu", BindingFlags.Instance | BindingFlags.NonPublic)?.Invoke(icon, null);
    }

    private void BuildMenu()
    {
        menu.Items.Clear();
        string phase = controller.Stage ?? PhaseText.Label(controller.Core.Phase);
        menu.Items.Add(new ToolStripMenuItem($"{phase}{(owner ? $", {cfg.EarbudsShort} here" : "")}") { Enabled = false, Font = new Font(menu.Font, FontStyle.Bold) });
        if (controller.HoldReason is string h && !owner) menu.Items.Add(new ToolStripMenuItem("Not switching: " + h) { Enabled = false });
        if (controller.LastFailure is string f) menu.Items.Add(new ToolStripMenuItem("Last switch: " + f) { Enabled = false });
        var devs = new ToolStripMenuItem("Devices");
        var peers = controller.PeerInfos();
        if (peers.Count == 0) devs.DropDownItems.Add(new ToolStripMenuItem("No other device heard yet") { Enabled = false });
        foreach (var p in peers)
        {
            var what = p.Call ? "in a call" : p.Playing ? "playing" : "idle";
            devs.DropDownItems.Add(new ToolStripMenuItem($"{DeviceNames.Pretty(p.Name)}: {what}{(p.Owner ? ", has the earbuds" : "")} ({(p.Via == "ble" ? "Bluetooth" : "Wi-Fi")}, {p.Age} s)") { Enabled = false });
        }
        menu.Items.Add(devs);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add($"Take {cfg.EarbudsShort}", null, (_, _) => controller.ClaimNow());
        menu.Items.Add($"Release {cfg.EarbudsShort}", null, (_, _) => controller.ReleaseNow());
        menu.Items.Add(new ToolStripMenuItem("Automatic switching", null, (_, _) => { cfg.AutoSwitch = !cfg.AutoSwitch; cfg.Save(); controller.Cfg = cfg; }) { Checked = cfg.AutoSwitch });
        if (controller.Core.SafeMode) menu.Items.Add("Leave safe mode", null, (_, _) => controller.ClearSafeMode());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Send clipboard now", null, (_, _) => clip.SendNow());
        menu.Items.Add("Send files...", null, (_, _) => ChooseFiles());
        var recent = new ToolStripMenuItem("Recent events");
        lock (events) foreach (var e in events.AsEnumerable().Reverse().Take(12)) recent.DropDownItems.Add(new ToolStripMenuItem(e) { Enabled = false });
        if (recent.DropDownItems.Count == 0) recent.DropDownItems.Add(new ToolStripMenuItem("Nothing yet") { Enabled = false });
        menu.Items.Add(recent);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Settings...", null, (_, _) => ShowSettings());
        menu.Items.Add("Copy diagnostics", null, (_, _) => { Clipboard.SetText(Diagnostics()); Notify("Diagnostics copied", "Paste them where you need them."); });
        menu.Items.Add("Open log folder", null, (_, _) => Process.Start("explorer.exe", $"\"{Config.Dir}\""));
        menu.Items.Add("Quit EarHop", null, (_, _) => ExitThread());
    }

    private void ChooseFiles()
    {
        using var d = new OpenFileDialog { Multiselect = true, Title = "Send files with EarHop" };
        if (d.ShowDialog() == DialogResult.OK) PickAndSend(d.FileNames);
    }

    private void PickAndSend(IList<string> paths)
    {
        var files = paths.Where(File.Exists).ToList();
        Log.Write($"share: {files.Count} file(s) to send");
        if (files.Count == 0) { Notify("Not sent", "Folders cannot be sent, only files."); return; }
        var devs = clip.Devices();
        if (devs.Count == 0) { Notify("Not sent", "No other device is on Wi-Fi right now."); return; }
        string title = files.Count == 1 ? Path.GetFileName(files[0]) : $"{files.Count} files";
        SendPicker.Open(title, devs, dev =>
        {
            Log.Write($"share: sending to {dev ?? "all devices"}");
            clip.SendFiles(files, dev == null ? null : new[] { dev });
        });
    }

    private SettingsForm? settings;
    private void ShowSettings()
    {
        if (settings is { IsDisposed: false }) { settings.Activate(); return; }
        settings = new SettingsForm(cfg, Apply);
        settings.Show();
    }

    /// <summary>New settings from the Settings window. A new name or secret restarts the links.</summary>
    private void Apply(Config c)
    {
        bool relink = c.Secret != cfg.Secret || c.DeviceName != cfg.DeviceName;
        cfg = c;
        cfg.Save();
        bt.NameFilter = cfg.EarbudsName;
        controller.Cfg = cfg;
        clip.Cfg = cfg;
        Startup.Apply(cfg.StartAtLogin);
        NameBook.Shared.Setup(cfg.Secret, cfg.KnownDevices);
        if (relink && started)
        {
            Log.Write("settings: name or secret changed, restarting EarHop");
            Program.RestartRequested = true;
            ExitThread();
            return;
        }
        if (cfg.Secret.Length > 0) StartLinks();
    }

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
        sb.AppendLine($"hold {controller.HoldReason ?? "-"} last failure {controller.LastFailure ?? "-"} earbuds known to Windows {bt.Known()}");
        sb.AppendLine($"wifi heard {(link.LastHeard == DateTime.MinValue ? "never" : $"{(DateTime.UtcNow - link.LastHeard).TotalSeconds:0} s ago")} ble heard {(ble.LastHeard == DateTime.MinValue ? "never" : $"{(DateTime.UtcNow - ble.LastHeard).TotalSeconds:0} s ago")} clip port {(clip.Listening ? "open" : "closed")}");
        foreach (var p in controller.PeerInfos())
            sb.AppendLine($"peer {p.Name} playing={p.Playing} owner={p.Owner} call={p.Call} ear={EarText.Raw(p.Ear)} via={p.Via} age={p.Age}s offset={ClockGuard.Shared.Offset(p.Name)}ms");
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
        icon.Visible = false;
        clipWindow.Dispose();
        link.Dispose();
        ble.Dispose();
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
