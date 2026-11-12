using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace EarHop;

/// <summary>Status over Wi-Fi: one signed UDP datagram to port 47474, broadcast on every network plus unicast to the
/// addresses learned from signed packets (Link.swift). Learns where each device is, spots two devices with one name,
/// and reports packets refused for a different secret.</summary>
public sealed class UdpLink : IDisposable
{
    public const int DefaultPort = 47474;
    public readonly int Port;
    /// <summary>Extra addresses to send to (tests use the loopback address).</summary>
    public List<IPAddress> ExtraTargets = new();
    private readonly string secret, selfName;
    private UdpClient? recv;
    private readonly UdpClient send;
    private readonly object gate = new();
    private readonly Dictionary<string, List<IPAddress>> learned = new();
    private readonly Dictionary<string, Dictionary<IPAddress, DateTime>> heardAt = new();
    private readonly Dictionary<string, (IPAddress Addr, int Seq, DateTime At)> lastFrom = new();
    private readonly Dictionary<string, DateTime> duplicateAt = new(), rejectedAt = new();
    public event Action<Status>? OnStatus;
    public event Action<string>? OnDuplicate, OnRejected;
    public DateTime LastHeard { get; private set; } = DateTime.MinValue;

    public UdpLink(string secret, string selfName, int port = DefaultPort)
    {
        Port = port;
        this.secret = secret;
        this.selfName = selfName;
        send = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
    }

    public void Start()
    {
        try
        {
            recv = new UdpClient(AddressFamily.InterNetwork);
            recv.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            recv.Client.Bind(new IPEndPoint(IPAddress.Any, Port));
            // Wakes the receive thread every second, so a stopped link ends even where closing a socket does not
            // interrupt a blocking receive (macOS, Linux).
            recv.Client.ReceiveTimeout = 1000;
        }
        catch (SocketException e) { Log.Write($"link: could not listen on UDP {Port}: {e.Message}"); return; }
        var t = new Thread(Loop) { IsBackground = true, Name = "earhop-recv" };
        t.Start();
    }

    private void Loop()
    {
        var any = new IPEndPoint(IPAddress.Any, 0);
        while (recv != null)
        {
            byte[] data;
            try { data = recv.Receive(ref any); }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { continue; }
            var from = any.Address;
            var s = Proto.Decode(data, secret, Proto.NowMs());
            if (s is Status st && st.Dev != selfName)
            {
                LastHeard = DateTime.UtcNow;
                bool isNew, duplicate = false;
                var now = DateTime.UtcNow;
                lock (gate)
                {
                    var known = learned.TryGetValue(st.Dev, out var k) ? k : new();
                    isNew = !known.Contains(from);
                    known.Remove(from);
                    known.Add(from);
                    if (known.Count > 4) known.RemoveAt(0);
                    learned[st.Dev] = known;
                    var times = heardAt.TryGetValue(st.Dev, out var tt) ? tt : new();
                    times[from] = now;
                    heardAt[st.Dev] = times.Where(kv => known.Contains(kv.Key)).ToDictionary(kv => kv.Key, kv => kv.Value);
                    if (lastFrom.TryGetValue(st.Dev, out var last) && !last.Addr.Equals(from) && st.Seq + 3 < last.Seq && (now - last.At).TotalSeconds < 10)
                    {
                        duplicate = (now - duplicateAt.GetValueOrDefault(st.Dev, DateTime.MinValue)).TotalSeconds > 60;
                        if (duplicate) duplicateAt[st.Dev] = now;
                    }
                    lastFrom[st.Dev] = (from, st.Seq, now);
                }
                if (isNew) Log.Write($"link: {st.Dev} is at {from}");
                if (duplicate)
                {
                    Log.Write($"link: two devices are both named {st.Dev}. Give each device its own name");
                    OnDuplicate?.Invoke(st.Dev);
                }
                OnStatus?.Invoke(st);
            }
            else if (Proto.RejectedSender(data) is string dev && dev != selfName)
            {
                bool due;
                lock (gate)
                {
                    due = (DateTime.UtcNow - rejectedAt.GetValueOrDefault(dev, DateTime.MinValue)).TotalSeconds > 60;
                    if (due) rejectedAt[dev] = DateTime.UtcNow;
                }
                if (due)
                {
                    Log.Write($"link: packets from {dev} refused: it uses a different secret, pair it again");
                    OnRejected?.Invoke(dev);
                }
            }
        }
    }

    /// <summary>The broadcast address of every active IPv4 network (255.255.255.255 alone leaves through one only).</summary>
    private static List<IPAddress> DirectedBroadcasts()
    {
        var output = new List<IPAddress>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var u in ni.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask == null) continue;
                    var a = u.Address.GetAddressBytes();
                    var m = u.IPv4Mask.GetAddressBytes();
                    if (m.All(x => x == 0)) continue;
                    var b = new byte[4];
                    for (int i = 0; i < 4; i++) b[i] = (byte)(a[i] | ~m[i]);
                    output.Add(new IPAddress(b));
                }
            }
        }
        catch (NetworkInformationException) { }
        return output;
    }

    private List<IPAddress> broadcasts = new();
    private DateTime broadcastsAt = DateTime.MinValue;

    public void Send(Status s)
    {
        var data = Proto.Encode(s, secret);
        if ((DateTime.UtcNow - broadcastsAt).TotalSeconds > 10) { broadcasts = DirectedBroadcasts(); broadcastsAt = DateTime.UtcNow; }
        List<IPAddress> direct;
        lock (gate) direct = learned.Values.SelectMany(x => x).ToList();
        foreach (var a in ExtraTargets.Concat(direct).Concat(broadcasts).Append(IPAddress.Broadcast).Distinct())
        {
            try { send.Send(data, data.Length, new IPEndPoint(a, Port)); }
            catch (SocketException) { }
        }
    }

    /// <summary>Where each device can be reached now (addresses heard within 10 minutes, newest first).</summary>
    public List<(string Dev, IPAddress Host)> PeerHostsByDev()
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            return learned.SelectMany(kv => AddressBook.Current(kv.Value, heardAt.GetValueOrDefault(kv.Key) ?? new(), now)
                                                       .Select(h => (kv.Key, h))).ToList();
        }
    }

    /// <summary>Whether an address belongs to one of the user's devices (heard in a signed status within 10 minutes).</summary>
    public bool Allowed(IPAddress a)
    {
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        lock (gate)
        {
            var now = DateTime.UtcNow;
            return heardAt.Values.Any(t => t.TryGetValue(a, out var at) && (now - at).TotalMinutes < 10);
        }
    }

    public void Dispose() { var r = recv; recv = null; Thread.Sleep(0); try { r?.Client.Shutdown(SocketShutdown.Both); } catch (SocketException) { } r?.Dispose(); send.Dispose(); }
}

