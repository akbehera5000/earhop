using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using WpfApp = System.Windows.Application;
using WpfButton = System.Windows.Controls.Button;
using WpfCheckBox = System.Windows.Controls.CheckBox;
using WpfColor = System.Windows.Media.Color;
using WpfHA = System.Windows.HorizontalAlignment;
using WpfOrientation = System.Windows.Controls.Orientation;
using WpfRadio = System.Windows.Controls.RadioButton;
using WpfTextBox = System.Windows.Controls.TextBox;
using WpfVA = System.Windows.VerticalAlignment;
using WpfPoint = System.Windows.Point;
using WpfSize = System.Windows.Size;

using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Image = System.Windows.Controls.Image;
using Rectangle = System.Windows.Shapes.Rectangle;
using Path = System.Windows.Shapes.Path;
using Panel = System.Windows.Controls.Panel;
using Control = System.Windows.Controls.Control;
using Label = System.Windows.Controls.Label;
using Clipboard = System.Windows.Clipboard;

namespace EarHop.Win.UI;

/// <summary>Small building blocks shared by the flyout and the Settings window.</summary>
static class Kit
{
    public static Style S(string key) => (Style)WpfApp.Current!.Resources[key];

    public static TextBlock Text(string text, double size = 14, string brush = "Text1", FontWeight? weight = null, bool wrap = false)
    {
        var t = new TextBlock { Text = text, FontSize = size, FontFamily = size >= 20 ? Fluent.Display : Fluent.Font, FontWeight = weight ?? FontWeights.Normal, TextTrimming = TextTrimming.CharacterEllipsis };
        t.SetResourceReference(TextBlock.ForegroundProperty, brush);
        if (wrap) { t.TextWrapping = TextWrapping.Wrap; t.TextTrimming = TextTrimming.None; }
        return t;
    }

    public static TextBlock Glyph(string glyph, double size = 16, string brush = "Text1")
    {
        var t = new TextBlock { Text = glyph, FontFamily = Fluent.Icons, FontSize = size, VerticalAlignment = WpfVA.Center, HorizontalAlignment = WpfHA.Center };
        t.SetResourceReference(TextBlock.ForegroundProperty, brush);
        return t;
    }

    /// <summary>A Fluent card: translucent fill, hairline stroke, 8 px corners.</summary>
    public static Border Card(UIElement child, Thickness? padding = null)
    {
        var b = new Border { CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), Padding = padding ?? new Thickness(16, 12, 16, 12), Child = child };
        b.SetResourceReference(Border.BackgroundProperty, "Card");
        b.SetResourceReference(Border.BorderBrushProperty, "CardStroke");
        return b;
    }

    public static WpfButton Button(string text, Action click, bool accent = false, string? glyph = null)
    {
        var b = new WpfButton { Style = S(accent ? "AccentBtn" : "Btn") };
        b.Content = glyph == null ? Text(text, 14, accent ? "OnAccent" : "Text1") : IconLabel(glyph, text, accent ? "OnAccent" : "Text1");
        b.Click += (_, _) => click();
        return b;
    }

    public static WpfButton Subtle(string glyph, string text, Action click, double size = 14)
    {
        var b = new WpfButton { Style = S("SubtleBtn"), Padding = new Thickness(8, 5, 10, 6), HorizontalContentAlignment = WpfHA.Left };
        b.Content = text.Length == 0 ? Glyph(glyph, 16) : IconLabel(glyph, text, "Text1", size);
        b.Click += (_, _) => click();
        if (text.Length == 0) b.Padding = new Thickness(8);
        b.ToolTip = text.Length == 0 ? null : text;
        return b;
    }

    public static StackPanel IconLabel(string glyph, string text, string brush = "Text1", double size = 14)
    {
        var p = new StackPanel { Orientation = WpfOrientation.Horizontal };
        var g = Glyph(glyph, size + 1, brush);
        g.Margin = new Thickness(0, 1, 8, 0);
        p.Children.Add(g);
        p.Children.Add(Text(text, size, brush));
        return p;
    }

    public static WpfCheckBox Toggle(bool on, Action<bool> changed)
    {
        var c = new WpfCheckBox { Style = S("Toggle"), IsChecked = on, VerticalAlignment = WpfVA.Center };
        c.Checked += (_, _) => { if (!Quiet) changed(true); };
        c.Unchecked += (_, _) => { if (!Quiet) changed(false); };
        return c;
    }

    public static WpfTextBox Input(string text, double width = 260)
    {
        return new WpfTextBox { Style = S("Input"), Text = text, Width = width, FontFamily = Fluent.Font };
    }

    /// <summary>A segmented control (like the Mac's picker): one selected segment on an accent tint.</summary>
    public static Border Segmented(string[] labels, int selected, Action<int> picked)
    {
        var grid = new UniformGrid1(labels.Length);
        string group = Guid.NewGuid().ToString("N");
        for (int i = 0; i < labels.Length; i++)
        {
            int idx = i;
            var r = new WpfRadio { Style = S("Segment"), GroupName = group, IsChecked = i == selected, Content = labels[i] };
            r.Checked += (_, _) => { if (!Quiet) picked(idx); };
            grid.Children.Add(r);
        }
        var b = new Border { CornerRadius = new CornerRadius(6), BorderThickness = new Thickness(1), Child = grid };
        b.SetResourceReference(Border.BackgroundProperty, "Control");
        b.SetResourceReference(Border.BorderBrushProperty, "ControlStroke");
        return b;
    }

    /// <summary>Selects a segment from code without running its action (for live updates).</summary>
    public static bool Quiet;
    public static void SetSegment(Border seg, int index)
    {
        if (seg.Child is not System.Windows.Controls.Primitives.UniformGrid g || index < 0 || index >= g.Children.Count) return;
        if (g.Children[index] is WpfRadio r && r.IsChecked != true) { Quiet = true; r.IsChecked = true; Quiet = false; }
    }

    /// <summary>A Settings row (WinUI SettingsCard): icon, title, description, control on the right.</summary>
    public static Border Row(string glyph, string title, string? desc, UIElement? control, bool first = true, bool last = true)
    {
        var g = new Grid { MinHeight = 44 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(glyph.Length > 0 ? 36 : 0) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        if (glyph.Length > 0)
        {
            var ic = Glyph(glyph, 18);
            ic.HorizontalAlignment = WpfHA.Left;
            g.Children.Add(ic);
        }
        var texts = new StackPanel { VerticalAlignment = WpfVA.Center, Margin = new Thickness(0, 0, 16, 0) };
        texts.Children.Add(Text(title, 14));
        if (!string.IsNullOrEmpty(desc))
        {
            var d = Text(desc, 12, "Text2", wrap: true);
            d.Margin = new Thickness(0, 1, 0, 0);
            texts.Children.Add(d);
        }
        Grid.SetColumn(texts, 1);
        g.Children.Add(texts);
        if (control is FrameworkElement fe)
        {
            fe.VerticalAlignment = WpfVA.Center;
            Grid.SetColumn(fe, 2);
            g.Children.Add(fe);
        }
        var card = Card(g, new Thickness(16, 10, 16, 10));
        card.Margin = new Thickness(0, 0, 0, 4);
        // Narrow: the control moves under the text, so nothing is cut off when the window gets small.
        // The first row takes the card's height, so text and control stay centred; the second is only for narrow cards.
        g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        if (control is FrameworkElement c)
        {
            bool narrow = false;
            card.SizeChanged += (_, e) =>
            {
                bool n = e.NewSize.Width < 400;
                if (n == narrow) return;
                narrow = n;
                Grid.SetRow(c, n ? 1 : 0);
                Grid.SetColumn(c, n ? 1 : 2);
                c.HorizontalAlignment = n ? WpfHA.Left : WpfHA.Stretch;
                c.Margin = n ? new Thickness(0, 10, 0, 2) : new Thickness(0);
            };
        }
        return card;
    }

    public static TextBlock Header(string text)
    {
        var t = Text(text, 14, "Text1", FontWeights.SemiBold);
        t.Margin = new Thickness(2, 22, 0, 8);
        return t;
    }

    /// <summary>A round device badge: the kind's glyph on a soft tile, with a state outline (green here and active,
    /// red-grey away).</summary>
    public static Grid DeviceBadge(string name, string kind, bool live, bool active, double size = 40)
    {
        var g = new Grid { Width = size, Height = size };
        var ring = new Ellipse { StrokeThickness = active || live ? 2 : 1.5 };
        ring.SetResourceReference(Shape.StrokeProperty, live ? "StateOn" : "StateOff");
        ring.SetResourceReference(Shape.FillProperty, active ? "AccentSoft" : "Control");
        g.Children.Add(ring);
        g.Children.Add(Glyph(DeviceGlyph(name, kind), size * 0.42, live ? "Text1" : "Text2"));
        return g;
    }

    public static string DeviceGlyph(string name, string kind)
    {
        string k = kind.Length > 0 ? kind : name;
        if (k.Contains("mac")) return "";            // laptop
        if (k.Contains("tab") || k.Contains("ipad")) return ""; // tablet
        if (k.Contains("phone") || k.Contains("android")) return ""; // phone
        if (k.Contains("pc") || k.Contains("windows")) return ""; // desktop
        return "";
    }
}

/// <summary>Smooth, eased mouse wheel scrolling for a ScrollViewer (WPF jumps by default). A wheel notch adds to a
/// target offset and the view glides to it each frame; dragging the scroll bar resets the target.</summary>
static class SmoothScroll
{
    public static void Attach(ScrollViewer sv)
    {
        double target = 0;
        bool animating = false;
        void Frame(object? s, EventArgs e)
        {
            double cur = sv.VerticalOffset;
            double next = cur + (target - cur) * 0.2;
            if (Math.Abs(target - next) < 0.5) { next = target; System.Windows.Media.CompositionTarget.Rendering -= Frame; animating = false; }
            sv.ScrollToVerticalOffset(next);
        }
        sv.PreviewMouseWheel += (_, e) =>
        {
            // A text box with its own scrolling (the log) keeps the wheel.
            for (var d = e.OriginalSource as DependencyObject; d != null && d != sv; d = System.Windows.Media.VisualTreeHelper.GetParent(d))
                if (d is System.Windows.Controls.TextBox) return;
            if (sv.ScrollableHeight <= 0) return;
            e.Handled = true;
            if (!animating) target = sv.VerticalOffset;
            target = Math.Clamp(target - e.Delta * 0.9, 0, sv.ScrollableHeight);
            if (!animating) { animating = true; System.Windows.Media.CompositionTarget.Rendering += Frame; }
        };
        sv.ScrollChanged += (_, _) => { if (!animating) target = sv.VerticalOffset; };
    }
}

/// <summary>Equal width columns without System.Windows.Controls.Primitives.UniformGrid's row logic.</summary>
sealed class UniformGrid1 : System.Windows.Controls.Primitives.UniformGrid
{
    public UniformGrid1(int columns) { Columns = columns; Rows = 1; }
}

/// <summary>
/// The earbuds picture: Galaxy Buds2 Pro or AirPods Pro, both buds and the case, drawn as vectors in the current light or
/// dark finish. It cross fades and settles with a small spring when the earbuds type changes, floats gently while they
/// are connected here, and dims when they are elsewhere. Original drawings, not taken from any product art.
/// </summary>
sealed class BudsArt : Grid
{
    private readonly Grid stage = new();
    private readonly Ellipse glow = new() { Width = 220, Height = 110, IsHitTestVisible = false };
    private bool? buds;
    private bool here;
    private readonly TranslateTransform floatT = new();

    public BudsArt()
    {
        Height = 128;
        glow.Opacity = 0.0;
        Children.Add(glow);
        stage.RenderTransformOrigin = new WpfPoint(0.5, 0.5);
        Children.Add(stage);
        stage.RenderTransform = new TransformGroup { Children = { new ScaleTransform(1, 1), floatT } };
        Fluent.Changed += Redraw;
        Unloaded += (_, _) => Fluent.Changed -= Redraw;
    }

    private void Redraw() { if (buds is bool b) { buds = null; Show(b, here); } }

    public void Show(bool isBuds, bool connectedHere)
    {
        bool typeChanged = buds != isBuds;
        bool stateChanged = here != connectedHere;
        buds = isBuds;
        here = connectedHere;
        // Called on every refresh: only a real change may start animations, or the flyout stutters.
        if (!typeChanged && !stateChanged) return;
        var accent = Fluent.Accent;
        glow.Fill = new RadialGradientBrush(WpfColor.FromArgb(0x55, accent.R, accent.G, accent.B), WpfColor.FromArgb(0, accent.R, accent.G, accent.B));
        glow.BeginAnimation(OpacityProperty, new DoubleAnimation(here ? 1 : 0.35, TimeSpan.FromMilliseconds(400)));
        if (typeChanged)
        {
            var next = isBuds ? DrawBuds() : DrawAirPods();
            next.Opacity = 0;
            stage.Children.Add(next);
            var ease = new BackEase { Amplitude = 0.4, EasingMode = EasingMode.EaseOut };
            next.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(220)));
            var sc = (ScaleTransform)((TransformGroup)stage.RenderTransform).Children[0];
            sc.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0.86, 1, TimeSpan.FromMilliseconds(420)) { EasingFunction = ease });
            sc.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.86, 1, TimeSpan.FromMilliseconds(420)) { EasingFunction = ease });
            for (int i = stage.Children.Count - 2; i >= 0; i--)
            {
                var old = (UIElement)stage.Children[i];
                var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(160));
                fade.Completed += (_, _) => stage.Children.Remove(old);
                old.BeginAnimation(OpacityProperty, fade);
            }
        }
        if (typeChanged || stateChanged)
        {
            stage.BeginAnimation(OpacityProperty, new DoubleAnimation(here ? 1 : 0.72, TimeSpan.FromMilliseconds(300)));
            if (here)
                floatT.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(-2.5, 2.5, TimeSpan.FromSeconds(2.4))
                { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
            else
                floatT.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(300)));
        }
    }

    // Finish: light grey shell in light mode, graphite in dark mode, like the real colourways.
    private static (Brush body, Brush edge, Brush gloss, Brush shadow) Finish(bool graphite)
    {
        WpfColor top, bottom, edge;
        if (graphite) { top = WpfColor.FromRgb(0x5A, 0x5A, 0x62); bottom = WpfColor.FromRgb(0x2C, 0x2C, 0x32); edge = WpfColor.FromArgb(0x50, 0xFF, 0xFF, 0xFF); }
        else { top = WpfColor.FromRgb(0xFF, 0xFF, 0xFF); bottom = WpfColor.FromRgb(0xDA, 0xDC, 0xE2); edge = WpfColor.FromArgb(0x30, 0, 0, 0); }
        var body = new LinearGradientBrush(top, bottom, new WpfPoint(0.2, 0), new WpfPoint(0.8, 1));
        var gloss = new LinearGradientBrush(WpfColor.FromArgb(0x90, 0xFF, 0xFF, 0xFF), WpfColor.FromArgb(0, 0xFF, 0xFF, 0xFF), new WpfPoint(0, 0), new WpfPoint(0, 1));
        var shadow = new RadialGradientBrush(WpfColor.FromArgb(0x40, 0, 0, 0), WpfColor.FromArgb(0, 0, 0, 0));
        body.Freeze(); gloss.Freeze(); shadow.Freeze();
        return (body, Fluent.Frozen(edge), gloss, shadow);
    }

    private static Canvas Board() => new() { Width = 260, Height = 120, HorizontalAlignment = WpfHA.Center, VerticalAlignment = WpfVA.Center };

    private static void Place(Canvas c, UIElement e, double x, double y) { Canvas.SetLeft(e, x); Canvas.SetTop(e, y); c.Children.Add(e); }

    private static Shape GroundShadow(double w, Brush b) => new Ellipse { Width = w, Height = 10, Fill = b };

    /// <summary>Galaxy Buds2 Pro: bean shaped buds (wider than tall) with a matte touch pad and a soft ear tip, and the
    /// rounded square case with its lid seam.</summary>
    private Canvas DrawBuds()
    {
        bool dark = Fluent.Dark;
        var top = dark ? WpfColor.FromRgb(0x78, 0x78, 0x84) : WpfColor.FromRgb(0xF4, 0xF4, 0xF7);
        var bottom = dark ? WpfColor.FromRgb(0x3C, 0x3C, 0x45) : WpfColor.FromRgb(0xC9, 0xCB, 0xD3);
        var body = new LinearGradientBrush(top, bottom, new WpfPoint(0.3, 0), new WpfPoint(0.7, 1));
        var rim = Fluent.Frozen(dark ? WpfColor.FromArgb(0x60, 0xFF, 0xFF, 0xFF) : WpfColor.FromArgb(0x38, 0, 0, 0));
        var pad = new LinearGradientBrush(dark ? WpfColor.FromRgb(0x2A, 0x2A, 0x31) : WpfColor.FromRgb(0xE2, 0xE3, 0xE8), dark ? WpfColor.FromRgb(0x4A, 0x4A, 0x55) : WpfColor.FromRgb(0xFF, 0xFF, 0xFF), new WpfPoint(0, 0), new WpfPoint(1, 1));
        var shine = new LinearGradientBrush(WpfColor.FromArgb(0x70, 0xFF, 0xFF, 0xFF), WpfColor.FromArgb(0, 0xFF, 0xFF, 0xFF), new WpfPoint(0, 0), new WpfPoint(0, 1));
        var shadow = new RadialGradientBrush(WpfColor.FromArgb(0x48, 0, 0, 0), WpfColor.FromArgb(0, 0, 0, 0));
        var c = Board();
        // case: rounded square with a lid seam and a status light
        Place(c, GroundShadow(80, shadow), 90, 104);
        Place(c, new Rectangle { Width = 74, Height = 68, RadiusX = 24, RadiusY = 24, Fill = body, Stroke = rim, StrokeThickness = 1 }, 93, 36);
        Place(c, new Rectangle { Width = 60, Height = 14, RadiusX = 7, RadiusY = 7, Fill = shine, Opacity = 0.8 }, 100, 40);
        Place(c, new Rectangle { Width = 74, Height = 1.2, Fill = rim }, 93, 61);
        var led = new Ellipse { Width = 5, Height = 5 };
        led.SetResourceReference(Shape.FillProperty, "StateOn");
        Place(c, led, 127.5, 84);
        foreach (var left in new[] { true, false })
        {
            var bud = new Canvas { Width = 74, Height = 60 };
            // shell: a bean, flatter on the outer side
            bud.Children.Add(new Path { Fill = body, Stroke = rim, StrokeThickness = 1, Data = Geometry.Parse("M 36,4 C 56,3 70,14 70,30 C 70,46 57,56 38,56 C 18,56 4,47 4,31 C 4,15 17,5 36,4 Z") });
            // matte touch pad on the outer face
            var touch = new Ellipse { Width = 36, Height = 28, Fill = pad, Stroke = rim, StrokeThickness = 0.8 };
            Canvas.SetLeft(touch, left ? 12 : 26); Canvas.SetTop(touch, 14);
            bud.Children.Add(touch);
            var gl = new Ellipse { Width = 26, Height = 9, Fill = shine };
            Canvas.SetLeft(gl, left ? 17 : 31); Canvas.SetTop(gl, 16);
            bud.Children.Add(gl);
            // ear tip
            var tip = new Ellipse { Width = 20, Height = 20, Stroke = rim, StrokeThickness = 1 };
            tip.SetResourceReference(Shape.FillProperty, "AccentSoft");
            Canvas.SetLeft(tip, left ? 52 : 2); Canvas.SetTop(tip, 32);
            bud.Children.Add(tip);
            var holder = new Canvas { Width = 74, Height = 60, RenderTransformOrigin = new WpfPoint(0.5, 0.5), RenderTransform = new RotateTransform(left ? -12 : 12) };
            holder.Children.Add(bud);
            Place(c, GroundShadow(56, shadow), left ? 14 : 190, 100);
            Place(c, holder, left ? 6 : 180, 36);
        }
        return c;
    }

    /// <summary>AirPods Pro: round heads with a short stem and silicone tip, and the wide case with its seam.</summary>
    private Canvas DrawAirPods()
    {
        var (body, edge, gloss, shadow) = Finish(false);
        var c = Board();
        // case
        Place(c, GroundShadow(92, shadow), 84, 104);
        var caseBody = new Rectangle { Width = 88, Height = 62, RadiusX = 20, RadiusY = 20, Fill = body, Stroke = edge, StrokeThickness = 1 };
        Place(c, caseBody, 86, 42);
        Place(c, new Rectangle { Width = 88, Height = 1.2, Fill = edge }, 86, 58);
        var led = new Ellipse { Width = 5, Height = 5 };
        led.SetResourceReference(Shape.FillProperty, "StateOn");
        Place(c, led, 127.5, 76);
        foreach (var left in new[] { true, false })
        {
            var pod = new Canvas { Width = 50, Height = 84 };
            var head = new Path { Fill = body, Stroke = edge, StrokeThickness = 1, Data = Geometry.Parse("M 24,4 C 38,4 46,13 46,24 C 46,34 40,40 32,42 L 32,78 C 32,82 29,84 26,84 C 23,84 20,82 20,78 L 20,44 C 10,42 4,34 4,24 C 4,12 12,4 24,4 Z") };
            pod.Children.Add(head);
            var shine = new Ellipse { Width = 22, Height = 14, Fill = gloss };
            Canvas.SetLeft(shine, 12); Canvas.SetTop(shine, 8);
            pod.Children.Add(shine);
            var tip = new Ellipse { Width = 16, Height = 16, Stroke = edge, StrokeThickness = 1 };
            tip.SetResourceReference(Shape.FillProperty, "AccentSoft");
            Canvas.SetLeft(tip, left ? 32 : 2); Canvas.SetTop(tip, 18);
            pod.Children.Add(tip);
            var grill = new Rectangle { Width = 6, Height = 3, RadiusX = 1.5, RadiusY = 1.5, Fill = edge };
            Canvas.SetLeft(grill, 23); Canvas.SetTop(grill, 76);
            pod.Children.Add(grill);
            if (!left) pod.RenderTransform = new ScaleTransform(-1, 1, 25, 0);
            var holder = new Canvas { Width = 50, Height = 84, RenderTransformOrigin = new WpfPoint(0.5, 0.5), RenderTransform = new RotateTransform(left ? -10 : 10) };
            holder.Children.Add(pod);
            Place(c, GroundShadow(34, shadow), left ? 30 : 196, 104);
            Place(c, holder, left ? 22 : 188, 18);
        }
        return c;
    }
}
