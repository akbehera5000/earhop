using System.Text;
using EarHop;

// The Windows self test: the protocol vectors from AGENTS.md section 6, the clipboard frames shared with the Mac and
// Android, and every handoff timeline from SimTests.swift (also with the real Buds timing). Prints "selftest OK".
Log.ToFile = false;
Config.ReadOnly = true;
var failed = new List<string>();
int count = 0;
void Check(string name, bool ok) { count++; if (Environment.GetEnvironmentVariable("EARHOP_TRACE") != null) Console.WriteLine($"{DateTime.Now:HH:mm:ss.fff} {name} {ok}"); if (!ok) failed.Add(name); }
static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();
static byte[] Bytes(string hex) => Convert.FromHexString(hex);

var s = new Status("mac", 1700000000000, 7, true, Ear.Both, false);
Check("canonical v1", Proto.Canonical(s, 1) == "1|mac|1700000000000|7|1|both|0");
Check("hmac vector v1", Proto.Sign("test-secret", Proto.Canonical(s, 1)) == "389cb0b6b95b236ec9f8ee2a76263d87cf0f876d590b14c976960cbe424b4c00");
Check("hmac vector v2", Proto.Sign("test-secret", "2|mac|1700000000000|7|1|both|0|1") == "8c0b895c231d9329540318f0d015d6cc3d2e1007950972ce05ef20bf82ba2b8f");
Check("hmac vector v3", Proto.Sign("test-secret", "3|mac|1700000000000|7|1|both|0|1|buds") == "e0b6ceded3c4002c40a606404586e70634245c77a4f5aff28eedb1352866659d");
var s4 = new Status("mac", 1700000000000, 7, true, Ear.Both, false, true, "buds", true);
Check("v4 vector", Proto.Sign("test-secret", Proto.Canonical(s4, 4)) == "7ad20a1aac679b7fcd58e5e67214a1531812fdb6e574eeca406f31ef8af8cf0f");
Check("device ids", new[] { "mac", "phone", "tab" }.Select(n => DeviceNames.Id(n, "test-secret")).SequenceEqual(new[] { 33190, 36699, 48167 }));
var b4 = BlePayload.Encode4(s4 with { Ts = 0 }, "test-secret", 1_700_000_000);
Check("v4 ble vector", Hex(b4) == "eb81a60007f728611b1ffa426553f100");
var d4 = BlePayload.Decode4(b4, "test-secret");
Check("v4 ble decode", d4 is { Id: 33190, Seq: 7 } && BlePayload.Status4(d4!.Value, "mac") is { Playing: true, Call: true, Prof: "buds", Att: true, Ear: Ear.Both });
var bOld = BlePayload.EncodeOld(new Status("mac", 0, 7, true, Ear.Both, false, true), "test-secret", BlePayload.Minute(1700000000000));
Check("old ble vector", bOld != null && Hex(bOld) == "ea0000071707e997e90831cf5fb05515");
var bOld3 = BlePayload.EncodeOld(new Status("mac", 0, 7, true, Ear.Both, false, true, "buds"), "test-secret", BlePayload.Minute(1700000000000));
Check("old ble vector v3", bOld3 != null && Hex(bOld3) == "ea0000077752983dac7ce48782b05515");
Check("old ble decode", BlePayload.DecodeOld(bOld3!, "test-secret", BlePayload.Minute(1700000000000)) is { Dev: "mac", Prof: "buds", Call: true });

long now = Proto.NowMs();
var fresh = new Status("tab", now, 1, false, Ear.None, true);
var wire = Proto.Encode(fresh, "k");
Check("roundtrip", Proto.Decode(wire, "k", now)?.Dev == "tab");
Check("wrong secret rejected", Proto.Decode(wire, "x", now) == null && Proto.RejectedSender(wire) == "tab");
Check("v4 decodes with distant clocks", Proto.Decode(wire, "k", now + 600_000)?.Dev == "tab");
Check("bad name refused", Proto.Decode(Proto.Encode(fresh with { Dev = "Bad Name" }, "k"), "k", now) == null);
var py = Encoding.UTF8.GetBytes("{\"v\":4,\"dev\":\"mac\",\"ts\":1700000000000,\"seq\":7,\"playing\":1,\"ear\":\"both\",\"owner\":0,\"call\":1,\"prof\":\"buds\",\"att\":1,\"sig\":\"7ad20a1aac679b7fcd58e5e67214a1531812fdb6e574eeca406f31ef8af8cf0f\"}");
Check("reads a packet from another app", Proto.Decode(py, "test-secret", 0) is { Dev: "mac", Att: true, Call: true });

var cg = new ClockGuard();
Check("clock 10 min ahead learned", cg.Check("p", 601_000, 1000, ClockGuard.WifiTolerance, 1) && cg.Check("p", 602_000, 2000, ClockGuard.WifiTolerance, 2));
Check("2 minute old recording refused", !cg.Check("p", 482_000, 2000, ClockGuard.BleTolerance, 1));
Check("changed clock relearned after 3 rising copies",
      !cg.Check("p", 900_000, 3000, ClockGuard.WifiTolerance, 3) && !cg.Check("p", 901_000, 4000, ClockGuard.WifiTolerance, 4)
      && cg.Check("p", 902_000, 5000, ClockGuard.WifiTolerance, 5));
Check("same copy replayed does not relearn",
      !cg.Check("p", 100_000, 6000, ClockGuard.WifiTolerance, 9) && !cg.Check("p", 100_000, 7000, ClockGuard.WifiTolerance, 9)
      && !cg.Check("p", 100_000, 8000, ClockGuard.WifiTolerance, 9));
Check("clip fresh by sender clock", cg.Fresh("p", 905_000, 6000, 300_000) && !cg.Fresh("p", 500_000, 6000, 300_000));

Check("order newer wifi copy taken", PeerOrder.Accept(10, 11, false) == (true, 11));
Check("order duplicate not taken", !PeerOrder.Accept(10, 10, false).Take);
Check("order late copy not taken", !PeerOrder.Accept(10, 8, false).Take);
Check("order bluetooth widened", PeerOrder.Accept(0x10003, 5, true) == (true, 0x10005));
Check("order older bluetooth not taken", !PeerOrder.Accept(0x10003, 2, true).Take);
Check("order bluetooth across wrap", PeerOrder.Accept(0x1FFFF, 1, true) == (true, 0x20001));
Check("order restarted device taken", PeerOrder.Accept(5000, 3, false) == (true, 3));
Check("order first copy taken", PeerOrder.Accept(null, 42, true) == (true, 42));
Check("replay is not a restart", !PeerOrder.Accept(1000, 5, false, 50_000, 60_000).Take && PeerOrder.Accept(1000, 5, false, 70_000, 60_000).Take);
Check("names", DeviceNames.Valid("pc") && DeviceNames.Valid("work-pc") && !DeviceNames.Valid("-pc") && !DeviceNames.Valid("PC") && !DeviceNames.Valid(""));

// Clipboard frames: byte for byte the same as the Mac (mac/.build/release/EarHop --clip-vector), and Android's frame reads.
var item = new ClipItem("0011223344556677", "mac", "text", "text/plain", 1700000000000, Encoding.UTF8.GetBytes("hello EarHop"));
var frame = ClipCodec.Encode(item, "test-secret", Enumerable.Range(0, 12).Select(i => (byte)i).ToArray())!;
var macHex = "45484331000102030405060708090a0b765c8e2baccb6f1e92ca911cc1d71d7038f9a0f0e2d8a53ac36a4ca51c25579630bd1417175a47291052f27e87a3ffc7985416704e8315c175ed265ba94d656595ffbf7c5ac0c2c59d153ea5c5711b6a54bca9ec65820b3ea9ee21053da3f2df70bddbe91ea07d245df07695ef2eae3173d9af3e584b2a61d20cf57e0fdcb8";
var macPlain = ClipCodec.Open(ClipCodec.Key("test-secret"), Bytes(macHex).AsSpan(4), ClipCodec.Magic)!;
// The Mac writes "text\/plain" (escaped slash), this app "text/plain": the same JSON, and every app reads both.
var ourPlain = ClipCodec.Open(ClipCodec.Key("test-secret"), frame.AsSpan(4), ClipCodec.Magic)!;
Check("clip header matches the Mac's", Encoding.UTF8.GetString(macPlain[2..]).Replace("\\/", "/") == Encoding.UTF8.GetString(ourPlain[2..]));
Check("clip reads the Mac frame", ClipCodec.Decode(Bytes(macHex), "test-secret") is { Dev: "mac", Mime: "text/plain" } m && Encoding.UTF8.GetString(m.Data) == "hello EarHop");
Check("clip roundtrip", ClipCodec.Decode(frame, "test-secret") is { Id: "0011223344556677" } d && Encoding.UTF8.GetString(d.Data) == "hello EarHop");
var android = Bytes("45484331000102030405060708090a0b765f8e2baccb6f1e92ca8c15cd9b54707dbfebaee2d2b733ca6147f74e7301c066ef4645451e0d295719f773cbfdff89df58072a1683548a75e16904a903226984dbe42346cdcac2d11b30f3c220036152bba9ec65820b3ea9ee210521ada68b68b697fc13a5312d0fda7ac7e62fbaf3862fce7382946c5fce347a00ed13e7fa021a4a");
Check("clip reads the Android frame", ClipCodec.Decode(android, "test-secret") is { Dev: "phone" } a && Encoding.UTF8.GetString(a.Data) == "hi from Android");
Check("clip wrong secret refused", ClipCodec.Decode(frame, "other") == null);
var bad = (byte[])frame.Clone(); bad[^20] ^= 1;
Check("clip tampering refused", ClipCodec.Decode(bad, "test-secret") == null);
var to = ClipCodec.Decode(ClipCodec.Encode(item with { To = "phone" }, "k")!, "k");
Check("clip to one device", to?.To == "phone");
var rec = FileCrypt.Seal(new byte[] { 1, 2, 3 }, "k", "abc", 4);
Check("file record roundtrip", FileCrypt.Open(rec, "k", "abc", 4) is { Length: 3 } && FileCrypt.Open(rec, "k", "abc", 5) == null);
Check("safe file names", FileCrypt.SafeName("../../x.txt") == "x.txt" && FileCrypt.SafeName("..\\a:b.txt") == "a-b.txt" && FileCrypt.SafeName("...") == "file");

var r = new Receipts(); var t0 = DateTime.UtcNow;
r.Track("c1", new[] { "mac", "phone" }, t0);
Check("receipt ack", r.Ack("c1", "mac") && !r.Ack("c1", "mac") && !r.Ack("c1", "tab"));
Check("receipt retry after 5 s", r.Due(t0.AddSeconds(4)).Count == 0 && r.Due(t0.AddSeconds(5)) is [("c1", ["phone"])]);
Check("receipt words", r.Words("c1", t0.AddSeconds(61)) == "Delivered to Mac ✓; not delivered to Phone");
Check("password-like", PasswordLike.Looks("Xk9#mPq2vL") && !PasswordLike.Looks("hello world") && !PasswordLike.Looks("https://a.b/c?D=1"));

var times = new Dictionary<string, DateTime> { ["a"] = t0.AddSeconds(-700), ["b"] = t0.AddSeconds(-10) };
Check("addresses: fresh ones first", AddressBook.Current(new List<string> { "a", "b" }, times, t0).SequenceEqual(new[] { "b" }));
Check("addresses: newest when none is fresh", AddressBook.Current(new List<string> { "a" }, times, t0).SequenceEqual(new[] { "a" }));

// Wi-Fi over this computer's loopback, on spare ports (never the real 47474 and 47475): status packets between two
// links, a clip frame, a refused address, and a 2.5 MB file stream.
{
    using var la = new UdpLink("k", "wa", 47584);
    using var lb = new UdpLink("k", "wb", 47584);
    var got = new TaskCompletionSource<Status>();
    lb.OnStatus += st => got.TrySetResult(st);
    lb.Start();
    la.ExtraTargets.Add(System.Net.IPAddress.Loopback);
    for (int i = 0; i < 10 && !got.Task.IsCompleted; i++) { la.Send(new Status("wa", Proto.NowMs(), i + 1, true, Ear.Unknown, false, false, "buds", true)); Thread.Sleep(100); }
    Check("udp status arrives", got.Task.Wait(2000) && got.Task.Result is { Dev: "wa", Playing: true, Prof: "buds", Att: true });
    Check("udp sender address learned", lb.Allowed(System.Net.IPAddress.Loopback) && lb.PeerHostsByDev().Any(p => p.Dev == "wa"));

    using var server = new ClipTcp(47585) { Secret = "k", Allowed = a => System.Net.IPAddress.IsLoopback(a.IsIPv4MappedToIPv6 ? a.MapToIPv4() : a) };
    var frameGot = new TaskCompletionSource<byte[]>();
    server.OnFrame = f => frameGot.TrySetResult(f);
    var dir = Path.Combine(Path.GetTempPath(), "earhop-test-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(dir);
    server.AcceptFile = it => Path.Combine(dir, $".earhop-{it.Id}.part");
    var fileGot = new TaskCompletionSource<(ClipItem, string?)>();
    server.OnFile = (it, path) => fileGot.TrySetResult((it, path));
    server.Start();
    var client = new ClipTcp(47585) { Secret = "k" };
    var clipFrame = ClipCodec.Encode(new ClipItem("t1", "wa", "text", "text/plain", Proto.NowMs(), Encoding.UTF8.GetBytes("over tcp")), "k")!;
    Check("tcp frame sent", client.Send(clipFrame, System.Net.IPAddress.Loopback).Result);
    Check("tcp frame arrives whole", frameGot.Task.Wait(3000) && frameGot.Task.Result.SequenceEqual(clipFrame));
    var src = Path.Combine(dir, "source.bin");
    var bytes = new byte[2_500_000]; new Random(7).NextBytes(bytes); File.WriteAllBytes(src, bytes);
    var meta = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object> { ["name"] = "source.bin", ["size"] = bytes.Length });
    var header = ClipCodec.Encode(new ClipItem("f1", "wa", "file", "application/octet-stream", Proto.NowMs(), meta, "wb"), "k")!;
    Check("file stream sent", client.SendFile(src, header, "f1", System.Net.IPAddress.Loopback).Result);
    Check("file arrives intact", fileGot.Task.Wait(5000) && fileGot.Task.Result.Item2 is string outPath && File.ReadAllBytes(outPath).SequenceEqual(bytes)
          && fileGot.Task.Result.Item1.Id == "f1");
    var badHeader = ClipCodec.Encode(new ClipItem("f2", "wa", "file", "application/octet-stream", Proto.NowMs(), meta), "other secret")!;
    fileGot = new TaskCompletionSource<(ClipItem, string?)>();
    client.SendFile(src, badHeader, "f2", System.Net.IPAddress.Loopback).Wait();
    Check("file with a wrong secret refused", !fileGot.Task.Wait(1500) && !File.Exists(Path.Combine(dir, ".earhop-f2.part")));
    server.Allowed = _ => false;
    frameGot = new TaskCompletionSource<byte[]>();
    client.Send(clipFrame, System.Net.IPAddress.Loopback).Wait();
    Check("tcp from an unknown address refused", !frameGot.Task.Wait(1500));
    try { Directory.Delete(dir, true); } catch { }
}

HandoffSim.RunAll(Check);
HandoffSim.DefaultConnect = 7; HandoffSim.DefaultDisconnect = 1.5;
HandoffSim.RunAll((n, ok) => Check("real timing: " + n, ok));

if (failed.Count == 0) { Console.WriteLine($"selftest OK ({count} checks)"); return 0; }
foreach (var f in failed) Console.WriteLine("FAIL " + f);
return 1;
