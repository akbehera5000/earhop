using System.IO.Pipes;
using System.Text;
using Microsoft.Win32;

namespace EarHop.Win;

static class Program
{
    private const string MutexName = "Local\\EarHop-single-instance";

    [STAThread]
    static void Main(string[] args)
    {
        var files = args.Where(a => !a.StartsWith("--")).ToList();
        using var single = new Mutex(true, MutexName, out bool first);
        if (!first)
        {
            // EarHop already runs: hand it the files (Explorer's Send to menu), or just stop.
            if (files.Count > 0) PipeServer.SendToRunning(files);
            return;
        }
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
        Application.ThreadException += (_, e) => Log.Write($"crash (UI): {e.Exception}");
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write($"crash: {e.ExceptionObject}");
        var app = new TrayApp(Config.Load());
        if (files.Count > 0) SynchronizationContext.Current?.Post(_ => PipeServer.Deliver(files), null);
        Application.Run(app);
        // A new name or secret: start again once this instance has let go of the single-instance lock.
        if (RestartRequested && Environment.ProcessPath is string exe)
        {
            single.ReleaseMutex();
            single.Dispose();
            System.Diagnostics.Process.Start(exe, "--background");
        }
    }

    public static bool RestartRequested;
}

/// <summary>Files from a second EarHop start (Explorer's Send to, EarHop.exe file.txt) reach the running EarHop through a
/// named pipe that only this user can open.</summary>
static class PipeServer
{
    private static string Name => "EarHop-send-" + Environment.UserName;
    private static Action<List<string>>? handler;

    public static void Start(Action<List<string>> onFiles)
    {
        handler = onFiles;
        var t = new Thread(() =>
        {
            while (true)
            {
                try
                {
                    using var s = new NamedPipeServerStream(Name, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.CurrentUserOnly);
                    s.WaitForConnection();
                    using var r = new StreamReader(s, Encoding.UTF8);
                    var list = r.ReadToEnd().Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
                    if (list.Count > 0) onFiles(list);
                }
                catch (Exception e) { Log.Write($"pipe: {e.Message}"); Thread.Sleep(1000); }
            }
        }) { IsBackground = true, Name = "earhop-pipe" };
        t.Start();
    }

    /// <summary>Files given on the command line of the first start.</summary>
    public static void Deliver(List<string> files) => handler?.Invoke(files);

    public static void SendToRunning(List<string> files)
    {
        try
        {
            using var c = new NamedPipeClientStream(".", Name, PipeDirection.Out, PipeOptions.CurrentUserOnly);
            c.Connect(3000);
            var b = Encoding.UTF8.GetBytes(string.Join("\n", files.Select(Path.GetFullPath)));
            c.Write(b);
        }
        catch (Exception) { }
    }
}

/// <summary>Start at login: the user's own Run key (no admin rights).</summary>
static class Startup
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public static void Apply(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(Key, true);
            if (k == null) return;
            if (on && Environment.ProcessPath is string exe) k.SetValue("EarHop", $"\"{exe}\" --background");
            else if (!on && k.GetValue("EarHop") != null) k.DeleteValue("EarHop");
        }
        catch (Exception e) { Log.Write($"startup: {e.Message}"); }
    }
}

/// <summary>Adds "EarHop" to Explorer's Send to menu (a shortcut in the user's SendTo folder), so files can be sent
/// from a right click.</summary>
static class SendTo
{
    public static void Ensure()
    {
        try
        {
            var dir = Environment.GetFolderPath(Environment.SpecialFolder.SendTo);
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(dir) || exe == null) return;
            var lnk = Path.Combine(dir, "EarHop (send to a device).lnk");
            var t = Type.GetTypeFromProgID("WScript.Shell");
            if (t == null) return;
            dynamic shell = Activator.CreateInstance(t)!;
            dynamic sc = shell.CreateShortcut(lnk);
            if (File.Exists(lnk) && string.Equals((string)sc.TargetPath, exe, StringComparison.OrdinalIgnoreCase)) return;
            sc.TargetPath = exe;
            sc.IconLocation = exe + ",0";
            sc.Description = "Send files to your phone, tablet or Mac with EarHop";
            sc.Save();
            Log.Write("sendto: added EarHop to the Send to menu");
        }
        catch (Exception e) { Log.Write($"sendto: {e.Message}"); }
    }
}
