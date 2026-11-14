using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using WpfButton = System.Windows.Controls.Button;
using WpfClipboard = System.Windows.Clipboard;
using WpfHA = System.Windows.HorizontalAlignment;
using WpfOrientation = System.Windows.Controls.Orientation;
using WpfRadio = System.Windows.Controls.RadioButton;
using WpfTextBox = System.Windows.Controls.TextBox;
using WpfVA = System.Windows.VerticalAlignment;
using WpfColor = System.Windows.Media.Color;
using WpfPoint = System.Windows.Point;

using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Image = System.Windows.Controls.Image;
using Rectangle = System.Windows.Shapes.Rectangle;
using Panel = System.Windows.Controls.Panel;
using Control = System.Windows.Controls.Control;
using Label = System.Windows.Controls.Label;

namespace EarHop.Win.UI;

/// <summary>
/// Settings in the Windows 11 Settings layout: Mica, a navigation pane with coloured icon tiles on the left and Fluent
/// setting cards on the right. Every switch applies at once; the name and the secret apply with Save (they restart the
/// links, like the Mac).
/// </summary>
sealed class SettingsWindow : Window
{
    private readonly TrayApp app;
    private readonly StackPanel nav = new() { Margin = new Thickness(0, 4, 0, 0) };
    private readonly StackPanel page = new() { MaxWidth = 1000 };
    private readonly ScrollViewer scroller;
    private readonly TextBlock pageTitle = Kit.Text("", 28, "Text1", FontWeights.SemiBold);
    private readonly System.Windows.Threading.DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private Action? live;
    private string current = "";
    private readonly List<TextBlock> labels = new();
    private ColumnDefinition paneColumn = null!;
    private FrameworkElement profile = null!, appHead = null!;
    private DockPanel content = null!;
    private bool compact;

    private static readonly (string Name, string Glyph, WpfColor Top, WpfColor Bottom)[] Pages =
    {
        ("General", "", WpfColor.FromRgb(0x8E, 0x8E, 0x96), WpfColor.FromRgb(0x63, 0x63, 0x6B)),
        ("Devices", "", WpfColor.FromRgb(0x4D, 0x9E, 0xFF), WpfColor.FromRgb(0x2E, 0x6E, 0xE0)),
        ("Earbuds", "", WpfColor.FromRgb(0xA8, 0x85, 0xFF), WpfColor.FromRgb(0x7A, 0x52, 0xE8)),
        ("Switching", "", WpfColor.FromRgb(0xFF, 0xA6, 0x4D), WpfColor.FromRgb(0xE8, 0x7A, 0x1F)),
        ("Clipboard", "", WpfColor.FromRgb(0x3C, 0xC8, 0xC0), WpfColor.FromRgb(0x1C, 0x9A, 0x94)),
        ("Pairing", "", WpfColor.FromRgb(0x4C, 0xD0, 0x7D), WpfColor.FromRgb(0x24, 0xA0, 0x55)),
        ("Connection", "", WpfColor.FromRgb(0x7C, 0x83, 0xFF), WpfColor.FromRgb(0x55, 0x5C, 0xE0)),
        ("History", "", WpfColor.FromRgb(0xFF, 0x6B, 0x8A), WpfColor.FromRgb(0xE0, 0x40, 0x62)),
        ("Log", "", WpfColor.FromRgb(0x8E, 0x8E, 0x96), WpfColor.FromRgb(0x63, 0x63, 0x6B)),
    };

    public SettingsWindow(TrayApp app, string first = "General")
    {
        this.app = app;
        Title = "EarHop Settings";
        Width = 1000;
        Height = 700;
        MinWidth = 460;
        MinHeight = 420;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = Fluent.Font;
        Icon = AppIconImage();
        SetResourceReference(BackgroundProperty, "Base");
        Fluent.Backdrop(this, acrylic: false);

        var grid = new Grid();
        paneColumn = new ColumnDefinition { Width = new GridLength(280) };
        grid.ColumnDefinitions.Add(paneColumn);
        grid.ColumnDefinitions.Add(new ColumnDefinition());

        // Navigation pane.
        var pane = new DockPanel { Margin = new Thickness(12, 8, 8, 12) };
        var head = new StackPanel { Orientation = WpfOrientation.Horizontal, Margin = new Thickness(6, 4, 0, 18) };
        appHead = head;
        var mark = new Image { Source = AppLogo(), Width = 20, Height = 20, Margin = new Thickness(0, 0, 10, 0) };
        RenderOptions.SetBitmapScalingMode(mark, BitmapScalingMode.HighQuality);
        head.Children.Add(mark);
        head.Children.Add(Kit.Text("EarHop", 12, "Text2"));
        DockPanel.SetDock(head, Dock.Top);
        pane.Children.Add(head);
        profile = ProfileCard();
        DockPanel.SetDock(profile, Dock.Top);
        pane.Children.Add(profile);
        foreach (var p in Pages)
        {
            var row = new StackPanel { Orientation = WpfOrientation.Horizontal };
            var glyph = new TextBlock { Text = p.Glyph, FontFamily = Fluent.Icons, FontSize = 16, Width = 20, Margin = new Thickness(0, 0, 14, 0), VerticalAlignment = WpfVA.Center, TextAlignment = TextAlignment.Center };
            glyph.SetResourceReference(TextBlock.ForegroundProperty, "Text1");
            row.Children.Add(glyph);
            var label = Kit.Text(p.Name, 14);
            labels.Add(label);
            row.Children.Add(label);
            var r = new WpfRadio { Style = Kit.S("Nav"), GroupName = "nav", Content = row, Tag = p.Name, ToolTip = p.Name };
            r.Checked += (_, _) => { Show((string)r.Tag); glyph.SetResourceReference(TextBlock.ForegroundProperty, "AccentText"); };
            r.Unchecked += (_, _) => glyph.SetResourceReference(TextBlock.ForegroundProperty, "Text1");
            nav.Children.Add(r);
        }
        pane.Children.Add(nav);
        grid.Children.Add(pane);

        // Content.
        content = new DockPanel { Margin = new Thickness(28, 12, 0, 0) };
        pageTitle.Margin = new Thickness(0, 0, 0, 8);
        DockPanel.SetDock(pageTitle, Dock.Top);
        content.Children.Add(pageTitle);
        scroller = new ScrollViewer { Content = page, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 28, 28), Focusable = false };
        SmoothScroll.Attach(scroller);
        content.Children.Add(scroller);
        Grid.SetColumn(content, 1);
        grid.Children.Add(content);
        Content = grid;

        SizeChanged += (_, _) => Adapt();
        timer.Tick += (_, _) => live?.Invoke();
        timer.Start();
        Closed += (_, _) => timer.Stop();
        Fluent.Changed += Rebuild;
        Closed += (_, _) => Fluent.Changed -= Rebuild;
        Select(first);
    }

    private void Rebuild() { var c = current; current = ""; Show(c); }

    /// <summary>Narrow windows: the navigation pane shows icons only (like NavigationView's compact mode) and the
    /// margins shrink, so the setting cards keep their room.</summary>
    private void Adapt()
    {
        bool c = ActualWidth < 880;
        if (c == compact) return;
        compact = c;
        paneColumn.Width = new GridLength(c ? 64 : 280);
        foreach (var l in labels) l.Visibility = c ? Visibility.Collapsed : Visibility.Visible;
        profile.Visibility = c ? Visibility.Collapsed : Visibility.Visible;
        ((StackPanel)appHead).Children[1].Visibility = c ? Visibility.Collapsed : Visibility.Visible;
        content.Margin = new Thickness(c ? 12 : 28, 12, 0, 0);
        scroller.Padding = new Thickness(0, 0, c ? 14 : 28, 28);
    }

    public void Select(string name)
    {
        foreach (WpfRadio r in nav.Children) if ((string)r.Tag == name) { r.IsChecked = true; return; }
    }

    private Border ProfileCard()
    {
        var g = new Grid { Margin = new Thickness(4, 0, 0, 16) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition());
        var badge = Kit.DeviceBadge("pc", "windows", true, true, 56);
        g.Children.Add(badge);
        var t = new StackPanel { VerticalAlignment = WpfVA.Center, Margin = new Thickness(14, 0, 0, 0) };
        t.Children.Add(Kit.Text(DeviceNames.Pretty(app.Cfg.DeviceName), 16, "Text1", FontWeights.SemiBold));
        t.Children.Add(Kit.Text($"EarHop {TrayApp.Version} for Windows", 12, "Text2"));
        Grid.SetColumn(t, 1);
        g.Children.Add(t);
        return new Border { Child = g };
    }

    private void Show(string name)
    {
        if (name == current) return;
        current = name;
        live = null;
        page.Children.Clear();
        pageTitle.Text = name;
        switch (name)
        {
            case "General": General(); break;
            case "Devices": Devices(); break;
            case "Earbuds": Earbuds(); break;
            case "Switching": Switching(); break;
            case "Clipboard": Clipboard(); break;
            case "Pairing": Pairing(); break;
            case "Connection": Connection(); break;
            case "History": History(); break;
            default: LogPage(); break;
        }
        scroller.ScrollToTop();
        page.Opacity = 0;
        var t = new TranslateTransform(0, 16);
        page.RenderTransform = t;
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        page.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(180)));
        t.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
    }

    private void Add(UIElement e) => page.Children.Add(e);
    private void Head(string text) => Add(Kit.Header(text));
    private void Note(string text) { var t = Kit.Text(text, 12, "Text2", wrap: true); t.Margin = new Thickness(2, 6, 0, 0); Add(t); }

    private static Border StatusText(string text, bool good)
    {
        var b = new Border { CornerRadius = new CornerRadius(11), BorderThickness = new Thickness(1.5), Padding = new Thickness(10, 2, 10, 3), Child = Kit.Text(text, 12, "Text1") };
        b.SetResourceReference(Border.BorderBrushProperty, good ? "StateOn" : "StateOff");
        return b;
    }

    // MARK: pages

    private void General()
    {
        var cfg = app.Cfg;
        Add(Kit.Row("", "Start EarHop when I sign in", "EarHop runs in the notification area and starts quietly.",
            Kit.Toggle(cfg.StartAtLogin, v => app.Update(c => c.StartAtLogin = v))));
        Add(Kit.Row("", "Appearance", "EarHop follows the light or dark mode set in Windows Settings, Personalization, Colors.",
            Kit.Button("Open Colors", () => Open("ms-settings:colors"))));
        var folder = Kit.Text(app.Clip.DownloadDir, 12, "Text2");
        folder.MaxWidth = 320;
        var folderRow = new StackPanel { Orientation = WpfOrientation.Horizontal };
        folderRow.Children.Add(Kit.Button("Change", () =>
        {
            using var d = new FolderBrowserDialog { Description = "Where EarHop saves received files", UseDescriptionForTitle = true, SelectedPath = app.Clip.DownloadDir };
            if (d.ShowDialog() == System.Windows.Forms.DialogResult.OK) { app.Update(c => c.DownloadFolder = d.SelectedPath); folder.Text = d.SelectedPath; }
        }));
        Add(Kit.Row("", "Received files", app.Clip.DownloadDir, folderRow));
        Head("About");
        Add(Kit.Row("", $"EarHop {TrayApp.Version}", "Your earbuds follow whichever device starts playing. Clipboard, files and links travel between your devices. Local network and Bluetooth only, no cloud and no account.", null));
        Add(Kit.Row("", "Send to menu", "Right click a file in File Explorer, Send to, EarHop, to send it to a device.", null));
    }

    private void Devices()
    {
        Head("This PC");
        var me = Kit.Row("", DeviceNames.Pretty(app.Cfg.DeviceName), "This PC. Rename it on the Pairing page.", Kit.Button("Rename", () => Select("Pairing")));
        ((Grid)me.Child).ColumnDefinitions[0].Width = new GridLength(52);
        ((Grid)me.Child).Children.Insert(0, Kit.DeviceBadge(app.Cfg.DeviceName, "windows", true, app.Owner));
        Add(me);
        Head("Your devices");
        var list = new StackPanel();
        Add(list);
        Note("Devices appear by themselves once they share the secret and are heard over Wi-Fi or Bluetooth LE. When two devices start playing at the same time, the one higher in the priority list on the Switching page gets the earbuds.");
        string key = "";
        live = () =>
        {
            var peers = app.Controller.PeerInfos();
            var names = NameBook.Shared.All.Where(n => n != app.Cfg.DeviceName).ToList();
            var k = string.Join("|", names.Select(n => { var p = peers.FirstOrDefault(x => x.Name == n); return $"{n}{p?.Playing}{p?.Owner}{p?.Call}{p?.Via}{p != null}{app.Cfg.ClipExclude.Contains(n)}"; }));
            if (k == key) return;
            key = k;
            list.Children.Clear();
            if (names.Count == 0)
                list.Children.Add(Kit.Row("", "Waiting for devices", "Pair a phone, tablet or Mac on the Pairing page. Both must be on the same Wi-Fi, or within Bluetooth range.", Kit.Button("Add a Device", () => Select("Pairing"), accent: true)));
            foreach (var n in names)
            {
                var p = peers.FirstOrDefault(x => x.Name == n);
                string state = p == null ? "Away" : (p.Call ? "In a call" : p.Owner ? "Has the earbuds" : p.Playing ? "Playing" : "Idle") + $", over {(p.Via == "ble" ? "Bluetooth LE" : "Wi-Fi")}";
                var controls = new StackPanel { Orientation = WpfOrientation.Horizontal };
                string dev = n;
                var send = Kit.Button("Send files", () => app.ChooseFiles(dev));
                send.Margin = new Thickness(0, 0, 16, 0);
                send.IsEnabled = app.Clip.WifiDevices().Contains(n);
                controls.Children.Add(send);
                controls.Children.Add(Kit.Text("Clipboard", 12, "Text2"));
                var clipOn = Kit.Toggle(!app.Cfg.ClipExclude.Contains(n), v => app.Update(c => { c.ClipExclude.Remove(dev); if (!v) c.ClipExclude.Add(dev); }));
                clipOn.Margin = new Thickness(8, 0, 0, 0);
                controls.Children.Add(clipOn);
                var row = Kit.Row("", DeviceNames.Pretty(n), state, controls);
                ((Grid)row.Child).ColumnDefinitions[0].Width = new GridLength(52);
                ((Grid)row.Child).Children.Insert(0, Kit.DeviceBadge(n, app.KindOf(n), p != null, p?.Owner == true));
                list.Children.Add(row);
            }
        };
        live();
    }

    private void Earbuds()
    {
        var cfg = app.Cfg;
        var art = new BudsArt { Height = 150 };
        var heroStack = new StackPanel();
        heroStack.Children.Add(art);
        var batt = Kit.Text("", 13, "Text1");
        batt.HorizontalAlignment = WpfHA.Center;
        batt.Margin = new Thickness(0, 8, 0, 0);
        heroStack.Children.Add(batt);
        var hero = Kit.Card(heroStack, new Thickness(16, 18, 16, 14));
        hero.Margin = new Thickness(0, 0, 0, 4);
        Add(hero);
        art.Show(cfg.IsBuds, app.Owner);
        int Idx() => app.Cfg.EarbudsMode == "auto" ? 0 : app.Cfg.IsBuds ? 1 : 2;
        var seg = Kit.Segmented(new[] { "Automatic", "Galaxy Buds2 Pro", "AirPods Pro" }, Idx(), i =>
        {
            if (i == 0) app.SetEarbudsMode("auto"); else app.SetEarbuds(i == 1 ? "buds" : "airpods");
            Rebuild();
        });
        Add(Kit.Row("\uE7F6", "Earbuds", "Automatic follows the pair connected to this PC, or the pair your other device reports.", seg));
        Head("This pair on this PC");
        var nameBox = Kit.Input(cfg.EarbudsName, 200);
        nameBox.LostFocus += (_, _) =>
        {
            var v = nameBox.Text.Trim();
            if (v.Length == 0) { nameBox.Text = app.Cfg.EarbudsName; return; }
            app.Update(c => { if (c.IsBuds) c.BudsName = v; else c.AirPodsName = v; });
        };
        Add(Kit.Row("\uE8AC", "Name in Windows", "Part of the name Windows shows for them in Settings, Bluetooth and devices.", nameBox));
        var known = new ContentControl();
        var conn = new ContentControl();
        var chan = new ContentControl();
        Add(Kit.Row("\uE702", "Paired with this PC", "Pair them once in Windows Bluetooth settings. EarHop then connects and releases them by itself.", known));
        Add(Kit.Row("\uE7F6", "Connected here", null, conn));
        if (cfg.IsBuds) Add(Kit.Row("\uE9E9", "Buds controls channel", "Battery, which bud is in an ear, noise control and equalizer, while the Buds are connected here.", chan));
        var bt = Kit.Button("Bluetooth settings", () => Open("ms-settings:bluetooth"));
        bt.HorizontalAlignment = WpfHA.Left;
        bt.Margin = new Thickness(0, 8, 0, 0);
        Add(bt);

        Border? noise = null;
        StackPanel? eq = null;
        System.Windows.Controls.CheckBox? touch = null;
        var controlsPanel = new StackPanel();
        if (cfg.IsBuds)
        {
            Head("Controls");
            noise = Kit.Segmented(new[] { "Off", "Noise cancelling", "Ambient" }, 0, i => app.Buds.SetNoise(BudsProtocol.NoiseModes[i]));
            controlsPanel.Children.Add(Kit.Row("\uE995", "Noise control", null, noise));
            eq = new StackPanel { Orientation = WpfOrientation.Horizontal };
            var eqSeg = Kit.Segmented(BudsProtocol.EqPresets.Select(e => e == "off" ? "Normal" : char.ToUpperInvariant(e[0]) + e[1..]).ToArray(), 0, i => app.Buds.SetEq(BudsProtocol.EqPresets[i]));
            eq.Children.Add(eqSeg);
            controlsPanel.Children.Add(Kit.Row("\uE9E9", "Equalizer", null, eq));
            touch = Kit.Toggle(true, v => app.Buds.SetTouch(v));
            controlsPanel.Children.Add(Kit.Row("\uE815", "Touch controls", "Tap and hold on the earbuds.", touch));
            Add(controlsPanel);
        }
        Head("When an earbud comes out");
        Add(Kit.Row("\uE769", "Pause when an earbud comes out", "Galaxy Buds: read from the Buds' controls channel while they are connected here. Play resumes when it goes back in within the time below.",
            Kit.Toggle(cfg.PauseWhenOneOut, v => app.Update(c => c.PauseWhenOneOut = v))));
        var resume = Kit.Segmented(new[] { "Never", "30 s", "2 min", "5 min" }, cfg.ResumeWithinSeconds switch { <= 0 => 0, <= 30 => 1, <= 120 => 2, _ => 3 },
            i => app.Update(c => c.ResumeWithinSeconds = i switch { 0 => 0, 1 => 30, 2 => 120, _ => 300 }));
        Add(Kit.Row("\uE768", "Resume when it goes back in within", null, resume));
        live = () =>
        {
            bool k = app.EarbudsKnown, c = app.Owner;
            known.Content = StatusText(k ? "Paired" : "Not paired", k);
            conn.Content = StatusText(c ? "Connected" : "Not connected", c);
            var bs = app.Buds.State;
            bool open = app.Buds.Open;
            chan.Content = StatusText(open ? (bs.Wearing == 2 ? "Open, both in" : bs.Wearing == 1 ? "Open, one in" : "Open, out of ears") : c ? "Opening" : "Closed", open);
            batt.Text = app.LastBattery is string b ? b : "Battery shows while the earbuds are connected to one of your devices.";
            controlsPanel.IsEnabled = open;
            controlsPanel.Opacity = open ? 1 : 0.45;
            if (open && noise != null)
            {
                int ni = Array.IndexOf(BudsProtocol.NoiseModes, bs.Noise);
                if (ni >= 0) Kit.SetSegment(noise, ni);
                int ei = Array.IndexOf(BudsProtocol.EqPresets, bs.Eq);
                if (ei >= 0 && eq?.Children[0] is Border es) Kit.SetSegment(es, ei);
                if (bs.TouchOn is bool t && touch != null && touch.IsChecked != t) { Kit.Quiet = true; touch.IsChecked = t; Kit.Quiet = false; }
            }
        };
        live();
    }

    private void Switching()
    {
        var cfg = app.Cfg;
        Add(Kit.Row("", "Switch the earbuds automatically", "The earbuds follow the device where audio starts. The device they leave pauses.",
            Kit.Toggle(cfg.AutoSwitch, v => app.Update(c => c.AutoSwitch = v))));
        var pause = new StackPanel { Orientation = WpfOrientation.Horizontal };
        var pausedText = Kit.Text("", 12, "Text2");
        pausedText.VerticalAlignment = WpfVA.Center;
        pausedText.Margin = new Thickness(0, 0, 12, 0);
        pause.Children.Add(pausedText);
        var b15 = Kit.Button("15 minutes", () => app.PauseSwitching(TimeSpan.FromMinutes(15)));
        var b60 = Kit.Button("1 hour", () => app.PauseSwitching(TimeSpan.FromHours(1)));
        var bResume = Kit.Button("Resume", () => app.PauseSwitching(TimeSpan.Zero), accent: true);
        b60.Margin = bResume.Margin = new Thickness(8, 0, 0, 0);
        pause.Children.Add(b15); pause.Children.Add(b60); pause.Children.Add(bResume);
        Add(Kit.Row("", "Pause switching", "Take and Release still work while it is paused.", pause));
        Add(Kit.Row("", "Live mode", "Off is a dry run: EarHop only writes what it would do to the log.",
            Kit.Toggle(cfg.LiveMode, v => app.Update(c => c.LiveMode = v))));
        Head("Who gets the earbuds");
        var prio = Kit.Input(string.Join(", ", cfg.Priority), 220);
        prio.LostFocus += (_, _) =>
        {
            var list = prio.Text.Split(',', ' ').Select(x => x.Trim().ToLowerInvariant()).Where(DeviceNames.Valid).Distinct().ToList();
            app.Update(c => c.Priority = list);
            prio.Text = string.Join(", ", list);
        };
        Add(Kit.Row("", "Priority", "When two devices start playing within 2 s of each other, the first one in this list wins.", prio));
        Add(Kit.Row("", "Calls and meetings come first", "A call on any device takes the earbuds at once. Teams, Zoom and WhatsApp calls count.", StatusText("Always on", true)));
        var safe = new ContentControl();
        Add(Kit.Row("", "Safe mode", "After 3 failed switches in a row EarHop stops switching by itself until you leave safe mode.", safe));
        live = () =>
        {
            var until = app.Controller.PausedUntil;
            bool paused = until > DateTime.UtcNow;
            pausedText.Text = paused ? $"Paused until {until.ToLocalTime():t}" : "";
            bResume.Visibility = paused ? Visibility.Visible : Visibility.Collapsed;
            if (app.Controller.Core.SafeMode)
            {
                if (safe.Content is not WpfButton) safe.Content = Kit.Button("Leave safe mode", () => app.Controller.ClearSafeMode(), accent: true);
            }
            else if (safe.Content is not Border) safe.Content = StatusText("Off", true);
        };
        live();
    }

    private void Clipboard()
    {
        var cfg = app.Cfg;
        Add(Kit.Row("", "Sync the clipboard with my other devices", "Encrypted with your shared secret. Text, images, links and files travel over Wi-Fi.",
            Kit.Toggle(cfg.ClipSync, v => app.Update(c => c.ClipSync = v))));
        int mi = app.Cfg.ClipTransport switch { "ble" => 1, "wifi" => 2, _ => 0 };
        Add(Kit.Row("\uE702", "Method", "Both: text over Bluetooth LE, Wi-Fi when Bluetooth cannot reach a device, and for images. Files always go over Wi-Fi.",
            Kit.Segmented(new[] { "Both", "Bluetooth", "Wi-Fi" }, mi, i => app.Update(c => c.ClipTransport = i switch { 1 => "ble", 2 => "wifi", _ => "both" }))));
        Head("Behaviour");
        Add(Kit.Row("", "Send copies automatically", "Everything you copy goes to the other devices. Off: only Send now.",
            Kit.Toggle(cfg.ClipAuto, v => app.Update(c => c.ClipAuto = v))));
        Add(Kit.Row("", "Receive clips and files", "Copies from the other devices land on this PC's clipboard; files go to the Received files folder.",
            Kit.Toggle(cfg.ClipReceive, v => app.Update(c => c.ClipReceive = v))));
        Add(Kit.Row("", "Include images", null, Kit.Toggle(cfg.ClipImages, v => app.Update(c => c.ClipImages = v))));
        Add(Kit.Row("", "Notify me when a clip arrives", null, Kit.Toggle(cfg.ClipNotify, v => app.Update(c => c.ClipNotify = v))));
        Add(Kit.Row("", "Skip password-like text", "Text that looks like a password stays on this PC.",
            Kit.Toggle(cfg.ClipSkipPasswords, v => app.Update(c => c.ClipSkipPasswords = v))));
        Head("Send");
        var send = new StackPanel { Orientation = WpfOrientation.Horizontal };
        send.Children.Add(Kit.Button("Send clipboard now", () => app.Clip.SendNow(), accent: true));
        var files = Kit.Button("Send files", () => app.ChooseFiles());
        files.Margin = new Thickness(8, 0, 0, 0);
        send.Children.Add(files);
        var reach = Kit.Text("", 12, "Text2");
        Add(Kit.Row("", "Send", null, send));
        Add(reach);
        reach.Margin = new Thickness(2, 6, 0, 0);
        live = () =>
        {
            var w = app.Clip.WifiDevices();
            var b = app.Clip.BleDevices();
            reach.Text = w.Count + b.Count == 0 ? "No other device is in reach right now, over Wi-Fi or Bluetooth LE."
                       : $"Bluetooth LE: {(b.Count == 0 ? "none" : string.Join(", ", b.Select(DeviceNames.Pretty)))}. Wi-Fi: {(w.Count == 0 ? "none" : string.Join(", ", w.Select(DeviceNames.Pretty)))}."
                         + (app.Clip.Ble.ServiceUp ? "" : " This PC's Bluetooth clipboard service is not up.") + (app.LastClipEvent is string e ? $" Last: {e}" : "");
        };
        live();
    }

    private void Pairing()
    {
        var cfg = app.Cfg;
        Head("This PC");
        var name = Kit.Input(cfg.DeviceName, 220);
        var nameError = Kit.Text("", 12, "Danger", wrap: true);
        Add(Kit.Row("", "Name", "Every device has its own name: 1 to 20 lower case letters, digits or -, for example pc or work-pc.", name));
        Head("Shared secret");
        bool shown = false;
        var secretText = Kit.Text(Mask(cfg.Secret), 14);
        secretText.FontFamily = new System.Windows.Media.FontFamily("Consolas");
        var showBtn = Kit.Button("Show", () => { });
        showBtn.Click += (_, _) => { shown = !shown; secretText.Text = shown ? app.Cfg.Secret : Mask(app.Cfg.Secret); ((TextBlock)showBtn.Content).Text = shown ? "Hide" : "Show"; };
        var secRow = new StackPanel { Orientation = WpfOrientation.Horizontal };
        secretText.VerticalAlignment = WpfVA.Center;
        secretText.Margin = new Thickness(0, 0, 12, 0);
        secRow.Children.Add(secretText);
        secRow.Children.Add(showBtn);
        Add(Kit.Row("", "Secret", "The same on every device. It signs and encrypts everything they send each other. Treat it like a password.", secRow));
        var acts = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
        WpfButton Act(string t, Action a, bool accent = false) { var b = Kit.Button(t, a, accent); b.Margin = new Thickness(0, 0, 8, 8); acts.Children.Add(b); return b; }
        var status = Kit.Text("", 12, "Text2", wrap: true);
        Act("Copy Secret", () => { if (app.Cfg.Secret.Length > 0) { WpfClipboard.SetText(app.Cfg.Secret); status.Text = "Secret copied."; } });
        Act("Copy Pairing Link", () => { if (app.Cfg.Secret.Length > 0) { WpfClipboard.SetText(TrayApp.PairLink(app.Cfg.Secret)); status.Text = "Pairing link copied. Paste it on the other device."; } });
        var pasted = Kit.Input("", 300);
        Add(acts);
        Head("Join your other devices");
        Add(Kit.Row("", "Secret or pairing link", "On the Mac: Settings, Pairing, Copy Pairing Link. On Android: Settings, Pairing. Paste it here.", pasted));
        var joinRow = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
        var pasteBtn = Kit.Button("Paste from clipboard", () => { try { pasted.Text = WpfClipboard.GetText().Trim(); } catch { } });
        pasteBtn.Margin = new Thickness(0, 0, 8, 8);
        joinRow.Children.Add(pasteBtn);
        var save = Kit.Button("Save and restart links", () =>
        {
            nameError.Text = "";
            var n = name.Text.Trim().ToLowerInvariant();
            if (!DeviceNames.Valid(n)) { nameError.Text = "Name: use 1 to 20 lower case letters, digits or -, starting with a letter or digit."; return; }
            if (NameBook.Shared.All.Contains(n) && n != app.Cfg.DeviceName) { nameError.Text = $"Name: \"{n}\" is already used by another device. Give this PC its own name."; return; }
            string sec = app.Cfg.Secret;
            if (pasted.Text.Trim().Length > 0)
            {
                sec = TrayApp.ParseSecret(pasted.Text);
                if (sec.Length < 8 || sec.Any(char.IsWhiteSpace)) { nameError.Text = "Secret: that does not look like an EarHop secret or earhop://pair link. Copy it again from the other device."; return; }
            }
            if (sec.Length == 0) { nameError.Text = "Secret: paste the secret or pairing link from one of your other devices first."; return; }
            if (n == app.Cfg.DeviceName && sec == app.Cfg.Secret) { status.Text = "Nothing changed."; return; }
            app.ApplyIdentity(n, sec);
        }, accent: true);
        save.Margin = new Thickness(0, 0, 8, 8);
        joinRow.Children.Add(save);
        Add(joinRow);
        Add(nameError);
        Add(status);
        Head("Add a phone or tablet");
        if (cfg.Secret.Length > 0)
        {
            var qr = new Image { Width = 180, Height = 180, Source = QrImage(TrayApp.PairLink(cfg.Secret)) };
            RenderOptions.SetBitmapScalingMode(qr, BitmapScalingMode.NearestNeighbor);
            var white = new Border { Background = Brushes.White, CornerRadius = new CornerRadius(8), Padding = new Thickness(10), Child = qr, HorizontalAlignment = WpfHA.Center };
            var qrStack = new StackPanel();
            qrStack.Children.Add(white);
            var hint = Kit.Text("In the EarHop app on the phone or tablet: Settings, Pairing, Scan QR code.", 12, "Text2", wrap: true);
            hint.HorizontalAlignment = WpfHA.Center;
            hint.Margin = new Thickness(0, 10, 0, 0);
            qrStack.Children.Add(hint);
            Add(Kit.Card(qrStack, new Thickness(16)));
        }
        else Note("Paste the secret from your Mac or Android device above. A QR code for the next device appears here once this PC has one.");
        Head("Start a new group");
        Add(Kit.Row("", "New secret", "Makes a new secret. Every other device must then be paired again with it.",
            Kit.Button("New Secret", () =>
            {
                if (System.Windows.MessageBox.Show(this, "Every other device must be paired again with the new secret. Make a new one?", "New secret", MessageBoxButton.OKCancel, MessageBoxImage.Warning) == MessageBoxResult.OK)
                    app.ApplyIdentity(app.Cfg.DeviceName, Config.NewSecret());
            })));
    }

    private void Connection()
    {
        var wifi = new ContentControl();
        var ble = new ContentControl();
        var port = new ContentControl();
        var bleOutState = new ContentControl();
        var wifiRow = Kit.Row("", "Wi-Fi", "Status on UDP port 47474, clips and files on TCP port 47475.", wifi);
        var bleRow = Kit.Row("", "Bluetooth LE", "Status from devices nearby, also without Wi-Fi.", ble);
        var netState = new ContentControl();
        var netRow = Kit.Row("\uE839", "Windows network and firewall", "Windows must let your other devices reach EarHop: the Wi-Fi set to Private and EarHop allowed in the firewall.", netState);
        Add(netRow);
        Add(wifiRow);
        Add(bleRow);
        Add(Kit.Row("", "This PC's status over Bluetooth LE", "So that devices set to Connection: Bluetooth hear this PC too.", bleOutState));
        Add(Kit.Row("", "Clipboard port", "Other devices send clips and files to this port.", port));
        Head("If nothing is heard");
        Note("1. All devices use the same secret (Pairing page).\n2. The Wi-Fi network is set to Private in Windows Settings, Network and internet.\n3. When Windows asked, EarHop was allowed on private networks. If you clicked Cancel, allow EarHop in Windows Security, Firewall and network protection, Allow an app through firewall.\n4. If this PC cannot send its status over Bluetooth LE, set Connection to Both on the Mac and Android devices so they also listen on Wi-Fi.");
        var fw = Kit.Button("Open firewall settings", () => Open("windowsdefender://network"));
        fw.HorizontalAlignment = WpfHA.Left;
        fw.Margin = new Thickness(0, 10, 0, 0);
        Add(fw);
        live = () =>
        {
            var (w, b) = app.HeardAges();
            wifi.Content = StatusText(w is double ws ? (ws < 5 ? "Hearing devices" : $"Last heard {Age(ws)}") : "Nothing heard", w is < 15);
            ble.Content = StatusText(b is double bs ? (bs < 5 ? "Hearing devices" : $"Last heard {Age(bs)}") : "Nothing heard", b is < 90);
            port.Content = StatusText(app.Clip.Listening ? "Open" : "Closed", app.Clip.Listening);
            if (app.Net.Problem is string np)
            {
                if (netState.Tag as string != np)
                {
                    var fixBtn = Kit.Button(app.Net.Fix == "network" ? "Set to Private" : "Allow in firewall", () => app.Net.OpenFix(), accent: true);
                    fixBtn.ToolTip = np;
                    netState.Content = fixBtn;
                    netState.Tag = np;
                }
            }
            else if (netState.Tag as string != "ok") { netState.Content = StatusText("Allowed", true); netState.Tag = "ok"; }
            bleOutState.Content = StatusText(app.BleSending == "on" ? "Sending" : app.BleSending == "off" ? "Starting" : app.BleSending == "no radio" ? "Bluetooth is off" : "Not available", app.BleSending == "on");
        };
        live();
    }

    private static string Age(double s) => s < 60 ? $"{s:0} s ago" : s < 3600 ? $"{s / 60:0} min ago" : $"{s / 3600:0} h ago";

    private void History()
    {
        var list = new StackPanel();
        Add(list);
        Note("Why the earbuds moved, newest first. Every switch is also in the log.");
        int count = -1;
        live = () =>
        {
            var events = app.RecentEvents();
            if (events.Count == count) return;
            count = events.Count;
            list.Children.Clear();
            if (events.Count == 0) list.Children.Add(Kit.Row("", "Nothing yet", "Switches, pauses and devices joining appear here.", null));
            foreach (var (time, kind, text) in events)
            {
                string glyph = kind switch { "claim" => "", "release" => "", "pause" => "", "resume" => "", "peer" => "", "safe" => "", _ => "" };
                var tag = new Border { CornerRadius = new CornerRadius(4), Padding = new Thickness(8, 1, 8, 2), Child = Kit.Text(time, 12, "Text2") };
                tag.SetResourceReference(Border.BackgroundProperty, "Control");
                list.Children.Add(Kit.Row(glyph, Capital(text), kind, tag));
            }
        };
        live();
    }

    private static string Capital(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];

    private void LogPage()
    {
        var bar = new WrapPanel { Margin = new Thickness(0, 0, 0, 10) };
        var copy = Kit.Button("Copy diagnostics", () => app.CopyDiagnostics(), accent: true);
        copy.Margin = new Thickness(0, 0, 8, 0);
        bar.Children.Add(copy);
        var folder = Kit.Button("Open log folder", () => Open(Config.Dir));
        folder.Margin = new Thickness(0, 0, 8, 0);
        bar.Children.Add(folder);
        Add(bar);
        var box = new WpfTextBox { IsReadOnly = true, FontFamily = new System.Windows.Media.FontFamily("Cascadia Mono, Consolas"), FontSize = 12, TextWrapping = TextWrapping.NoWrap,
            Height = 480, BorderThickness = new Thickness(1), Padding = new Thickness(10), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto };
        box.SetResourceReference(ForegroundProperty, "Text1");
        box.SetResourceReference(BackgroundProperty, "Card");
        box.SetResourceReference(BorderBrushProperty, "CardStroke");
        Add(box);
        Note("The log never contains clipboard contents, only kinds and sizes.");
        string last = "";
        live = () =>
        {
            var t = Log.Tail(300);
            if (t == last) return;
            bool atEnd = box.VerticalOffset + box.ViewportHeight >= box.ExtentHeight - 4;
            last = t;
            box.Text = t;
            if (atEnd) box.ScrollToEnd();
        };
        live();
    }

    // MARK: helpers

    private static string Mask(string s) => s.Length == 0 ? "Not set" : new string('•', Math.Min(24, s.Length));

    private static void Open(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); } catch (Exception e) { Log.Write($"open: {e.Message}"); }
    }

    private static BitmapImage QrImage(string text)
    {
        using var gen = new QRCoder.QRCodeGenerator();
        using var data = gen.CreateQrCode(text, QRCoder.QRCodeGenerator.ECCLevel.M);
        var png = new QRCoder.PngByteQRCode(data).GetGraphic(8);
        var img = new BitmapImage();
        img.BeginInit();
        img.StreamSource = new MemoryStream(png);
        img.CacheOption = BitmapCacheOption.OnLoad;
        img.EndInit();
        img.Freeze();
        return img;
    }

    /// <summary>The app icon at its largest size (256 px), for pictures inside the windows. The first frame of the .ico
    /// is the 16 px one, which looked blurry when scaled up.</summary>
    public static BitmapSource? AppLogo()
    {
        try
        {
            var st = typeof(SettingsWindow).Assembly.GetManifestResourceStream("EarHop.Win.EarHop.ico");
            if (st == null) return null;
            var dec = new IconBitmapDecoder(st, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            var best = dec.Frames.OrderByDescending(f => f.PixelWidth).First();
            best.Freeze();
            return best;
        }
        catch { return null; }
    }

    public static BitmapFrame? AppIconImage()
    {
        try
        {
            var s = typeof(SettingsWindow).Assembly.GetManifestResourceStream("EarHop.Win.EarHop.ico");
            return s == null ? null : BitmapFrame.Create(s, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        }
        catch { return null; }
    }
}
