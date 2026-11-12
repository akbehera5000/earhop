using System.Text.Json;

namespace EarHop.Win;

/// <summary>Settings: this PC's name, the shared secret, which earbuds, switching and clipboard options.</summary>
sealed class SettingsForm : Form
{
    private readonly Config original;
    private readonly Action<Config> save;
    private readonly TextBox name = new() { Width = 260 };
    private readonly TextBox secret = new() { Width = 260, UseSystemPasswordChar = true };
    private readonly ComboBox earbuds = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 260 };
    private readonly TextBox budsName = new() { Width = 260 };
    private readonly TextBox airpodsName = new() { Width = 260 };
    private readonly CheckBox autoSwitch = new() { Text = "Automatic switching", AutoSize = true };
    private readonly CheckBox clipSync = new() { Text = "Clipboard sync", AutoSize = true };
    private readonly CheckBox clipAuto = new() { Text = "Send every copy automatically", AutoSize = true };
    private readonly CheckBox clipReceive = new() { Text = "Receive clips and files", AutoSize = true };
    private readonly CheckBox clipImages = new() { Text = "Images too", AutoSize = true };
    private readonly CheckBox clipNotify = new() { Text = "Notify when a clip arrives", AutoSize = true };
    private readonly CheckBox clipSkip = new() { Text = "Skip password-like text", AutoSize = true };
    private readonly CheckBox startup = new() { Text = "Start EarHop when I sign in", AutoSize = true };
    private readonly Label error = new() { ForeColor = Color.Firebrick, AutoSize = true, MaximumSize = new Size(440, 0) };

    public SettingsForm(Config cfg, Action<Config> save)
    {
        original = cfg;
        this.save = save;
        Text = "EarHop Settings";
        Icon = TrayApp.AppIcon();
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 9.75f);
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(18);

        name.Text = cfg.DeviceName;
        secret.Text = cfg.Secret;
        earbuds.Items.AddRange(new object[] { "Galaxy Buds2 Pro", "AirPods" });
        earbuds.SelectedIndex = cfg.IsBuds ? 0 : 1;
        budsName.Text = cfg.BudsName;
        airpodsName.Text = cfg.AirPodsName;
        autoSwitch.Checked = cfg.AutoSwitch;
        clipSync.Checked = cfg.ClipSync;
        clipAuto.Checked = cfg.ClipAuto;
        clipReceive.Checked = cfg.ClipReceive;
        clipImages.Checked = cfg.ClipImages;
        clipNotify.Checked = cfg.ClipNotify;
        clipSkip.Checked = cfg.ClipSkipPasswords;
        startup.Checked = cfg.StartAtLogin;

        var t = new TableLayoutPanel { ColumnCount = 2, AutoSize = true };
        void Row(string label, Control c, string? help = null)
        {
            t.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 7, 12, 3) });
            t.Controls.Add(c);
            if (help != null)
            {
                t.Controls.Add(new Label());
                t.Controls.Add(new Label { Text = help, ForeColor = SystemColors.GrayText, AutoSize = true, MaximumSize = new Size(300, 0), Margin = new Padding(3, 0, 3, 8) });
            }
        }
        void Section(string title)
        {
            var l = new Label { Text = title, Font = new Font("Segoe UI Semibold", 10.5f), AutoSize = true, Margin = new Padding(0, 12, 0, 4) };
            t.Controls.Add(l);
            t.SetColumnSpan(l, 2);
        }
        void Check(CheckBox c) { t.Controls.Add(new Label()); t.Controls.Add(c); }

        Section("This PC");
        Row("Name", name, "Lower case letters, digits and -, different on every device (for example pc or work-pc).");
        Row("Secret", secret, "The same on every device. Copy it from the Mac (Settings, Network) or paste its earhop://pair link.");
        var show = new CheckBox { Text = "Show the secret", AutoSize = true };
        show.CheckedChanged += (_, _) => secret.UseSystemPasswordChar = !show.Checked;
        Check(show);
        Section("Earbuds");
        Row("Earbuds", earbuds);
        Row("Buds name", budsName, "Part of the name Windows shows in Sound settings. Pair the earbuds with this PC once in Bluetooth settings.");
        Row("AirPods name", airpodsName);
        Check(autoSwitch);
        Section("Clipboard and files (Wi-Fi)");
        Check(clipSync); Check(clipAuto); Check(clipReceive); Check(clipImages); Check(clipNotify); Check(clipSkip);
        Section("General");
        Check(startup);

        var ok = new Button { Text = "Save", Width = 100, Height = 30 };
        var cancel = new Button { Text = "Cancel", Width = 100, Height = 30 };
        ok.Click += (_, _) => Save();
        cancel.Click += (_, _) => Close();
        AcceptButton = ok;
        CancelButton = cancel;
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Dock = DockStyle.Fill, Margin = new Padding(0, 14, 0, 0) };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(ok);
        t.Controls.Add(error);
        t.SetColumnSpan(error, 2);
        t.Controls.Add(buttons);
        t.SetColumnSpan(buttons, 2);
        Controls.Add(t);
    }

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

    private void Save()
    {
        var n = name.Text.Trim();
        if (!DeviceNames.Valid(n)) { error.Text = "The name must be 1 to 20 lower case letters, digits or -, starting with a letter or digit."; return; }
        if (n is "mac" or "phone" or "tab") { error.Text = $"\"{n}\" is used by another device. Give this PC its own name, for example pc."; return; }
        var sec = ParseSecret(secret.Text);
        if (sec.Length == 0) { error.Text = "Paste the secret from one of your other devices."; return; }
        var c = JsonSerializer.Deserialize<Config>(JsonSerializer.Serialize(original))!;
        c.DeviceName = n;
        c.Secret = sec;
        c.Earbuds = earbuds.SelectedIndex == 0 ? "buds" : "airpods";
        c.BudsName = budsName.Text.Trim().Length > 0 ? budsName.Text.Trim() : "Buds2 Pro";
        c.AirPodsName = airpodsName.Text.Trim().Length > 0 ? airpodsName.Text.Trim() : "AirPods";
        c.AutoSwitch = autoSwitch.Checked;
        c.ClipSync = clipSync.Checked;
        c.ClipAuto = clipAuto.Checked;
        c.ClipReceive = clipReceive.Checked;
        c.ClipImages = clipImages.Checked;
        c.ClipNotify = clipNotify.Checked;
        c.ClipSkipPasswords = clipSkip.Checked;
        c.StartAtLogin = startup.Checked;
        save(c);
        Close();
    }
}
