using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media;
using Microsoft.Win32;
using WpfApp = System.Windows.Application;
using WpfColor = System.Windows.Media.Color;

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
/// Fluent Design 2 for the WPF windows: the Windows 11 colour tokens for light and dark (follows the system setting),
/// the EarHop accent (AirPods blue, Galaxy Buds violet), Mica and Acrylic backdrops, and WinUI style controls.
/// State colours (green on, red-grey off) are only used on outlines; the accent only on backgrounds.
/// </summary>
static class Fluent
{
    public static readonly WpfColor AirPodsBlue = WpfColor.FromRgb(0x4D, 0x9E, 0xFF);
    public static readonly WpfColor BudsViolet = WpfColor.FromRgb(0x94, 0x66, 0xFF);
    public static readonly WpfColor StateOn = WpfColor.FromRgb(0x30, 0xD1, 0x58);
    public static readonly WpfColor StateOff = WpfColor.FromRgb(0xA0, 0x78, 0x78);
    public static readonly WpfColor Warning = WpfColor.FromRgb(0xFF, 0x9F, 0x0A);
    public static readonly WpfColor Danger = WpfColor.FromRgb(0xFF, 0x45, 0x3A);

    public static bool Dark { get; private set; } = true;
    public static WpfColor Accent { get; private set; } = BudsViolet;
    public static event Action? Changed;

    /// <summary>Windows 11 22H2 and later draw Mica and Acrylic behind WPF windows.</summary>
    public static bool HasBackdrop => Environment.OSVersion.Version.Build >= 22621;

    private static bool started;

    public static void Start(bool buds)
    {
        if (WpfApp.Current == null) new WpfApp { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        if (!started)
        {
            started = true;
            WpfApp.Current!.Resources.MergedDictionaries.Add(Styles());
            SystemEvents.UserPreferenceChanged += (_, e) =>
            {
                if (e.Category is UserPreferenceCategory.General or UserPreferenceCategory.Color or UserPreferenceCategory.VisualStyle)
                    WpfApp.Current?.Dispatcher.BeginInvoke(() => Apply(Accent == BudsViolet));
            };
        }
        Apply(buds);
    }

    /// <summary>Sets the light or dark tokens and the accent for the chosen earbuds.</summary>
    public static void Apply(bool buds)
    {
        // EarHop is always black (like the Android app's dark look), whatever the Windows mode.
        Dark = true;
        Accent = buds ? BudsViolet : AirPodsBlue;
        var r = WpfApp.Current!.Resources;
        void Set(string key, WpfColor c) { r[key] = Frozen(c); r[key + "Color"] = c; }
        WpfColor A(byte a, WpfColor c) => WpfColor.FromArgb(a, c.R, c.G, c.B);
        var white = Colors.White;
        var black = Colors.Black;
        if (Dark)
        {
            Set("Text1", white);
            Set("Text2", A(0xC8, white));
            Set("Text3", A(0x8B, white));
            Set("Base", black);
            Set("FlyoutBase", black);
            Set("Card", WpfColor.FromRgb(0x14, 0x14, 0x16));
            Set("CardSolid", WpfColor.FromRgb(0x14, 0x14, 0x16));
            Set("CardStroke", WpfColor.FromRgb(0x26, 0x26, 0x2A));
            Set("CardStrokeHi", WpfColor.FromRgb(0x30, 0x30, 0x34));
            Set("Control", WpfColor.FromRgb(0x22, 0x22, 0x26));
            Set("ControlHover", WpfColor.FromRgb(0x2C, 0x2C, 0x31));
            Set("ControlPressed", WpfColor.FromRgb(0x1C, 0x1C, 0x20));
            Set("ControlStroke", WpfColor.FromRgb(0x30, 0x30, 0x35));
            Set("ControlStrokeBottom", WpfColor.FromRgb(0x3A, 0x3A, 0x40));
            Set("Subtle", Colors.Transparent);
            Set("SubtleHover", WpfColor.FromRgb(0x1C, 0x1C, 0x20));
            Set("SubtlePressed", WpfColor.FromRgb(0x16, 0x16, 0x19));
            Set("Divider", WpfColor.FromRgb(0x22, 0x22, 0x26));
            Set("InputFill", WpfColor.FromRgb(0x18, 0x18, 0x1B));
            Set("InputFocus", WpfColor.FromRgb(0x0C, 0x0C, 0x0E));
            Set("KnobOff", A(0xC8, white));
            Set("TrackOffStroke", A(0x9E, white));
            Set("SelectedNav", WpfColor.FromRgb(0x1C, 0x1C, 0x20));
            Set("AccentSoft", A(0x33, Accent));
            Set("AccentText", Lighten(Accent, 0.35));
        }
        else
        {
            Set("Text1", A(0xE4, black));
            Set("Text2", A(0x9E, black));
            Set("Text3", A(0x72, black));
            Set("Base", WpfColor.FromRgb(0xF3, 0xF3, 0xF3));
            Set("FlyoutBase", WpfColor.FromRgb(0xF9, 0xF9, 0xF9));
            Set("Card", A(0xB3, white));
            Set("CardSolid", WpfColor.FromRgb(0xFB, 0xFB, 0xFB));
            Set("CardStroke", A(0x0F, black));
            Set("CardStrokeHi", A(0x0F, black));
            Set("Control", A(0xB3, white));
            Set("ControlHover", A(0x80, WpfColor.FromRgb(0xF9, 0xF9, 0xF9)));
            Set("ControlPressed", A(0x4D, WpfColor.FromRgb(0xF9, 0xF9, 0xF9)));
            Set("ControlStroke", A(0x0F, black));
            Set("ControlStrokeBottom", A(0x29, black));
            Set("Subtle", Colors.Transparent);
            Set("SubtleHover", A(0x09, black));
            Set("SubtlePressed", A(0x06, black));
            Set("Divider", A(0x14, black));
            Set("InputFill", A(0xB3, white));
            Set("InputFocus", white);
            Set("KnobOff", A(0x9E, black));
            Set("TrackOffStroke", A(0x9E, black));
            Set("SelectedNav", A(0x09, black));
            Set("AccentSoft", A(0x26, Accent));
            Set("AccentText", Darken(Accent, 0.25));
        }
        Set("Accent", Accent);
        Set("AccentHover", A(0xE6, Accent));
        Set("AccentPressed", A(0xCC, Accent));
        Set("OnAccent", white);
        Set("StateOn", StateOn);
        Set("StateOff", StateOff);
        Set("Warning", Warning);
        Set("Danger", Danger);
        Changed?.Invoke();
    }

    public static Brush B(string key) => (Brush)WpfApp.Current!.Resources[key];
    public static WpfColor C(string key) => (WpfColor)WpfApp.Current!.Resources[key + "Color"];

    public static SolidColorBrush Frozen(WpfColor c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }
    public static WpfColor Lighten(WpfColor c, double t) => WpfColor.FromRgb((byte)(c.R + (255 - c.R) * t), (byte)(c.G + (255 - c.G) * t), (byte)(c.B + (255 - c.B) * t));
    public static WpfColor Darken(WpfColor c, double t) => WpfColor.FromRgb((byte)(c.R * (1 - t)), (byte)(c.G * (1 - t)), (byte)(c.B * (1 - t)));

    public static bool SystemDark()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("AppsUseLightTheme") is int v ? v == 0 : true;
        }
        catch { return true; }
    }

    public static readonly System.Windows.Media.FontFamily Font = PickFont("Segoe UI Variable Text", "Segoe UI");
    public static readonly System.Windows.Media.FontFamily Display = PickFont("Segoe UI Variable Display", "Segoe UI");
    public static readonly System.Windows.Media.FontFamily Icons = PickFont("Segoe Fluent Icons", "Segoe MDL2 Assets");

    private static System.Windows.Media.FontFamily PickFont(params string[] names)
    {
        var have = Fonts.SystemFontFamilies.Select(f => f.Source).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var n in names) if (have.Contains(n)) return new System.Windows.Media.FontFamily(n);
        return new System.Windows.Media.FontFamily(names[^1]);
    }

    // MARK: backdrop

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    [DllImport("dwmapi.dll")] private static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins m);
    [StructLayout(LayoutKind.Sequential)] private struct Margins { public int L, R, T, B; }

    /// <summary>
    /// Mica (main windows) or Acrylic (flyouts) behind the window on Windows 11, with the title bar in the same light or
    /// dark mode. Older Windows gets the solid Fluent base colour instead.
    /// </summary>
    public static void Backdrop(Window w, bool acrylic)
    {
        w.UseLayoutRounding = true;
        TextOptions.SetTextFormattingMode(w, TextFormattingMode.Ideal);
        TextOptions.SetTextRenderingMode(w, TextRenderingMode.ClearType);
        void Paint()
        {
            var hwnd = new WindowInteropHelper(w).Handle;
            if (hwnd == IntPtr.Zero) return;
            int dark = Dark ? 1 : 0;
            DwmSetWindowAttribute(hwnd, 20, ref dark, sizeof(int));
            int round = 2;
            DwmSetWindowAttribute(hwnd, 33, ref round, sizeof(int));          // rounded corners (Windows 11)
            int caption = 0;                                                   // black title bar (COLORREF black)
            DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int));
            int border = 0x00302C2C;                                           // hairline window border
            DwmSetWindowAttribute(hwnd, 34, ref border, sizeof(int));
            w.SetResourceReference(Window.BackgroundProperty, acrylic ? "FlyoutBase" : "Base");
        }
        if (new WindowInteropHelper(w).Handle != IntPtr.Zero) Paint();
        else w.SourceInitialized += (_, _) => Paint();
        Action onChange = () => Paint();
        Changed += onChange;
        w.Closed += (_, _) => Changed -= onChange;
    }

    // MARK: control styles (WinUI 3 look), parsed once

    private static ResourceDictionary Styles() => (ResourceDictionary)XamlReader.Parse(Xaml);

    private const string Xaml = """
<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">

  <!-- Standard button -->
  <Style x:Key="Btn" TargetType="Button">
    <Setter Property="Foreground" Value="{DynamicResource Text1}"/>
    <Setter Property="Background" Value="{DynamicResource Control}"/>
    <Setter Property="BorderBrush" Value="{DynamicResource ControlStroke}"/>
    <Setter Property="FontSize" Value="14"/>
    <Setter Property="Padding" Value="12,5,12,6"/>
    <Setter Property="MinHeight" Value="32"/>
    <Setter Property="Cursor" Value="Hand"/>
    <Setter Property="FocusVisualStyle" Value="{x:Null}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="Button">
          <Border x:Name="b" CornerRadius="4" Background="{TemplateBinding Background}" BorderThickness="1">
            <Border.BorderBrush>
              <LinearGradientBrush StartPoint="0,0" EndPoint="0,1">
                <GradientStop Offset="0.9" Color="{DynamicResource ControlStrokeColor}"/>
                <GradientStop Offset="1" Color="{DynamicResource ControlStrokeBottomColor}"/>
              </LinearGradientBrush>
            </Border.BorderBrush>
            <ContentPresenter x:Name="c" Margin="{TemplateBinding Padding}" HorizontalAlignment="Center" VerticalAlignment="Center"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource ControlHover}"/></Trigger>
            <Trigger Property="IsPressed" Value="True">
              <Setter TargetName="b" Property="Background" Value="{DynamicResource ControlPressed}"/>
              <Setter TargetName="c" Property="Opacity" Value="0.8"/>
            </Trigger>
            <Trigger Property="IsEnabled" Value="False"><Setter TargetName="c" Property="Opacity" Value="0.4"/></Trigger>
            <Trigger Property="IsKeyboardFocused" Value="True"><Setter TargetName="b" Property="BorderBrush" Value="{DynamicResource Text1}"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Accent button -->
  <Style x:Key="AccentBtn" TargetType="Button" BasedOn="{StaticResource Btn}">
    <Setter Property="Foreground" Value="{DynamicResource OnAccent}"/>
    <Setter Property="Background" Value="{DynamicResource Accent}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="Button">
          <Border x:Name="b" CornerRadius="4" Background="{TemplateBinding Background}" BorderThickness="1">
            <Border.BorderBrush>
              <LinearGradientBrush StartPoint="0,0" EndPoint="0,1">
                <GradientStop Offset="0.9" Color="#14FFFFFF"/>
                <GradientStop Offset="1" Color="#40000000"/>
              </LinearGradientBrush>
            </Border.BorderBrush>
            <ContentPresenter x:Name="c" Margin="{TemplateBinding Padding}" HorizontalAlignment="Center" VerticalAlignment="Center"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource AccentHover}"/></Trigger>
            <Trigger Property="IsPressed" Value="True">
              <Setter TargetName="b" Property="Background" Value="{DynamicResource AccentPressed}"/>
              <Setter TargetName="c" Property="Opacity" Value="0.85"/>
            </Trigger>
            <Trigger Property="IsEnabled" Value="False"><Setter TargetName="b" Property="Opacity" Value="0.4"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Subtle (transparent) button, for toolbars and icon buttons -->
  <Style x:Key="SubtleBtn" TargetType="Button" BasedOn="{StaticResource Btn}">
    <Setter Property="Background" Value="{DynamicResource Subtle}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="Button">
          <Border x:Name="b" CornerRadius="4" Background="{TemplateBinding Background}">
            <ContentPresenter x:Name="c" Margin="{TemplateBinding Padding}" HorizontalAlignment="{TemplateBinding HorizontalContentAlignment}" VerticalAlignment="Center"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource SubtleHover}"/></Trigger>
            <Trigger Property="IsPressed" Value="True">
              <Setter TargetName="b" Property="Background" Value="{DynamicResource SubtlePressed}"/>
              <Setter TargetName="c" Property="Opacity" Value="0.75"/>
            </Trigger>
            <Trigger Property="IsEnabled" Value="False"><Setter TargetName="c" Property="Opacity" Value="0.4"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- ToggleSwitch (WinUI): a CheckBox with a pill track and a knob that grows on hover -->
  <Style x:Key="Toggle" TargetType="CheckBox">
    <Setter Property="Cursor" Value="Hand"/>
    <Setter Property="FocusVisualStyle" Value="{x:Null}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="CheckBox">
          <Grid Background="Transparent" Width="40" Height="20">
            <Border x:Name="track" CornerRadius="10" BorderThickness="1" BorderBrush="{DynamicResource TrackOffStroke}" Background="{DynamicResource Control}"/>
            <Ellipse x:Name="knob" Width="12" Height="12" HorizontalAlignment="Left" Margin="4,0,0,0" Fill="{DynamicResource KnobOff}">
              <Ellipse.RenderTransform><TranslateTransform x:Name="tx" X="0"/></Ellipse.RenderTransform>
            </Ellipse>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property="IsChecked" Value="True">
              <Setter TargetName="track" Property="Background" Value="{DynamicResource Accent}"/>
              <Setter TargetName="track" Property="BorderBrush" Value="{DynamicResource Accent}"/>
              <Setter TargetName="knob" Property="Fill" Value="{DynamicResource OnAccent}"/>
              <Trigger.EnterActions>
                <BeginStoryboard><Storyboard><DoubleAnimation Storyboard.TargetName="tx" Storyboard.TargetProperty="X" To="20" Duration="0:0:0.167"><DoubleAnimation.EasingFunction><CubicEase EasingMode="EaseOut"/></DoubleAnimation.EasingFunction></DoubleAnimation></Storyboard></BeginStoryboard>
              </Trigger.EnterActions>
              <Trigger.ExitActions>
                <BeginStoryboard><Storyboard><DoubleAnimation Storyboard.TargetName="tx" Storyboard.TargetProperty="X" To="0" Duration="0:0:0.167"><DoubleAnimation.EasingFunction><CubicEase EasingMode="EaseOut"/></DoubleAnimation.EasingFunction></DoubleAnimation></Storyboard></BeginStoryboard>
              </Trigger.ExitActions>
            </Trigger>
            <Trigger Property="IsMouseOver" Value="True">
              <Setter TargetName="knob" Property="Width" Value="14"/>
              <Setter TargetName="knob" Property="Height" Value="14"/>
              <Setter TargetName="knob" Property="Margin" Value="3,0,0,0"/>
            </Trigger>
            <Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.4"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Segment (a RadioButton in a segmented control) -->
  <Style x:Key="Segment" TargetType="RadioButton">
    <Setter Property="Foreground" Value="{DynamicResource Text2}"/>
    <Setter Property="FontSize" Value="13"/>
    <Setter Property="Cursor" Value="Hand"/>
    <Setter Property="FocusVisualStyle" Value="{x:Null}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="RadioButton">
          <Border x:Name="b" CornerRadius="4" Background="Transparent" Padding="10,4,10,5" Margin="2">
            <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource SubtleHover}"/></Trigger>
            <Trigger Property="IsChecked" Value="True">
              <Setter TargetName="b" Property="Background" Value="{DynamicResource AccentSoft}"/>
              <Setter Property="Foreground" Value="{DynamicResource Text1}"/>
              <Setter Property="FontWeight" Value="SemiBold"/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Navigation item in the Settings pane -->
  <Style x:Key="Nav" TargetType="RadioButton">
    <Setter Property="Foreground" Value="{DynamicResource Text1}"/>
    <Setter Property="FontSize" Value="14"/>
    <Setter Property="Cursor" Value="Hand"/>
    <Setter Property="FocusVisualStyle" Value="{x:Null}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="RadioButton">
          <Grid Margin="0,1" Height="36">
            <Border x:Name="b" CornerRadius="4" Background="Transparent"/>
            <Border x:Name="pill" Width="3" Height="16" CornerRadius="1.5" HorizontalAlignment="Left" Background="{DynamicResource Accent}" Visibility="Collapsed"/>
            <ContentPresenter Margin="12,0,8,0" VerticalAlignment="Center"/>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource SubtleHover}"/></Trigger>
            <Trigger Property="IsChecked" Value="True">
              <Setter TargetName="b" Property="Background" Value="{DynamicResource SelectedNav}"/>
              <Setter TargetName="pill" Property="Visibility" Value="Visible"/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- TextBox with the WinUI accent underline on focus -->
  <Style x:Key="Input" TargetType="TextBox">
    <Setter Property="Foreground" Value="{DynamicResource Text1}"/>
    <Setter Property="CaretBrush" Value="{DynamicResource Text1}"/>
    <Setter Property="SelectionBrush" Value="{DynamicResource Accent}"/>
    <Setter Property="FontSize" Value="14"/>
    <Setter Property="MinHeight" Value="32"/>
    <Setter Property="Padding" Value="10,5,6,6"/>
    <Setter Property="VerticalContentAlignment" Value="Center"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="TextBox">
          <Grid>
            <Border x:Name="b" CornerRadius="4" Background="{DynamicResource InputFill}" BorderBrush="{DynamicResource ControlStroke}" BorderThickness="1"/>
            <Border x:Name="line" CornerRadius="0,0,4,4" BorderBrush="{DynamicResource Text3}" BorderThickness="0,0,0,1" Margin="1,0"/>
            <ScrollViewer x:Name="PART_ContentHost" Margin="{TemplateBinding Padding}" VerticalAlignment="Center"/>
          </Grid>
          <ControlTemplate.Triggers>
            <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource ControlHover}"/></Trigger>
            <Trigger Property="IsKeyboardFocused" Value="True">
              <Setter TargetName="b" Property="Background" Value="{DynamicResource InputFocus}"/>
              <Setter TargetName="line" Property="BorderBrush" Value="{DynamicResource Accent}"/>
              <Setter TargetName="line" Property="BorderThickness" Value="0,0,0,2"/>
            </Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Thin overlay scroll bar -->
  <Style TargetType="ScrollBar">
    <Setter Property="Width" Value="6"/>
    <Setter Property="MinWidth" Value="6"/>
    <Setter Property="Background" Value="Transparent"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="ScrollBar">
          <Track x:Name="PART_Track" IsDirectionReversed="True">
            <Track.Thumb>
              <Thumb>
                <Thumb.Template>
                  <ControlTemplate TargetType="Thumb">
                    <Border CornerRadius="3" Background="{DynamicResource Text3}" Margin="1,2"/>
                  </ControlTemplate>
                </Thumb.Template>
              </Thumb>
            </Track.Thumb>
          </Track>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>

  <!-- Context menus in the Fluent look -->
  <Style TargetType="ContextMenu">
    <Setter Property="Background" Value="{DynamicResource FlyoutBase}"/>
    <Setter Property="Foreground" Value="{DynamicResource Text1}"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="ContextMenu">
          <Border Background="{DynamicResource FlyoutBase}" BorderBrush="{DynamicResource CardStrokeHi}" BorderThickness="1" CornerRadius="8" Padding="4">
            <StackPanel IsItemsHost="True"/>
          </Border>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
  <Style TargetType="MenuItem">
    <Setter Property="Foreground" Value="{DynamicResource Text1}"/>
    <Setter Property="FontSize" Value="14"/>
    <Setter Property="Template">
      <Setter.Value>
        <ControlTemplate TargetType="MenuItem">
          <Border x:Name="b" CornerRadius="4" Padding="12,7,24,8" Background="Transparent" MinWidth="160">
            <ContentPresenter ContentSource="Header"/>
          </Border>
          <ControlTemplate.Triggers>
            <Trigger Property="IsHighlighted" Value="True"><Setter TargetName="b" Property="Background" Value="{DynamicResource SubtleHover}"/></Trigger>
            <Trigger Property="IsEnabled" Value="False"><Setter Property="Foreground" Value="{DynamicResource Text3}"/></Trigger>
          </ControlTemplate.Triggers>
        </ControlTemplate>
      </Setter.Value>
    </Setter>
  </Style>
</ResourceDictionary>
""";
}
