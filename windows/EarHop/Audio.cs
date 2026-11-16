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

    /// <summary>Asks the Bluetooth audio driver to connect the earbuds. Returns how many endpoints accepted it. Asks the
    /// music (render) endpoints first and the hands-free ones too when none accepted. Endpoints of earbuds that are on
    /// another device are often "not present" in Windows, so those are asked as well.</summary>
    public int Connect()
    {
        int n = OneShot(Ks.OneshotReconnect, render: true);
        if (n == 0) n = OneShot(Ks.OneshotReconnect, render: false);
        return n;
    }

    /// <summary>The earbuds' endpoints and their states, for the log when a connect fails.</summary>
    public string Describe()
    {
        try
        {
            var parts = Endpoints(DeviceState.All).Select(d => { try { return $"{d.FriendlyName} [{d.DataFlow}, {d.State}]"; } catch { return "?"; } }).ToList();
            return parts.Count == 0 ? $"no audio endpoint contains \"{NameFilter}\"" : string.Join("; ", parts);
        }
        catch (Exception e) { return e.Message; }
    }

    /// <summary>Asks the driver to disconnect every audio profile of the earbuds (music and hands-free).</summary>
    public int Disconnect() => OneShot(Ks.OneshotDisconnect, render: false);

    /// <summary>Runs the one-shot request on its own multithreaded (MTA) COM thread: Core Audio's topology objects
    /// cannot be reached from an STA thread (tested: "Specified cast is not valid"). The calls themselves go through
    /// the raw COM tables (Ks), so no .NET cast can fail on the Bluetooth endpoints, as it did on the owner's PC.</summary>
    private int OneShot(uint id, bool render)
    {
        int result = 0;
        var t = new Thread(() => { try { result = OneShotHere(id, render); } catch (Exception e) { Log.Write($"bt: {e.Message}"); } })
        { IsBackground = true, Name = "earhop-bt" };
        t.SetApartmentState(ApartmentState.MTA);
        t.Start();
        t.Join(TimeSpan.FromSeconds(10));
        return result;
    }

    private int OneShotHere(uint id, bool render)
    {
        // Straight to the driver, by the earbuds' Bluetooth address (no COM, see KsDirect).
        string what = id == Ks.OneshotReconnect ? "connect" : "disconnect";
        var address = BtAddress.Of(NameFilter);
        {
            var paths = KsDirect.Interfaces(address, NameFilter);
            if (paths.Count == 0) Log.Write($"bt: no Bluetooth audio driver interface for \"{NameFilter}\" yet (are they paired with this PC?)");
            int sent = 0;
            foreach (var p in paths)
            {
                int err = KsDirect.Send(p, id);
                bool music = !p.Contains("bthhfenum", StringComparison.OrdinalIgnoreCase) && !p.Contains("hfp", StringComparison.OrdinalIgnoreCase);
                string kind = music ? "music" : "headset";
                if (err == 0) sent++;
                else Log.Write($"bt: {what} ({kind}) refused by the driver, error {err}");
            }
            if (sent > 0 || paths.Count > 0)
            {
                Log.Write($"bt: {what} sent to {sent} of {paths.Count} audio interface(s)");
                return sent;
            }
        }
        // Fallback: the endpoint topology route (older path).
        int ok = 0;
        var done = new HashSet<string>();
        foreach (var d in Endpoints(DeviceState.Active | DeviceState.Unplugged | DeviceState.NotPresent).ToList())
        {
            try
            {
                if (render && d.DataFlow != DataFlow.Render) continue;
                var filterId = Ks.FilterId(d.ID, out var why);
                if (filterId == null) { Log.Write($"bt: no driver filter behind {d.FriendlyName} ({d.State}): {why}"); continue; }
                if (!done.Add(filterId)) continue;
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

    // Raw COM calls through the interfaces' function tables. IMMDevice: QueryInterface, AddRef, Release, Activate (3).
    // IDeviceTopology: GetConnectorCount (3), GetConnector (4). IConnector: GetDeviceIdConnectedTo (10).
    // IKsControl: KsProperty (3). Every step returns its HRESULT, so a refusal is logged precisely.
    private static unsafe int Call(IntPtr obj, int slot, Func<IntPtr, int> invoke) => invoke((*(IntPtr**)obj)[slot]);

    private static unsafe int ActivateRaw(string deviceId, Guid iid, out IntPtr iface)
    {
        iface = IntPtr.Zero;
        var en = Enumerator();
        int hr = en.GetDevice(deviceId, out var dev);
        if (hr != 0) return hr;
        var unk = Marshal.GetComInterfaceForObject(dev, typeof(IMMDevice));
        try
        {
            IntPtr result = IntPtr.Zero;
            var fn = (delegate* unmanaged[Stdcall]<IntPtr, Guid*, int, IntPtr, IntPtr*, int>)(*(IntPtr**)unk)[3];
            hr = fn(unk, &iid, ClsctxAll, IntPtr.Zero, &result);
            iface = result;
            return hr;
        }
        finally { Marshal.Release(unk); }
    }

    /// <summary>The kernel streaming filter behind an audio endpoint: the device its first connector is wired to.</summary>
    public static unsafe string? FilterId(string endpointId) => FilterId(endpointId, out _);

    public static unsafe string? FilterId(string endpointId, out string why)
    {
        why = "";
        int hr = ActivateRaw(endpointId, typeof(IDeviceTopology).GUID, out var topo);
        if (hr != 0) { why = $"topology 0x{hr:x8}"; return null; }
        try
        {
            uint n = 0;
            hr = ((delegate* unmanaged[Stdcall]<IntPtr, uint*, int>)(*(IntPtr**)topo)[3])(topo, &n);
            if (hr != 0 || n == 0) { why = $"connectors 0x{hr:x8} ({n})"; return null; }
            IntPtr conn = IntPtr.Zero;
            hr = ((delegate* unmanaged[Stdcall]<IntPtr, uint, IntPtr*, int>)(*(IntPtr**)topo)[4])(topo, 0, &conn);
            if (hr != 0) { why = $"connector 0x{hr:x8}"; return null; }
            try
            {
                IntPtr str = IntPtr.Zero;
                hr = ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr*, int>)(*(IntPtr**)conn)[10])(conn, &str);
                if (hr != 0) { why = $"connected device 0x{hr:x8}"; return null; }
                var id = Marshal.PtrToStringUni(str);
                Marshal.FreeCoTaskMem(str);
                return id;
            }
            finally { Marshal.Release(conn); }
        }
        finally { Marshal.Release(topo); }
    }

    /// <summary>Sends a one-shot Bluetooth audio property (connect or disconnect) to a filter; returns the HRESULT.</summary>
    public static unsafe int Send(string filterId, uint id)
    {
        int hr = ActivateRaw(filterId, typeof(IKsControl).GUID, out var ks);
        if (hr != 0) return hr;
        try
        {
            var prop = new KsProperty { Set = BtAudioSet, Id = id, Flags = KsPropertyTypeGet };
            uint returned = 0;
            var fn = (delegate* unmanaged[Stdcall]<IntPtr, KsProperty*, uint, IntPtr, uint, uint*, int>)(*(IntPtr**)ks)[3];
            return fn(ks, &prop, (uint)sizeof(KsProperty), IntPtr.Zero, 0, &returned);
        }
        finally { Marshal.Release(ks); }
    }

    /// <summary>Diagnostics only: walks endpoint, topology, filter and IKsControl like a connect does, without sending
    /// anything, and says where it stops (EarHop.exe --kstest).</summary>
    public static string Probe(string endpointId)
    {
        try
        {
            var filter = FilterId(endpointId, out var why);
            if (filter == null) return $"no filter ({why})";
            int hr = ActivateRaw(filter, typeof(IKsControl).GUID, out var ks);
            if (hr != 0) return $"IKsControl 0x{hr:x8}";
            Marshal.Release(ks);
            return "ok";
        }
        catch (Exception e) { return $"{e.GetType().Name}: {e.Message}"; }
    }

}

/// <summary>
/// The Bluetooth audio driver's one-shot connect and disconnect (KSPROPSETID_BtAudio), sent straight to the driver:
/// the earbuds' kernel streaming audio interfaces are found with SetupAPI by their Bluetooth address, opened as files,
/// and the property goes in with DeviceIoControl(IOCTL_KS_PROPERTY). This is what IKsControl does underneath, without
/// any COM object, so nothing can fail with "Specified cast is not valid" (which stopped every request on the owner's
/// PC in 1.4.3 and 1.4.4, for both the Galaxy Buds and the AirPods).
/// </summary>
static class KsDirect
{
    private static readonly Guid KsCategoryAudio = new("6994AD04-93EF-11D0-A3CC-00A0C9223196");
    private static readonly Guid BtAudioSet = new("7FA06C40-B8F6-4C7E-8556-E8C33A12E54D");
    private const uint IoctlKsProperty = 0x002F0003; // CTL_CODE(FILE_DEVICE_KS, 0, METHOD_NEITHER, FILE_ANY_ACCESS)
    private const int DigcfPresent = 0x2, DigcfDeviceInterface = 0x10;

    [StructLayout(LayoutKind.Sequential)]
    private struct SpDeviceInterfaceData { public int cbSize; public Guid InterfaceClassGuid; public int Flags; public IntPtr Reserved; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KsProperty { public Guid Set; public uint Id; public uint Flags; }
    [StructLayout(LayoutKind.Sequential)]
    private struct SpDevinfoData { public int cbSize; public Guid ClassGuid; public int DevInst; public IntPtr Reserved; }

    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool SetupDiGetDeviceRegistryPropertyW(IntPtr set, ref SpDevinfoData devInfo, int property, out int regType, byte[]? buffer, int size, out int required);
    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern IntPtr SetupDiGetClassDevsW(ref Guid classGuid, IntPtr enumerator, IntPtr parent, int flags);
    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr devInfo, ref Guid classGuid, int index, ref SpDeviceInterfaceData data);
    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool SetupDiGetDeviceInterfaceDetailW(IntPtr set, ref SpDeviceInterfaceData data, IntPtr detail, int size, out int required, ref SpDevinfoData devInfo);
    [DllImport("setupapi.dll")]
    private static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern Microsoft.Win32.SafeHandles.SafeFileHandle CreateFileW(string name, uint access, uint share, IntPtr sa, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool DeviceIoControl(Microsoft.Win32.SafeHandles.SafeFileHandle h, uint code, ref KsProperty inBuf, int inSize, IntPtr outBuf, int outSize, out int returned, IntPtr overlapped);

    private static string Prop(IntPtr set, ref SpDevinfoData dev, int property)
    {
        SetupDiGetDeviceRegistryPropertyW(set, ref dev, property, out _, null, 0, out int need);
        if (need <= 0) return "";
        var b = new byte[need];
        return SetupDiGetDeviceRegistryPropertyW(set, ref dev, property, out _, b, need, out _) ? System.Text.Encoding.Unicode.GetString(b).TrimEnd('\0') : "";
    }

    /// <summary>Every present Bluetooth audio driver interface of the earbuds: by their address in the path (music) or
    /// their name in the device's name (hands-free, whose path has no address).</summary>
    public static List<string> Interfaces(string? address12, string nameFilter, bool any = false)
    {
        var list = new List<string>();
        var g = KsCategoryAudio;
        var set = SetupDiGetClassDevsW(ref g, IntPtr.Zero, IntPtr.Zero, DigcfPresent | DigcfDeviceInterface);
        if (set == new IntPtr(-1)) return list;
        try
        {
            for (int i = 0; ; i++)
            {
                var d = new SpDeviceInterfaceData { cbSize = Marshal.SizeOf<SpDeviceInterfaceData>() };
                if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref g, i, ref d)) break;
                var info = new SpDevinfoData { cbSize = Marshal.SizeOf<SpDevinfoData>() };
                SetupDiGetDeviceInterfaceDetailW(set, ref d, IntPtr.Zero, 0, out int need, ref info);
                if (need <= 0) continue;
                var buf = Marshal.AllocHGlobal(need);
                try
                {
                    Marshal.WriteInt32(buf, IntPtr.Size == 8 ? 8 : 6); // cbSize of SP_DEVICE_INTERFACE_DETAIL_DATA_W
                    if (!SetupDiGetDeviceInterfaceDetailW(set, ref d, buf, need, out _, ref info)) continue;
                    var path = Marshal.PtrToStringUni(buf + 4) ?? "";
                    if (any) { list.Add($"{path} [{Prop(set, ref info, 0x0C)}]"); continue; }
                    if (!path.Contains("bth", StringComparison.OrdinalIgnoreCase)) continue; // Bluetooth audio only
                    bool byAddress = address12 != null && path.Contains(address12, StringComparison.OrdinalIgnoreCase);
                    bool byName = false;
                    if (!byAddress)
                    {
                        // SPDRP_FRIENDLYNAME (0x0C), else SPDRP_DEVICEDESC (0)
                        var name = Prop(set, ref info, 0x0C);
                        if (name.Length == 0) name = Prop(set, ref info, 0);
                        byName = name.Contains(nameFilter, StringComparison.OrdinalIgnoreCase);
                    }
                    if ((byAddress || byName) && !list.Contains(path)) list.Add(path);
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
        }
        finally { SetupDiDestroyDeviceInfoList(set); }
        return list;
    }

    /// <summary>Sends the one-shot property to one interface; 0 when the driver took it, else the Win32 error.</summary>
    public static int Send(string path, uint id)
    {
        // Read and write access first; the property is FILE_ANY_ACCESS, so a handle without access also works.
        foreach (uint access in new uint[] { 0xC0000000, 0x80000000, 0 })
        {
            using var h = CreateFileW(path, access, 3, IntPtr.Zero, 3, 0x80, IntPtr.Zero);
            if (h.IsInvalid) { int e = Marshal.GetLastWin32Error(); if (access == 0) return e; continue; }
            var prop = new KsProperty { Set = BtAudioSet, Id = id, Flags = 1 };
            if (DeviceIoControl(h, IoctlKsProperty, ref prop, Marshal.SizeOf<KsProperty>(), IntPtr.Zero, 0, out _, IntPtr.Zero)) return 0;
            return Marshal.GetLastWin32Error();
        }
        return -1;
    }
}

/// <summary>The Bluetooth address of a paired device whose name contains the filter, as 12 hex digits (cached 5 min).</summary>
static class BtAddress
{
    private static readonly Dictionary<string, (string Address, DateTime At)> cache = new();

    public static string? Of(string filter)
    {
        lock (cache)
            if (cache.TryGetValue(filter, out var c) && (DateTime.UtcNow - c.At).TotalMinutes < 5) return c.Address;
        try
        {
            var all = Windows.Devices.Enumeration.DeviceInformation.FindAllAsync(
                Windows.Devices.Bluetooth.BluetoothDevice.GetDeviceSelectorFromPairingState(true)).AsTask().GetAwaiter().GetResult();
            foreach (var d in all)
            {
                if (!d.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
                using var dev = Windows.Devices.Bluetooth.BluetoothDevice.FromIdAsync(d.Id).AsTask().GetAwaiter().GetResult();
                if (dev == null) continue;
                var a = dev.BluetoothAddress.ToString("x12");
                lock (cache) cache[filter] = (a, DateTime.UtcNow);
                return a;
            }
        }
        catch (Exception e) { Log.Write($"bt: could not read the paired devices ({e.Message.Trim()})"); }
        return null;
    }
}
