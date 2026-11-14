using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shell;
using WpfButton = System.Windows.Controls.Button;
using WpfCheckBox = System.Windows.Controls.CheckBox;
using WpfHA = System.Windows.HorizontalAlignment;
using WpfOrientation = System.Windows.Controls.Orientation;
using WpfVA = System.Windows.VerticalAlignment;
using WpfMenu = System.Windows.Controls.ContextMenu;
using WpfMenuItem = System.Windows.Controls.MenuItem;

using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Image = System.Windows.Controls.Image;
using Rectangle = System.Windows.Shapes.Rectangle;
using Panel = System.Windows.Controls.Panel;
using Control = System.Windows.Controls.Control;
using Label = System.Windows.Controls.Label;
using Clipboard = System.Windows.Clipboard;

namespace EarHop.Win.UI;

/// <summary>
/// The tray flyout (the Mac popover's sibling): which earbuds, a live picture, Take and Release, why it is not
/// switching, the Switching and Clipboard tiles, the other devices and the footer. Acrylic on Windows 11, it opens above
/// the tray with the Windows flyout motion and closes when it loses focus.
/// </summary>
sealed class Flyout : Window
{
    private readonly TrayApp app;
    private readonly TextBlock title = Kit.Text("", 16, "Text1", FontWeights.SemiBold);
    private readonly TextBlock subtitle = Kit.Text("", 12, "Text2");
    private readonly Border pill = new() { CornerRadius = new CornerRadius(11), BorderThickness = new Thickness(1.5), Padding = new Thickness(10, 2, 10, 3) };
    private readonly TextBlock pillText = Kit.Text("", 12, "Text1", FontWeights.SemiBold);
    private readonly BudsArt art = new();
    private readonly WpfButton take;
    private readonly WpfButton release;
    private readonly TextBlock note = Kit.Text("", 12, "Text2", wrap: true);
    private readonly StackPanel notes = new() { Margin = new Thickness(2, 8, 2, 0) };
    private readonly Border switchTile;
    private readonly Border clipTile;
    private readonly TextBlock switchSub = Kit.Text("", 12, "Text2");
    private readonly TextBlock clipSub = Kit.Text("", 12, "Text2");
    private readonly WpfCheckBox switchToggle;
    private readonly WpfCheckBox clipToggle;
    private readonly WrapPanel devices = new();
    private readonly TextBlock clipStatus = Kit.Text("", 12, "Text2");
    private readonly System.Windows.Threading.DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly Border root;
    private readonly TranslateTransform slide = new(0, 12);
    private bool closing;
    private string deviceKey = "";
    private readonly Border earbudsSeg;
    private readonly StackPanel battery = new() { Orientation = WpfOrientation.Horizontal, HorizontalAlignment = WpfHA.Center, Margin = new Thickness(0, 2, 0, 4) };
    private readonly Border controls;
    private readonly Border noiseSeg;
    private readonly UniformGrid1 eqChips = new(3) { Rows = 2 };
    private readonly WpfCheckBox touch;
    private readonly TextBlock controlsHint = Kit.Text("", 12, "Text2", wrap: true);
    private readonly StackPanel controlsBody = new();
    private readonly Border banner = new() { CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1.5), Padding = new Thickness(12, 10, 12, 10), Margin = new Thickness(0, 0, 0, 10), Visibility = Visibility.Collapsed };
    private readonly TextBlock bannerText = Kit.Text("", 12, "Text1", wrap: true);
    private readonly ScrollViewer scroll;
    private readonly StackPanel controlsDetail = new() { Visibility = Visibility.Collapsed };
    private readonly TextBlock controlsSummary = Kit.Text("", 12, "Text2");
    private readonly TextBlock chevron = Kit.Glyph("\uE70D", 12, "Text2");
    private string batteryKey = "", notesKey = "";

    public Flyout(TrayApp app)
    {
        this.app = app;
        Title = "EarHop";
        Width = 384;
        SizeToContent = SizeToContent.Height;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        Topmost = true;
        FontFamily = Fluent.Font;
        WindowChrome.SetWindowChrome(this, new WindowChrome { CaptionHeight = 0, GlassFrameThickness = new Thickness(-1), ResizeBorderThickness = new Thickness(0), UseAeroCaptionButtons = false, CornerRadius = new CornerRadius(8) });
        Fluent.Backdrop(this, acrylic: true);

        var stack = new StackPanel { Margin = new Thickness(16, 14, 16, 8) };

        // Header: earbuds name, status and a state pill (green outline when here).
        var head = new Grid { Margin = new Thickness(0, 0, 0, 12) };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var logo = new Border { Width = 36, Height = 36, CornerRadius = new CornerRadius(18), Margin = new Thickness(0, 0, 12, 0), Child = Kit.Glyph("", 17, "AccentText") };
        logo.SetResourceReference(Border.BackgroundProperty, "AccentSoft");
        head.Children.Add(logo);
        var ht = new StackPanel { VerticalAlignment = WpfVA.Center };
        ht.Children.Add(title);
        ht.Children.Add(subtitle);
        Grid.SetColumn(ht, 1);
        head.Children.Add(ht);
        pill.Child = pillText;
        pill.VerticalAlignment = WpfVA.Center;
        Grid.SetColumn(pill, 2);
        head.Children.Add(pill);
        stack.Children.Add(head);

        // A warning when Windows keeps the other devices out (Public network or firewall), with the page to fix it.
        var bannerBody = new StackPanel();
        bannerBody.Children.Add(Kit.IconLabel("\uE7BA", "Your devices cannot reach this PC", "Text1", 13));
        bannerText.Margin = new Thickness(0, 4, 0, 8);
        bannerBody.Children.Add(bannerText);
        var fix = Kit.Button("Open the Windows setting", () => { Close(); app.Net.OpenFix(); });
        fix.HorizontalAlignment = WpfHA.Left;
        bannerBody.Children.Add(fix);
        banner.Child = bannerBody;
        banner.SetResourceReference(Border.BorderBrushProperty, "Warning");
        banner.SetResourceReference(Border.BackgroundProperty, "Card");
        stack.Children.Add(banner);

        // Earbuds picker: Automatic follows the pair connected here or held by another device.
        earbudsSeg = Kit.Segmented(new[] { "Automatic", "Galaxy Buds", "AirPods" }, SegIndex(), i =>
        {
            if (i == 0) app.SetEarbudsMode("auto"); else app.SetEarbuds(i == 1 ? "buds" : "airpods");
            Refresh();
        });
        earbudsSeg.Margin = new Thickness(0, 0, 0, 10);
        stack.Children.Add(earbudsSeg);

        // Hero card: the picture, Take and Release, and notes.
        var hero = new StackPanel();
        // A smaller picture in the flyout (the Earbuds page shows it full size).
        art.Height = 104;
        art.LayoutTransform = new ScaleTransform(0.82, 0.82);
        hero.Children.Add(art);
        hero.Children.Add(battery);
        var actions = new Grid { Margin = new Thickness(0, 6, 0, 0) };
        actions.ColumnDefinitions.Add(new ColumnDefinition());
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
        actions.ColumnDefinitions.Add(new ColumnDefinition());
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        take = Kit.Button("Take", () => { app.Controller.ClaimNow(); Refresh(); }, accent: true, glyph: "");
        release = Kit.Button("Release", () => { app.Controller.ReleaseNow(); Refresh(); }, glyph: "");
        Grid.SetColumn(release, 2);
        var sleep = Kit.Button("", () => { }, glyph: null);
        sleep.Content = Kit.Glyph("", 15);
        sleep.ToolTip = "Pause switching";
        sleep.Padding = new Thickness(10, 6, 10, 6);
        sleep.Click += (_, _) => PauseMenu(sleep);
        Grid.SetColumn(sleep, 4);
        actions.Children.Add(take);
        actions.Children.Add(release);
        actions.Children.Add(sleep);
        hero.Children.Add(actions);
        notes.Children.Add(note);
        hero.Children.Add(notes);
        var heroCard = Kit.Card(hero, new Thickness(14, 8, 14, 12));
        heroCard.Margin = new Thickness(0, 0, 0, 10);
        stack.Children.Add(heroCard);

        // Galaxy Buds controls (through the Buds' own channel while they are connected here).
        noiseSeg = Kit.Segmented(new[] { "Off", "ANC", "Ambient" }, 0, i => app.Buds.SetNoise(BudsProtocol.NoiseModes[i]));
        touch = Kit.Toggle(true, v => app.Buds.SetTouch(v));
        // Folded into one line by default, so the flyout stays small; a click opens noise control, equalizer and touch.
        var ch = new StackPanel();
        var headGrid = new Grid();
        headGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        headGrid.ColumnDefinitions.Add(new ColumnDefinition());
        headGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var ctlHead = Kit.IconLabel("\uE9E9", "Earbud controls", "Text1", 13);
        ((TextBlock)ctlHead.Children[1]).FontWeight = FontWeights.SemiBold;
        headGrid.Children.Add(ctlHead);
        controlsSummary.HorizontalAlignment = WpfHA.Right;
        controlsSummary.Margin = new Thickness(8, 0, 10, 0);
        controlsSummary.VerticalAlignment = WpfVA.Center;
        Grid.SetColumn(controlsSummary, 1);
        headGrid.Children.Add(controlsSummary);
        Grid.SetColumn(chevron, 2);
        headGrid.Children.Add(chevron);
        var headBtn = new WpfButton { Style = Kit.S("SubtleBtn"), Content = headGrid, HorizontalContentAlignment = WpfHA.Stretch, Padding = new Thickness(8, 6, 8, 6), Margin = new Thickness(-8, -6, -8, -6) };
        headBtn.Click += (_, _) =>
        {
            bool open = controlsDetail.Visibility != Visibility.Visible;
            controlsDetail.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
            chevron.Text = open ? "\uE70E" : "\uE70D";
        };
        ch.Children.Add(headBtn);
        controlsHint.Margin = new Thickness(0, 10, 0, 0);
        controlsDetail.Children.Add(controlsHint);
        controlsBody.Margin = new Thickness(0, 10, 0, 0);
        controlsBody.Children.Add(Kit.Text("Noise control", 12, "Text2"));
        noiseSeg.Margin = new Thickness(0, 4, 0, 10);
        controlsBody.Children.Add(noiseSeg);
        controlsBody.Children.Add(Kit.Text("Equalizer", 12, "Text2"));
        eqChips.Margin = new Thickness(0, 4, 0, 6);
        foreach (var e in BudsProtocol.EqPresets)
        {
            string preset = e;
            var chip = new System.Windows.Controls.RadioButton { Style = Kit.S("Segment"), GroupName = "eq" + GetHashCode(), Content = e == "off" ? "Normal" : char.ToUpperInvariant(e[0]) + e[1..], Tag = e };
            chip.Checked += (_, _) => { if (!Kit.Quiet) app.Buds.SetEq(preset); };
            eqChips.Children.Add(chip);
        }
        controlsBody.Children.Add(eqChips);
        var touchRow = new Grid();
        touchRow.Children.Add(Kit.Text("Touch controls", 13));
        touch.HorizontalAlignment = WpfHA.Right;
        touchRow.Children.Add(touch);
        controlsBody.Children.Add(touchRow);
        controlsDetail.Children.Add(controlsBody);
        ch.Children.Add(controlsDetail);
        controls = Kit.Card(ch, new Thickness(14, 10, 14, 10));
        controls.Margin = new Thickness(0, 0, 0, 10);
        stack.Children.Add(controls);

        // Switching and Clipboard tiles.
        var tiles = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        tiles.ColumnDefinitions.Add(new ColumnDefinition());
        tiles.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
        tiles.ColumnDefinitions.Add(new ColumnDefinition());
        switchToggle = Kit.Toggle(app.Cfg.AutoSwitch, v => app.Update(c => c.AutoSwitch = v));
        clipToggle = Kit.Toggle(app.Cfg.ClipSync, v => app.Update(c => c.ClipSync = v));
        switchTile = Tile("", "Switching", switchSub, switchToggle,
            Kit.Subtle("", "", () => PauseMenu(switchTile!)));
        clipTile = Tile("", "Clipboard", clipSub, clipToggle,
            Kit.Subtle("", "", () => { app.Clip.SendNow(); Refresh(); }),
            Kit.Subtle("", "", () => { Close(); app.ChooseFiles(); }));
        ((WpfButton)((StackPanel)((Grid)switchTile.Child).Children[^1]).Children[0]).ToolTip = "Pause switching";
        var clipButtons = (StackPanel)((Grid)clipTile.Child).Children[^1];
        ((WpfButton)clipButtons.Children[0]).ToolTip = "Send the clipboard now";
        ((WpfButton)clipButtons.Children[1]).ToolTip = "Send files";
        Grid.SetColumn(clipTile, 2);
        tiles.Children.Add(switchTile);
        tiles.Children.Add(clipTile);
        stack.Children.Add(tiles);

        // Devices.
        var devHead = new Grid { Margin = new Thickness(2, 0, 0, 6) };
        devHead.Children.Add(Kit.Text("Devices", 13, "Text2", FontWeights.SemiBold));
        clipStatus.HorizontalAlignment = WpfHA.Right;
        devHead.Children.Add(clipStatus);
        stack.Children.Add(devHead);
        stack.Children.Add(devices);

        // Footer.
        var foot = new Grid { Margin = new Thickness(-8, 8, -8, 0) };
        var leftFoot = Kit.Subtle("", "Settings", () => { Close(); app.ShowSettings(); });
        var rightFoot = new StackPanel { Orientation = WpfOrientation.Horizontal, HorizontalAlignment = WpfHA.Right };
        rightFoot.Children.Add(Kit.Subtle("", "Copy Log", () => { app.CopyDiagnostics(); clipStatus.Text = "Log copied"; }));
        rightFoot.Children.Add(Kit.Subtle("", "Quit", () => { Close(); app.Quit(); }));
        foot.Children.Add(leftFoot);
        foot.Children.Add(rightFoot);
        var footBorder = new Border { BorderThickness = new Thickness(0, 1, 0, 0), Padding = new Thickness(0, 4, 0, 0), Child = foot, Margin = new Thickness(-16, 4, -16, -8) };
        footBorder.SetResourceReference(Border.BorderBrushProperty, "Divider");
        foot.Margin = new Thickness(8, 4, 8, 8);
        stack.Children.Add(footBorder);

        scroll = new ScrollViewer { Content = stack, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = SystemParameters.WorkArea.Height - 24 };
        SmoothScroll.Attach(scroll);
        root = new Border { Child = scroll, RenderTransform = slide, Opacity = 0 };
        Content = root;

        Deactivated += (_, _) => { if (!closing) Close(); };
        Closing += (_, _) => { closing = true; timer.Stop(); };
        KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
        Loaded += (_, _) =>
        {
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            slide.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(12, 0, TimeSpan.FromMilliseconds(250)) { EasingFunction = ease });
            root.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
        };
        timer.Tick += (_, _) => Refresh();
        Refresh();
        timer.Start();
    }

    private static Border Tile(string glyph, string name, TextBlock sub, WpfCheckBox toggle, params WpfButton[] buttons)
    {
        var g = new Grid();
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var top = new Grid();
        var label = Kit.IconLabel(glyph, name, "Text1", 14);
        ((TextBlock)label.Children[1]).FontWeight = FontWeights.SemiBold;
        top.Children.Add(label);
        toggle.HorizontalAlignment = WpfHA.Right;
        top.Children.Add(toggle);
        g.Children.Add(top);
        sub.Margin = new Thickness(0, 4, 0, 4);
        Grid.SetRow(sub, 1);
        g.Children.Add(sub);
        var row = new StackPanel { Orientation = WpfOrientation.Horizontal, Margin = new Thickness(-8, 0, 0, -4) };
        foreach (var b in buttons) row.Children.Add(b);
        Grid.SetRow(row, 2);
        g.Children.Add(row);
        var card = Kit.Card(g, new Thickness(12, 10, 12, 8));
        card.BorderThickness = new Thickness(1.5);
        return card;
    }

    private void PauseMenu(FrameworkElement anchor)
    {
        var m = new WpfMenu { PlacementTarget = anchor, Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom, FontFamily = Fluent.Font };
        void Add(string text, Action a) { var i = new WpfMenuItem { Header = text }; i.Click += (_, _) => { a(); Refresh(); }; m.Items.Add(i); }
        if (app.Controller.PausedUntil > DateTime.UtcNow) Add("Resume switching now", () => app.PauseSwitching(TimeSpan.Zero));
        Add("Pause for 15 minutes", () => app.PauseSwitching(TimeSpan.FromMinutes(15)));
        Add("Pause for 1 hour", () => app.PauseSwitching(TimeSpan.FromHours(1)));
        Add("Pause until I turn it back on", () => app.Update(c => c.AutoSwitch = false));
        m.IsOpen = true;
    }

    private int SegIndex() => app.Cfg.EarbudsMode == "auto" ? 0 : app.Cfg.IsBuds ? 1 : 2;

    private static Border BattPill(string label, int level, bool charging)
    {
        var p = new StackPanel { Orientation = WpfOrientation.Horizontal };
        // Segoe battery glyphs: Battery0..9 at U+E850..E859, Battery10 U+E83F; charging 0..9 at U+E85A..E863, 10 U+E83E.
        int step = Math.Clamp((int)Math.Round(level / 10.0), 0, 10);
        int code = step == 10 ? (charging ? 0xE83E : 0xE83F) : (charging ? 0xE85A : 0xE850) + step;
        var g = Kit.Glyph(((char)code).ToString(), 14, level <= 15 && !charging ? "Danger" : "Text1");
        g.Margin = new Thickness(0, 0, 6, 0);
        p.Children.Add(Kit.Text(label, 12, "Text2"));
        p.Children.Add(new Border { Width = 6 });
        p.Children.Add(g);
        p.Children.Add(Kit.Text($"{level}%", 12, "Text1", FontWeights.SemiBold));
        var b = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(10, 3, 10, 4), Margin = new Thickness(3, 0, 3, 0), Child = p };
        b.SetResourceReference(Border.BackgroundProperty, "Control");
        return b;
    }

    /// <summary>Reads the live state from the tray app and updates every part of the flyout.</summary>
    public void Refresh()
    {
        var cfg = app.Cfg;
        Kit.SetSegment(earbudsSeg, SegIndex());

        // Network warning.
        if (app.Net.Problem is string np) { bannerText.Text = np; banner.Visibility = Visibility.Visible; }
        else banner.Visibility = Visibility.Collapsed;

        // Battery: from this PC's Buds channel, or what the device that holds them reported.
        var bs = app.Buds.State;
        string bk = $"{cfg.IsBuds}{app.Buds.Open}{bs.BattL},{bs.BattR},{bs.BattCase},{bs.ChargeL},{bs.ChargeR},{bs.ChargeCase}|{app.LastBattery}";
        if (bk != batteryKey)
        {
        batteryKey = bk;
        battery.Children.Clear();
        if (cfg.IsBuds && app.Buds.Open && (bs.BattL >= 0 || bs.BattR >= 0))
        {
            if (bs.BattL >= 0) battery.Children.Add(BattPill("L", bs.BattL, bs.ChargeL));
            if (bs.BattCase >= 0) battery.Children.Add(BattPill("Case", bs.BattCase, bs.ChargeCase));
            if (bs.BattR >= 0) battery.Children.Add(BattPill("R", bs.BattR, bs.ChargeR));
        }
        else if (app.LastBattery is string lb)
        {
            var t = Kit.Text(lb, 12, "Text2");
            battery.Children.Add(t);
        }
        }

        // Buds controls: live only while the Buds' channel is open on this PC.
        controls.Visibility = cfg.IsBuds ? Visibility.Visible : Visibility.Collapsed;
        bool budsLive = app.Buds.Open && bs.Revision >= 0 || app.Buds.Open && bs.Noise != "unknown";
        controlsBody.IsEnabled = budsLive;
        controlsBody.Opacity = budsLive ? 1 : 0.45;
        controlsSummary.Text = budsLive ? $"{(bs.Noise == "anc" ? "ANC" : bs.Noise == "ambient" ? "Ambient" : "Noise off")}, {(bs.Eq == "off" ? "Normal" : bs.Eq)}" : "Not connected here";
        controlsHint.Text = budsLive ? (bs.Wearing == 2 ? "In both ears." : bs.Wearing == 1 ? "In one ear." : "Out of your ears.")
                          : app.Owner ? "Connecting to the Buds' controls..." : "Available while the Buds are connected to this PC.";
        if (budsLive)
        {
            int ni = Array.IndexOf(BudsProtocol.NoiseModes, bs.Noise);
            if (ni >= 0) Kit.SetSegment(noiseSeg, ni);
            foreach (System.Windows.Controls.RadioButton chip in eqChips.Children)
                if ((string)chip.Tag == bs.Eq && chip.IsChecked != true) { Kit.Quiet = true; chip.IsChecked = true; Kit.Quiet = false; }
            if (bs.TouchOn is bool tOn && touch.IsChecked != tOn) { Kit.Quiet = true; touch.IsChecked = tOn; Kit.Quiet = false; }
        }
        var ctl = app.Controller;
        bool here = app.Owner;
        string name = cfg.IsBuds ? "Galaxy Buds2 Pro" : "AirPods Pro";
        title.Text = name;
        string? holder = ctl.OwnerPeer();
        string phase = ctl.Stage ?? PhaseText.Label(ctl.Core.Phase);
        subtitle.Text = here ? (app.Playing ? "Playing on this PC" : "Connected to this PC")
                      : holder != null ? $"Connected to {DeviceNames.Pretty(holder)}" : phase;
        pillText.Text = here ? "Here" : holder != null ? DeviceNames.Pretty(holder) : "Away";
        pill.SetResourceReference(Border.BorderBrushProperty, here ? "StateOn" : "StateOff");
        art.Show(cfg.IsBuds, here);

        take.IsEnabled = !here && ctl.Stage == null;
        ((TextBlock)((StackPanel)take.Content).Children[1]).Text = here ? "Here" : "Take";
        release.IsEnabled = here && ctl.Stage == null;

        string nk = $"{ctl.Stage}|{(here ? "" : ctl.HoldReason)}|{ctl.LastFailure}|{ctl.Core.SafeMode}|{cfg.AutoSwitch}|{here}";
        bool rebuildNotes = nk != notesKey;
        notesKey = nk;
        if (rebuildNotes) notes.Children.Clear();
        void Note(string text, string brush) { if (!rebuildNotes) return; var t = Kit.Text(text, 12, brush, wrap: true); t.Margin = new Thickness(0, 2, 0, 0); notes.Children.Add(t); }
        if (ctl.Stage != null) Note(ctl.Stage, "AccentText");
        else if (ctl.HoldReason is string h && !here) Note("Not switching: " + h, "Text2");
        if (ctl.LastFailure is string f) Note("Last switch failed: " + f, "Danger");
        if (ctl.Core.SafeMode && rebuildNotes)
        {
            Note($"Safe mode: automatic switching stopped after {ctl.Core.Failures} failed switches.", "Warning");
            var b = Kit.Button("Leave safe mode", () => { ctl.ClearSafeMode(); Refresh(); });
            b.HorizontalAlignment = WpfHA.Left;
            b.Margin = new Thickness(0, 6, 0, 0);
            notes.Children.Add(b);
        }
        if (rebuildNotes && notes.Children.Count == 0)
            Note(!cfg.AutoSwitch ? "Automatic switching is off. Take and Release still work."
                 : here ? "Playing anywhere else moves the earbuds there."
                 : "Play something on this PC and the earbuds come here.", "Text2");

        // Tiles: the state colour is on the outline only.
        bool paused = ctl.PausedUntil > DateTime.UtcNow;
        bool switching = cfg.AutoSwitch && !paused;
        if (switchToggle.IsChecked != cfg.AutoSwitch) { Kit.Quiet = true; switchToggle.IsChecked = cfg.AutoSwitch; Kit.Quiet = false; }
        switchSub.Text = !cfg.AutoSwitch ? "Off" : paused ? $"Paused until {ctl.PausedUntil.ToLocalTime():t}" : app.LinkSummary();
        switchTile.SetResourceReference(Border.BorderBrushProperty, switching ? "StateOn" : "StateOff");
        int wifiN = app.Clip.WifiDevices().Count, bleN = app.Clip.BleDevices().Count;
        var reach = app.Clip.Devices().Count;
        if (clipToggle.IsChecked != cfg.ClipSync) { Kit.Quiet = true; clipToggle.IsChecked = cfg.ClipSync; Kit.Quiet = false; }
        clipSub.Text = !cfg.ClipSync ? "Off" : reach == 0 ? "No device in reach"
                     : $"{reach} device{(reach == 1 ? "" : "s")}, {(bleN > 0 && wifiN > 0 ? "Bluetooth and Wi-Fi" : bleN > 0 ? "Bluetooth" : "Wi-Fi")}";
        clipTile.SetResourceReference(Border.BorderBrushProperty, cfg.ClipSync && reach > 0 ? "StateOn" : "StateOff");
        if (app.LastClipEvent is string ce && (DateTime.UtcNow - app.LastClipEventAt).TotalSeconds < 8) clipStatus.Text = ce;

        // Devices (rebuilt only when something changed, so hover states survive).
        var peers = app.Controller.PeerInfos();
        var known = NameBook.Shared.All.Where(n => n != cfg.DeviceName).ToList();
        var key = string.Join("|", known.Select(n => { var p = peers.FirstOrDefault(x => x.Name == n); return $"{n}{p?.Playing}{p?.Owner}{p?.Call}{p != null}"; }));
        if (key != deviceKey)
        {
            deviceKey = key;
            devices.Children.Clear();
            if (known.Count == 0)
            {
                var empty = new StackPanel { Orientation = WpfOrientation.Horizontal };
                empty.Children.Add(Kit.Text("No other device yet.", 12, "Text2"));
                var add = Kit.Subtle("", "Add a device", () => { Close(); app.ShowSettings("Pairing"); }, 12);
                add.Content = Kit.Text("Add a device", 12, "AccentText");
                add.Padding = new Thickness(6, 0, 6, 1);
                empty.Children.Add(add);
                devices.Children.Add(empty);
            }
            foreach (var n in known)
            {
                var p = peers.FirstOrDefault(x => x.Name == n);
                bool live = p != null;
                var cell = new StackPanel { Width = 78, Margin = new Thickness(0, 0, 4, 4) };
                var badge = Kit.DeviceBadge(n, app.KindOf(n), live, p?.Owner == true);
                cell.Children.Add(badge);
                var nm = Kit.Text(DeviceNames.Pretty(n), 12, "Text1");
                nm.HorizontalAlignment = WpfHA.Center;
                nm.Margin = new Thickness(0, 4, 0, 0);
                cell.Children.Add(nm);
                var st = Kit.Text(!live ? "Away" : p!.Call ? "In a call" : p.Owner ? "Has the earbuds" : p.Playing ? "Playing" : "Idle", 11, "Text2");
                st.HorizontalAlignment = WpfHA.Center;
                cell.Children.Add(st);
                cell.ToolTip = live ? $"{DeviceNames.Pretty(n)} over {(p!.Via == "ble" ? "Bluetooth LE" : "Wi-Fi")}, heard {p.Age} s ago" : $"{DeviceNames.Pretty(n)} has not been heard for a while";
                devices.Children.Add(cell);
            }
        }
    }

    /// <summary>Places the flyout above the tray corner of the work area, like the Windows 11 flyouts.</summary>
    public void PlaceNearTray()
    {
        var wa = SystemParameters.WorkArea;
        UpdateLayout();
        double h = ActualHeight > 0 ? ActualHeight : 560;
        Left = wa.Right - Width - 12;
        Top = Math.Max(wa.Top + 8, wa.Bottom - h - 12);
    }
}
