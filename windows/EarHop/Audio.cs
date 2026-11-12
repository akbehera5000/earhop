using System.Diagnostics;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace EarHop.Win;

/// <summary>Playing and call detection through Core Audio. "Playing" means an app's audio session on the default output
/// produced sound (peak above a floor) within the last 2 s; a browser that keeps its session open while paused is
/// silent and does not count. A call means an app records from the microphone while this PC plays, for 2 s (the Mac
/// rule: a microphone alone, dictation, is not a call).</summary>
sealed class AudioMonitor : IDisposable
{
    private readonly MMDeviceEnumerator en = new();
    private MMDevice? output;
    private string outputId = "";
    private List<AudioSessionControl> sessions = new();
    private DateTime refreshedAt = DateTime.MinValue;
    private DateTime lastSound = DateTime.MinValue;
    private DateTime? micSince;
    private readonly int selfPid = Environment.ProcessId;
    private const float Floor = 0.0008f;

    public bool Playing { get; private set; }
    public bool InCall { get; private set; }
    /// <summary>The apps producing sound now (for the log and diagnostics), by process name.</summary>
    public string Sources { get; private set; } = "";

    /// <summary>Called four times a second on the UI thread.</summary>
    public void Tick()
    {
        var now = DateTime.UtcNow;
        try
        {
            if ((now - refreshedAt).TotalSeconds >= 1) Refresh(now);
            var names = new List<string>();
            foreach (var s in sessions)
            {
                try
                {
                    if (s.IsSystemSoundsSession || s.State != AudioSessionState.AudioSessionStateActive) continue;
                    if ((int)s.GetProcessID == selfPid) continue;
                    if (s.AudioMeterInformation.MasterPeakValue > Floor) { lastSound = now; names.Add(ProcessName((int)s.GetProcessID)); }
                }
                catch (COMException) { refreshedAt = DateTime.MinValue; }
                catch (InvalidCastException) { refreshedAt = DateTime.MinValue; }
            }
            if (names.Count > 0) Sources = string.Join(",", names.Distinct());
            Playing = (now - lastSound).TotalSeconds < 2;
            bool mic = Playing && MicInUse();
            if (mic && Playing) micSince ??= now; else micSince = null;
            InCall = micSince is DateTime ms && (now - ms).TotalSeconds >= 2;
        }
        catch (Exception e) when (e is COMException or InvalidOperationException)
        {
            Log.Write($"audio: {e.Message}");
            refreshedAt = DateTime.MinValue;
            output = null;
        }
    }

    private void Refresh(DateTime now)
    {
        refreshedAt = now;
        var d = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        if (d.ID != outputId || output == null) { output = d; outputId = d.ID; Log.Write($"audio: output is {d.FriendlyName}"); }
        var mgr = output.AudioSessionManager;
        mgr.RefreshSessions();
        var list = new List<AudioSessionControl>();
        var all = mgr.Sessions;
        for (int i = 0; i < all.Count; i++) list.Add(all[i]);
        sessions = list;
    }

    private bool MicInUse()
    {
        try
        {
            var mic = en.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Communications);
            var mgr = mic.AudioSessionManager;
            mgr.RefreshSessions();
            var all = mgr.Sessions;
            for (int i = 0; i < all.Count; i++)
            {
                var s = all[i];
                if (!s.IsSystemSoundsSession && s.State == AudioSessionState.AudioSessionStateActive && (int)s.GetProcessID != selfPid) return true;
            }
        }
        catch (COMException) { }
        return false;
    }

    private static readonly Dictionary<int, string> names = new();
    private static string ProcessName(int pid)
    {
        if (names.TryGetValue(pid, out var n)) return n;
        try { n = Process.GetProcessById(pid).ProcessName; } catch { n = pid.ToString(); }
        if (names.Count > 200) names.Clear();
        names[pid] = n;
        return n;
    }

    public void Dispose() => en.Dispose();
}

/// <summary>Connects and disconnects the earbuds the way Windows' own Sound settings do: the Bluetooth audio driver's
/// one-shot reconnect and disconnect properties (KSPROPSETID_BtAudio), reached from the earbuds' audio endpoint through
/// its device topology. The earbuds must be paired with this PC once in Bluetooth settings. Owner check: an earbuds
/// output endpoint is active.</summary>
sealed class BtAudio
{
    public string NameFilter = "Buds2 Pro";

    // A new enumerator for every call: claims and releases run on worker threads, the owner check on the UI thread, and
    // Core Audio objects must not cross apartments.
    private IEnumerable<MMDevice> Endpoints(DeviceState states)
    {
        using var en = new MMDeviceEnumerator();
        foreach (var flow in new[] { DataFlow.Render, DataFlow.Capture })
        {
            MMDeviceCollection list;
            try { list = en.EnumerateAudioEndPoints(flow, states); } catch (COMException) { continue; }
            for (int i = 0; i < list.Count; i++)
            {
                MMDevice d;
                string name;
                try { d = list[i]; name = d.FriendlyName; } catch (COMException) { continue; }
                if (name.Contains(NameFilter, StringComparison.OrdinalIgnoreCase)) yield return d;
            }
        }
    }

    /// <summary>True when the earbuds play through this PC now (an earbuds output endpoint is active).</summary>
    public bool Connected()
    {
        try
        {
            using var en = new MMDeviceEnumerator();
            var list = en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);
            for (int i = 0; i < list.Count; i++)
                if (list[i].FriendlyName.Contains(NameFilter, StringComparison.OrdinalIgnoreCase)) return true;
        }
        catch (COMException) { }
        return false;
    }

    /// <summary>Whether any endpoint of the earbuds is known to Windows (they were paired here).</summary>
    public bool Known() => Endpoints(DeviceState.Active | DeviceState.Unplugged | DeviceState.Disabled).Any();

    /// <summary>Asks the Bluetooth audio driver to connect the earbuds. Returns how many endpoints accepted it.</summary>
    public int Connect() => OneShot(Ks.OneshotReconnect, render: true);

    /// <summary>Asks the driver to disconnect every audio profile of the earbuds (music and hands-free).</summary>
    public int Disconnect() => OneShot(Ks.OneshotDisconnect, render: false);

    private int OneShot(uint id, bool render)
    {
        int ok = 0;
        var done = new HashSet<string>();
        foreach (var d in Endpoints(DeviceState.Active | DeviceState.Unplugged).ToList())
        {
            try
            {
                if (render && d.DataFlow != DataFlow.Render) continue;
                var filterId = Ks.FilterId(d.ID);
                if (filterId == null || !done.Add(filterId)) continue;
                int hr = Ks.Send(filterId, id);
                if (hr == 0) ok++;
                else Log.Write($"bt: {(id == Ks.OneshotReconnect ? "connect" : "disconnect")} on {d.FriendlyName} returned 0x{hr:x8}");
            }
            catch (Exception e) when (e is COMException or InvalidCastException)
            {
                Log.Write($"bt: {d.FriendlyName}: {e.Message}");
            }
        }
        return ok;
    }
}

/// <summary>Core Audio and kernel streaming interfaces not covered by NAudio. Method order follows the Windows SDK
/// headers (mmdeviceapi.h, devicetopology.h, ks.h).</summary>
static class Ks
{
    public const uint OneshotReconnect = 0;
    public const uint OneshotDisconnect = 1;
    private static readonly Guid BtAudioSet = new("7FA06C40-B8F6-4C7E-8556-E8C33A12E54D");
    private const uint KsPropertyTypeGet = 1;
    private const int ClsctxAll = 0x17;

    [StructLayout(LayoutKind.Sequential)]
    private struct KsProperty { public Guid Set; public uint Id; public uint Flags; }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorCo { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IntPtr endpoint);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        [PreserveSig] int OpenPropertyStore(int access, out IntPtr store);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("2A07407E-6497-4A18-9787-32F79BD0D98F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDeviceTopology
    {
        [PreserveSig] int GetConnectorCount(out uint count);
        [PreserveSig] int GetConnector(uint index, out IConnector connector);
        [PreserveSig] int GetSubunitCount(out uint count);
        [PreserveSig] int GetSubunit(uint index, out IntPtr subunit);
        [PreserveSig] int GetPartById(uint id, out IntPtr part);
        [PreserveSig] int GetDeviceId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetSignalPath(IntPtr from, IntPtr to, int rejectMixedPaths, out IntPtr parts);
    }

    [ComImport, Guid("9c2c4058-23f5-41de-877a-df3af236a09e"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IConnector
    {
        [PreserveSig] int GetType(out int type);
        [PreserveSig] int GetDataFlow(out int flow);
        [PreserveSig] int ConnectTo(IntPtr other);
        [PreserveSig] int Disconnect();
        [PreserveSig] int IsConnected(out int connected);
        [PreserveSig] int GetConnectedTo(out IntPtr other);
        [PreserveSig] int GetConnectorIdConnectedTo([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetDeviceIdConnectedTo([MarshalAs(UnmanagedType.LPWStr)] out string id);
    }

    [ComImport, Guid("28F54685-06FD-11D2-B27A-00A0C9223196"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IKsControl
    {
        [PreserveSig] int KsProperty(ref KsProperty property, uint propertyLength, IntPtr data, uint dataLength, out uint returned);
        [PreserveSig] int KsMethod(IntPtr method, uint methodLength, IntPtr data, uint dataLength, out uint returned);
        [PreserveSig] int KsEvent(IntPtr ev, uint eventLength, IntPtr data, uint dataLength, out uint returned);
    }

    private static IMMDeviceEnumerator Enumerator() => (IMMDeviceEnumerator)new MMDeviceEnumeratorCo();

    /// <summary>The kernel streaming filter behind an audio endpoint: the device its first connector is wired to.</summary>
    public static string? FilterId(string endpointId)
    {
        var en = Enumerator();
        if (en.GetDevice(endpointId, out var dev) != 0) return null;
        var iid = typeof(IDeviceTopology).GUID;
        if (dev.Activate(ref iid, ClsctxAll, IntPtr.Zero, out var t) != 0) return null;
        var topo = (IDeviceTopology)t;
        if (topo.GetConnectorCount(out uint n) != 0 || n == 0) return null;
        if (topo.GetConnector(0, out var conn) != 0) return null;
        return conn.GetDeviceIdConnectedTo(out var id) == 0 ? id : null;
    }

    /// <summary>Sends a one-shot Bluetooth audio property (connect or disconnect) to a filter; returns the HRESULT.</summary>
    public static int Send(string filterId, uint id)
    {
        var en = Enumerator();
        int hr = en.GetDevice(filterId, out var dev);
        if (hr != 0) return hr;
        var iid = typeof(IKsControl).GUID;
        hr = dev.Activate(ref iid, ClsctxAll, IntPtr.Zero, out var k);
        if (hr != 0) return hr;
        var prop = new KsProperty { Set = BtAudioSet, Id = id, Flags = KsPropertyTypeGet };
        return ((IKsControl)k).KsProperty(ref prop, (uint)Marshal.SizeOf<KsProperty>(), IntPtr.Zero, 0, out _);
    }
}
