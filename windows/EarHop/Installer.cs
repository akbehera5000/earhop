using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using EarHop.Win.UI;
using Microsoft.Win32;
using WpfHA = System.Windows.HorizontalAlignment;
using WpfOrientation = System.Windows.Controls.Orientation;
using WpfVA = System.Windows.VerticalAlignment;
using Image = System.Windows.Controls.Image;
using MessageBox = System.Windows.Forms.MessageBox;

namespace EarHop.Win;

/// <summary>
/// EarHop installs itself: the same exe, named EarHop-Setup-x.y.z.exe, shows a small Fluent setup window. Per user, no
/// administrator rights and no certificate: the app goes to %LOCALAPPDATA%\Programs\EarHop, gets a Start menu entry
/// (and a desktop one if wanted) and an entry in Settings, Apps, Installed apps, which runs "EarHop.exe --uninstall".
/// Settings in %APPDATA%\EarHop stay unless the owner removes them while uninstalling. EarHop never touches the firewall:
/// Windows asks once for the installed EarHop.exe.
/// </summary>
static class Installer
{
    public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "EarHop");
    public static string Exe => Path.Combine(Dir, "EarHop.exe");
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\EarHop";
    private static string StartMenuLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "EarHop.lnk");
    private static string DesktopLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "EarHop.lnk");

    /// <summary>True when this exe is the setup (its name starts with "EarHop-Setup", or --install was given).</summary>
    public static bool IsSetup(string[] args)
    {
        if (args.Contains("--install")) return true;
        var name = Path.GetFileNameWithoutExtension(Environment.ProcessPath ?? "");
        return name.StartsWith("EarHop-Setup", StringComparison.OrdinalIgnoreCase)
               && !string.Equals(Environment.ProcessPath, Exe, StringComparison.OrdinalIgnoreCase);
    }

    // MARK: setup window

    public static void RunSetup()
    {
        var app = new System.Windows.Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
        Fluent.Start(true);
        bool installed = File.Exists(Exe);
        string? oldVersion = installed ? FileVersionInfo.GetVersionInfo(Exe).ProductVersion?.Split('+')[0] : null;

        var w = new Window
        {
            Title = "EarHop Setup", Width = 480, SizeToContent = SizeToContent.Height, ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterScreen, FontFamily = Fluent.Font, Icon = SettingsWindow.AppIconImage(),
        };
        Fluent.Backdrop(w, acrylic: false);
        var stack = new StackPanel { Margin = new Thickness(28, 24, 28, 24) };
        var head = new StackPanel { Orientation = WpfOrientation.Horizontal, Margin = new Thickness(0, 0, 0, 16) };
        var logo = new Image { Source = SettingsWindow.AppLogo(), Width = 56, Height = 56, Margin = new Thickness(0, 0, 16, 0) };
        System.Windows.Media.RenderOptions.SetBitmapScalingMode(logo, System.Windows.Media.BitmapScalingMode.HighQuality);
        head.Children.Add(logo);
        var titles = new StackPanel { VerticalAlignment = WpfVA.Center };
        titles.Children.Add(Kit.Text("EarHop", 24, "Text1", FontWeights.SemiBold));
        titles.Children.Add(Kit.Text($"Version {TrayApp.Version} for Windows", 13, "Text2"));
        head.Children.Add(titles);
        stack.Children.Add(head);
        bool same = installed && oldVersion == TrayApp.Version;
        stack.Children.Add(Kit.Text(same ? $"EarHop {oldVersion} is already installed. Install it again to repair it. Your devices, secret and settings stay."
            : installed ? $"EarHop {oldVersion} is installed. This updates it to {TrayApp.Version}. Your devices, secret and settings stay."
            : "Your earbuds follow whichever device starts playing, and the clipboard travels between your devices.", 13, "Text2", wrap: true));
        var startup = Kit.Toggle(true, _ => { });
        var desktop = Kit.Toggle(false, _ => { });
        var opts = new StackPanel { Margin = new Thickness(0, 18, 0, 0) };
        opts.Children.Add(Kit.Row("", "Start EarHop when I sign in", null, startup));
        opts.Children.Add(Kit.Row("", "Shortcut on the desktop", null, desktop));
        stack.Children.Add(opts);
        var where = Kit.Text($"Installs for you only, in {Dir}. No administrator rights needed.", 12, "Text3", wrap: true);
        where.Margin = new Thickness(2, 10, 0, 0);
        stack.Children.Add(where);
        var status = Kit.Text("", 12, "Text2", wrap: true);
        status.Margin = new Thickness(2, 12, 0, 0);
        stack.Children.Add(status);
        var buttons = new StackPanel { Orientation = WpfOrientation.Horizontal, HorizontalAlignment = WpfHA.Right, Margin = new Thickness(0, 18, 0, 0) };
        var cancel = Kit.Button("Cancel", () => w.Close());
        var install = Kit.Button(same ? "Reinstall" : installed ? "Update" : "Install", () => { }, accent: true);
        install.MinWidth = 110;
        install.Margin = new Thickness(8, 0, 0, 0);
        buttons.Children.Add(cancel);
        buttons.Children.Add(install);
        stack.Children.Add(buttons);
        w.Content = stack;

        install.Click += async (_, _) =>
        {
            install.IsEnabled = cancel.IsEnabled = false;
            status.Text = "Installing...";
            bool start = startup.IsChecked == true, desk = desktop.IsChecked == true;
            bool paired = File.Exists(Config.FilePath) && Config.Load().Secret.Length > 0;
            string? error = await Task.Run(() => Install(start, desk));
            if (error != null)
            {
                status.SetResourceReference(TextBlock.ForegroundProperty, "Danger");
                status.Text = error;
                install.IsEnabled = cancel.IsEnabled = true;
                return;
            }
            status.Text = "Done. EarHop is starting; find it in the notification area next to the clock. If Windows asks, allow EarHop on private networks.";
            try { Process.Start(new ProcessStartInfo(Exe, paired ? "--flyout" : "--settings=Pairing") { UseShellExecute = false }); } catch { }
            install.Content = Kit.Text("Close", 14, "OnAccent");
            install.IsEnabled = true;
            install.Click += (_, _) => w.Close();
        };
        app.Run(w);
    }

    /// <summary>Copies the app, writes the shortcuts and the Installed apps entry. Returns an error text, or null.</summary>
    private static string? Install(bool startAtLogin, bool desktopShortcut)
    {
        try
        {
            StopRunning();
            Directory.CreateDirectory(Dir);
            var src = Environment.ProcessPath!;
            // A running old copy can hold the file for a moment after it was asked to stop.
            for (int i = 0; ; i++)
            {
                try { File.Copy(src, Exe, true); break; }
                catch (IOException) when (i < 20) { Thread.Sleep(250); }
            }
            Shortcut(StartMenuLink, "EarHop: your earbuds and clipboard follow you between devices");
            if (desktopShortcut) Shortcut(DesktopLink, "EarHop"); else TryDelete(DesktopLink);

            using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", "EarHop");
                k.SetValue("DisplayVersion", TrayApp.Version);
                k.SetValue("Publisher", "EarHop");
                k.SetValue("DisplayIcon", $"{Exe},0");
                k.SetValue("InstallLocation", Dir);
                k.SetValue("UninstallString", $"\"{Exe}\" --uninstall");
                k.SetValue("QuietUninstallString", $"\"{Exe}\" --uninstall --quiet");
                k.SetValue("EstimatedSize", (int)(new FileInfo(Exe).Length / 1024), RegistryValueKind.DWord);
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
            }

            // Start at login follows the app's own setting; the installed app writes the Run key with its own path.
            var cfg = Config.Load();
            cfg.StartAtLogin = startAtLogin;
            cfg.Save();
            Startup.Apply(false); // the Run key is written again by the installed EarHop, pointing at it
            Log.Write($"setup: installed {TrayApp.Version} in {Dir}");
            return null;
        }
        catch (Exception e)
        {
            Log.Write($"setup: failed: {e}");
            return $"Could not install: {e.Message}";
        }
    }

    // MARK: uninstall

    public static void Uninstall(bool quiet)
    {
        bool removeSettings = false;
        if (!quiet)
        {
            var r = MessageBox.Show("Remove EarHop from this PC?\n\nYour other devices keep working. Choose Yes to uninstall.", "Uninstall EarHop", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r != DialogResult.Yes) return;
            removeSettings = MessageBox.Show("Also remove EarHop's settings and log (this PC's name and the pairing secret)?\n\nChoose No to keep them for a later install.",
                                             "Uninstall EarHop", MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) == DialogResult.Yes;
        }
        StopRunning();
        Startup.Apply(false);
        TryDelete(StartMenuLink);
        TryDelete(DesktopLink);
        TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "EarHop (send to a device).lnk"));
        try { Registry.CurrentUser.DeleteSubKey(UninstallKey, false); } catch { }
        if (removeSettings) { try { Directory.Delete(Config.Dir, true); } catch { } }
        // The running exe cannot delete itself: a hidden command removes the folder a moment after this exits.
        try
        {
            Process.Start(new ProcessStartInfo("cmd.exe", $"/c ping -n 3 127.0.0.1 >nul & rmdir /s /q \"{Dir}\"")
            { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
        }
        catch { }
        if (!quiet) MessageBox.Show("EarHop was removed.", "Uninstall EarHop", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    // MARK: helpers

    /// <summary>Stops every other running EarHop (an update must replace the exe).</summary>
    private static void StopRunning()
    {
        int self = Environment.ProcessId;
        foreach (var p in Process.GetProcessesByName("EarHop"))
        {
            try { if (p.Id != self) { p.Kill(); p.WaitForExit(5000); } } catch { }
        }
    }

    private static void Shortcut(string path, string description)
    {
        var t = Type.GetTypeFromProgID("WScript.Shell");
        if (t == null) return;
        dynamic shell = Activator.CreateInstance(t)!;
        dynamic sc = shell.CreateShortcut(path);
        sc.TargetPath = Exe;
        sc.WorkingDirectory = Dir;
        sc.IconLocation = Exe + ",0";
        sc.Description = description;
        sc.Save();
    }

    private static void TryDelete(string path) { try { if (File.Exists(path)) File.Delete(path); } catch { } }
}
