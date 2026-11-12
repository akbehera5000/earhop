using System.Net;
using System.Net.Sockets;

namespace EarHop;

/// <summary>Wi-Fi clipboard link, TCP port 47475 (ClipTcp in Clipboard.swift): one "length (4) | frame" per connection,
/// or an "EHF1" file stream. Only addresses learned from signed status packets may connect.</summary>
public sealed class ClipTcp : IDisposable
{
    public const int DefaultPort = 47475;
    public readonly int Port;
    public ClipTcp(int port = DefaultPort) { Port = port; }
    public const int MaxFrame = 16 * 1024 * 1024;
    private TcpListener? listener;
    private int open;
    public Func<IPAddress, bool> Allowed = _ => false;
    public Action<byte[]>? OnFrame;
    public Func<ClipItem, string?>? AcceptFile;
    public Action<ClipItem, string?>? OnFile;
    public string Secret = "";
    public bool Listening { get; private set; }

    public void Start()
    {
        try
        {
            listener = new TcpListener(IPAddress.IPv6Any, Port);
            listener.Server.DualMode = true;
            listener.Start();
            Listening = true;
            _ = AcceptLoop(listener);
        }
        catch (SocketException e) { Log.Write($"clip: Wi-Fi listener not started: {e.Message}"); }
    }

    private async Task AcceptLoop(TcpListener l)
    {
        while (true)
        {
            TcpClient c;
            try { c = await l.AcceptTcpClientAsync(); } catch { Listening = false; return; }
            var from = ((IPEndPoint)c.Client.RemoteEndPoint!).Address;
            if (!Allowed(from) || Interlocked.Increment(ref open) > 4) { if (Allowed(from)) Interlocked.Decrement(ref open); c.Dispose(); continue; }
            _ = Task.Run(async () => { try { await Serve(c); } catch { } finally { Interlocked.Decrement(ref open); c.Dispose(); } });
        }
    }

    private static async Task<byte[]?> Exactly(Stream s, int n, TimeSpan timeout)
    {
        var buf = new byte[n];
        int got = 0;
        using var cts = new CancellationTokenSource(timeout);
        while (got < n)
        {
            int r = await s.ReadAsync(buf.AsMemory(got, n - got), cts.Token);
            if (r <= 0) return null;
            got += r;
        }
        return buf;
    }

    private async Task Serve(TcpClient c)
    {
        var s = c.GetStream();
        var head = await Exactly(s, 4, TimeSpan.FromSeconds(10));
        if (head == null) return;
        if (head.AsSpan().SequenceEqual(FileCrypt.Magic)) { await ServeFile(s); return; }
        int len = FileCrypt.Int(head);
        if (len <= 0 || len > MaxFrame) return;
        var frame = await Exactly(s, len, TimeSpan.FromSeconds(30));
        if (frame != null) OnFrame?.Invoke(frame);
    }

    private async Task ServeFile(NetworkStream s)
    {
        var idle = TimeSpan.FromSeconds(30); // nothing for 30 s ends the transfer; data may flow as long as it needs
        var l = await Exactly(s, 4, idle);
        if (l == null) return;
        int hl = FileCrypt.Int(l);
        if (hl <= 0 || hl >= 65536) return;
        var h = await Exactly(s, hl, idle);
        if (h == null || ClipCodec.Decode(h, Secret) is not ClipItem item || item.Kind != "file" || AcceptFile?.Invoke(item) is not string dest) return;
        bool ok = false;
        try
        {
            using (var output = File.Create(dest))
            {
                for (uint index = 0; ; index++)
                {
                    var rl = await Exactly(s, 4, idle);
                    if (rl == null) break;
                    int n = FileCrypt.Int(rl);
                    if (n == 0) { ok = true; break; }
                    if (n > FileCrypt.Chunk + 64) break;
                    var r = await Exactly(s, n, idle);
                    if (r == null || FileCrypt.Open(r, Secret, item.Id, index) is not byte[] plain) break;
                    await output.WriteAsync(plain);
                }
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or UnauthorizedAccessException) { Log.Write($"file: {e.Message}"); }
        if (!ok) { try { File.Delete(dest); } catch { } }
        OnFile?.Invoke(item, ok ? dest : null);
    }

    public async Task<bool> Send(byte[] frame, IPAddress host)
    {
        try
        {
            using var c = new TcpClient(host.AddressFamily);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            await c.ConnectAsync(host, Port, cts.Token);
            var s = c.GetStream();
            await s.WriteAsync(FileCrypt.Be32(frame.Length).Concat(frame).ToArray(), cts.Token);
            await s.FlushAsync(cts.Token);
            return true;
        }
        catch (Exception e) { Log.Write($"clip: Wi-Fi send to {host} failed: {e.Message}"); return false; }
    }

    public async Task<bool> SendFile(string path, byte[] header, string id, IPAddress host)
    {
        try
        {
            using var c = new TcpClient(host.AddressFamily);
            using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10))) await c.ConnectAsync(host, Port, cts.Token);
            var s = c.GetStream();
            await s.WriteAsync(FileCrypt.Magic.Concat(FileCrypt.Be32(header.Length)).Concat(header).ToArray());
            using var f = File.OpenRead(path);
            var buf = new byte[FileCrypt.Chunk];
            for (uint index = 0; ; index++)
            {
                int n = 0, r;
                while (n < buf.Length && (r = await f.ReadAsync(buf.AsMemory(n))) > 0) n += r;
                if (n == 0) break;
                var sealedRec = FileCrypt.Seal(buf[..n], Secret, id, index);
                await s.WriteAsync(FileCrypt.Be32(sealedRec.Length).Concat(sealedRec).ToArray());
            }
            await s.WriteAsync(FileCrypt.Be32(0));
            await s.FlushAsync();
            return true;
        }
        catch (Exception e) { Log.Write($"file: send to {host} failed: {e.Message}"); return false; }
    }

    public void Dispose() { Listening = false; listener?.Stop(); }
}

