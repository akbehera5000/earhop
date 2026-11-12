using System.Drawing.Drawing2D;

namespace EarHop.Win;

/// <summary>The "Send to" panel, like the Mac's and the Android share sheet: round device buttons in a row with their
/// names, and a Cancel button. Green outlines mark devices that can be reached now (every listed one is).</summary>
sealed class SendPicker : Form
{
    private static SendPicker? current;

    public static void Open(string title, List<string> devices, Action<string?> pick)
    {
        current?.Close();
        current = new SendPicker(title, devices, pick);
        current.Show();
        current.Activate();
    }

    private SendPicker(string title, List<string> devices, Action<string?> pick)
    {
        Text = "Send with EarHop";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        ShowInTaskbar = true;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 9.75f);
        BackColor = SystemColors.Window;
        Icon = TrayApp.AppIcon();
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(20, 16, 20, 16);

        var root = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false };
        root.Controls.Add(new Label { Text = "Send to", Font = new Font("Segoe UI Semibold", 15f), AutoSize = true, Margin = new Padding(0, 0, 0, 2) });
        root.Controls.Add(new Label { Text = $"{title}, over Wi-Fi", ForeColor = SystemColors.GrayText, AutoSize = true, MaximumSize = new Size(420, 0), Margin = new Padding(0, 0, 0, 12) });
        var row = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, WrapContents = true, MaximumSize = new Size(460, 0) };
        var choices = (devices.Count > 1 ? new List<string?> { null } : new List<string?>()).Concat(devices.Select(d => (string?)d));
        foreach (var c in choices)
        {
            var b = new DeviceButton(c);
            b.Click += (_, _) => { Close(); pick(c); };
            row.Controls.Add(b);
        }
        root.Controls.Add(row);
        var cancel = new Button { Text = "Cancel", AutoSize = false, Size = new Size(120, 32), Margin = new Padding(0, 14, 0, 0), FlatStyle = FlatStyle.System };
        cancel.Click += (_, _) => { Log.Write("share: cancelled"); Close(); };
        root.Controls.Add(cancel);
        Controls.Add(root);
        CancelButton = cancel;
        Load += (_, _) =>
        {
            var p = Cursor.Position;
            var area = Screen.FromPoint(p).WorkingArea;
            Location = new Point(Math.Clamp(p.X - Width / 2, area.Left + 8, area.Right - Width - 8), Math.Clamp(p.Y - Height / 2, area.Top + 8, area.Bottom - Height - 8));
        };
    }

    private sealed class DeviceButton : Control
    {
        private readonly string? dev;
        private bool hover;
        private static readonly Color Green = Color.FromArgb(48, 209, 89);

        public DeviceButton(string? dev)
        {
            this.dev = dev;
            Size = new Size(84, 96);
            Margin = new Padding(0, 0, 10, 0);
            Cursor = Cursors.Hand;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            AccessibleName = dev == null ? "All devices" : DeviceNames.Pretty(dev);
            AccessibleRole = AccessibleRole.PushButton;
            TabStop = true;
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode is Keys.Enter or Keys.Space) OnClick(e); base.OnKeyDown(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        // Segoe MDL2 Assets glyphs: phone, tablet, laptop, and a group of devices for "All devices".
        private string Glyph => dev switch { null => "", "phone" => "", "tab" => "", _ => "" };

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            int d = 62, x = (Width - d) / 2;
            var circle = new Rectangle(x, 2, d, d);
            using (var fill = new SolidBrush(hover ? Color.FromArgb(28, 0, 0, 0) : Color.FromArgb(14, 0, 0, 0))) g.FillEllipse(fill, circle);
            using (var pen = new Pen(Green, Focused ? 3.5f : 2.5f)) g.DrawEllipse(pen, circle);
            using (var gf = new Font("Segoe MDL2 Assets", 20f))
                TextRenderer.DrawText(g, Glyph, gf, circle, ForeColor, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            var label = new Rectangle(0, d + 8, Width, Height - d - 8);
            TextRenderer.DrawText(g, dev == null ? "All devices" : DeviceNames.Pretty(dev), Font, label, ForeColor, TextFormatFlags.HorizontalCenter | TextFormatFlags.Top | TextFormatFlags.EndEllipsis);
        }
    }
}
