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

// Galaxy Buds protocol, the same checks as the Mac (main.swift self test).
static byte[] BFrame(byte id, byte[] p) { int size = 1 + p.Length + 2; return new byte[] { 0xFD, (byte)(size & 0xFF), (byte)(size >> 8), id }.Concat(p).Concat(new byte[] { 0, 0, 0xDD }).ToArray(); }
static byte[] BExt(byte pl) { var p = new byte[40]; p[6] = pl; return p; }
static BudsProtocol.State BState(byte id, byte[] p) { var f = BFrame(id, p).ToList(); var st = new BudsProtocol.State(); foreach (var m in BudsProtocol.Extract(f)) st = BudsProtocol.Apply(m, st); return st; }
var bb = BFrame(0x61, BExt(0x11)).ToList(); var bm = BudsProtocol.Extract(bb);
Check("buds frame", bm.Count == 1 && bb.Count == 0);
Check("buds both", BState(0x61, BExt(0x11)).Ear == Ear.Both);
Check("buds one", BState(0x61, BExt(0x12)).Ear == Ear.One && BState(0x61, BExt(0x21)).Ear == Ear.One);
Check("buds none", BState(0x61, BExt(0x22)).Ear == Ear.None);
Check("buds case is none", BState(0x61, BExt(0x34)).Ear == Ear.None);
Check("buds partial", BudsProtocol.Extract(BFrame(0x61, BExt(0x11)).Take(6).ToList()).Count == 0);
Check("buds crc check value", BudsProtocol.Crc16(Encoding.ASCII.GetBytes("123456789")) == 0x31C3);
var encMsgs = BudsProtocol.Extract(BudsProtocol.NoiseCommand("anc").ToList());
Check("buds encode roundtrip", encMsgs.Count == 1 && encMsgs[0].Id == BudsProtocol.IdNoise && encMsgs[0].Payload.SequenceEqual(new byte[] { 1 }));
var full = new byte[40]; full[0] = 5; full[2] = 80; full[3] = 70; full[6] = 0x12; full[7] = 55; full[9] = 3; full[10] = 0x8F; full[12] = 2;
var fs = BState(0x61, full);
Check("buds full status", fs.Noise == "ambient" && fs.Eq == "dynamic" && fs.BattL == 80 && fs.BattR == 70 && fs.BattCase == 55 && fs.TouchOn == true && fs.Ear == Ear.One && fs.PlaceR == "idle");
var as1 = BudsProtocol.Apply(new BudsProtocol.Msg(BudsProtocol.IdAck, new byte[] { BudsProtocol.IdEqualizer, 4 }), new BudsProtocol.State());
as1 = BudsProtocol.Apply(new BudsProtocol.Msg(BudsProtocol.IdNoiseUpdate, new byte[] { 1 }), as1);
Check("buds ack and noise update", as1.Eq == "clear" && as1.Noise == "anc");
var tc = BudsProtocol.Extract(BudsProtocol.TouchCommand(true, new BudsProtocol.State()).ToList());
Check("buds touch command", tc.Count == 1 && tc[0].Payload.SequenceEqual(new byte[] { 1, 1, 1, 1, 1, 0, 0 }));
static byte[] Ext13(byte place) { var p = new byte[13]; p[0] = 5; p[2] = 80; p[3] = 70; p[6] = place; return p; }
static byte[] ZeroCrc(int id, byte[] p) { var f = BudsProtocol.Encode(id, p); f[^3] = 0; f[^2] = 0; return f; }
var crcA = new BudsProtocol.CrcStats();
Check("buds good checksum counted", BudsProtocol.Extract(BudsProtocol.Encode(0x61, Ext13(0x11)).ToList(), crcA).Count == 1 && crcA.Good == 1 && crcA.Enforcing);
var crcB = new BudsProtocol.CrcStats();
Check("buds bad checksum kept before verified", BudsProtocol.Extract(ZeroCrc(0x61, Ext13(0x22)).ToList(), crcB).Count == 1 && crcB.Bad == 1);
BudsProtocol.Extract(BudsProtocol.Encode(0x61, Ext13(0x11)).ToList(), crcB);
var md = BudsProtocol.Extract(ZeroCrc(0x61, Ext13(0x22)).Concat(BudsProtocol.Encode(0x61, Ext13(0x12))).ToList(), crcB);
Check("buds bad checksum dropped once verified", md.Count == 1 && BudsProtocol.Apply(md[0], new BudsProtocol.State()).Ear == Ear.One);
var fe = BudsProtocol.Encode(0x61, Ext13(0x11)); (fe[^3], fe[^2]) = (fe[^2], fe[^3]);
var crcE = new BudsProtocol.CrcStats();
Check("buds big endian checksum accepted", BudsProtocol.Extract(fe.ToList(), crcE).Count == 1 && crcE.Good == 1);
var ff = new byte[] { 0xFD, 0xFF, 0x03, 0x61 }.Concat(BudsProtocol.Encode(0x61, Ext13(0x11))).ToList();
Check("buds oversized length is junk", BudsProtocol.Extract(ff, new BudsProtocol.CrcStats()).Count == 1 && ff.Count == 0);

// Bluetooth clipboard: routing, health and chunks, the same checks as the Mac.
var rall = new[] { "phone", "tab" };
var r1 = ClipRoute.Plan("text", 100, "both", rall, new[] { "phone", "tab" }, new[] { "phone", "tab" });
Check("route: text over Bluetooth only when Bluetooth reaches", r1.Ble.SetEquals(rall) && r1.Wifi.Count == 0);
var r2 = ClipRoute.Plan("text", 100, "both", rall, new[] { "phone" }, new[] { "phone", "tab" });
Check("route: text over Wi-Fi to a device Bluetooth cannot reach", r2.Ble.SetEquals(new[] { "phone" }) && r2.Wifi.SetEquals(new[] { "tab" }));
var r3 = ClipRoute.Plan("image", 20000, "both", rall, new[] { "phone", "tab" }, new[] { "phone" });
Check("route: image over Wi-Fi, Bluetooth only without a Wi-Fi route", r3.Wifi.SetEquals(new[] { "phone" }) && r3.Ble.SetEquals(new[] { "tab" }));
var r4 = ClipRoute.Plan("image", 900000, "both", rall, new[] { "phone", "tab" }, Array.Empty<string>());
Check("route: a large image never goes over Bluetooth", r4.Ble.Count == 0 && r4.Wifi.Count == 0);
var r5 = ClipRoute.Plan("text", 100, "wifi", rall, rall, rall);
Check("route: Wi-Fi mode never uses Bluetooth", r5.Ble.Count == 0 && r5.Wifi.SetEquals(rall));
var bh = new BleHealth(); var bt0 = DateTime.UtcNow;
bh.Report("tab", false, false, bt0);
Check("ble health: one failure does not rest the device", bh.Usable("tab", bt0));
bh.Report("tab", false, false, bt0);
Check("ble health: two failures rest it 2 min", !bh.Usable("tab", bt0.AddSeconds(60)) && bh.Usable("tab", bt0.AddSeconds(121)));
bh.Report("phone", false, true, bt0);
Check("ble health: a pairing refusal stops tries for 6 hours", !bh.Usable("phone", bt0.AddHours(5)) && bh.NeedsPairing("phone"));
Check("ble health: works again after success", bh.Report("tab", true, false, bt0) != null && bh.Usable("tab", bt0));
Check("ble health: a device that worked counts as paired", bh.KnownGood("tab") && !bh.KnownGood("phone"));
bh.Paired("phone");
Check("ble health: Bluetooth from a device clears its pairing note", bh.KnownGood("phone") && !bh.NeedsPairing("phone") && bh.Usable("phone", bt0));
var bigFrame = ClipCodec.Encode(new ClipItem("a", "mac", "text", "text/plain", 1, Enumerable.Repeat((byte)65, 5000).ToArray()), "k")!;
var chunks = ClipChunks.Split(bigFrame, 172, 7);
var asm = new ClipChunks.Assembler();
byte[]? assembled = null;
foreach (var c in Enumerable.Reverse(chunks)) if (asm.Add(c, "x", DateTime.UtcNow) is byte[] w) assembled = w;
Check("chunks: split and reassembled in any order", chunks.Count == (bigFrame.Length + 171) / 172 && chunks.All(c => c.Length <= 180) && assembled != null && assembled.SequenceEqual(bigFrame));
Check("chunks: a 17 KB text fits Bluetooth", ClipRoute.Plan("text", 17073, "both", new[] { "mac" }, new[] { "mac" }, new[] { "mac" }).Ble.Contains("mac"));

// AirPods proximity beacon, the same checks as the Mac.
static byte[] Bcn(byte status) => new byte[] { 0x4C, 0x00, 0x07, 0x19, 0x01, 0x24, 0x20, status, 0x9A, 0x8F, 0x10, 0x00, 0x04 };
Check("beacon both", Beacon.Parse(Bcn(0x0B))?.Ear == Ear.Both);
Check("beacon one", Beacon.Parse(Bcn(0x03))?.Ear == Ear.One);
Check("beacon none", Beacon.Parse(Bcn(0x01))?.Ear == Ear.None);
Check("beacon model", Beacon.Parse(Bcn(0x0B))?.Model == 0x2024);
Check("beacon case level", Beacon.Parse(new byte[] { 0x4C, 0x00, 0x07, 0x19, 0x01, 0x24, 0x20, 0x0B, 0x9A, 0x85, 0x10, 0x00, 0x04 })?.CaseLevel == 5);
Check("beacon case unknown", Beacon.Parse(Bcn(0x0B))?.CaseLevel == null);
Check("beacon battery", Beacon.Parse(Bcn(0x0B)) is { BudA: 9, BudB: 10 });
Check("beacon rejects short", Beacon.Parse(new byte[] { 0x4C, 0x00, 0x07 }) == null);
Check("weaker pair nearby is ignored", Beacon.Pick(new[] { (2, -45.0), (0, -70.0) }) == 2);
Check("own second address still counts", Beacon.Pick(new[] { (2, -45.0), (1, -50.0) }) == 1);
Check("no sources gives no reading", Beacon.Pick(Array.Empty<(int, double)>()) == null);

HandoffSim.RunAll(Check);
HandoffSim.DefaultConnect = 7; HandoffSim.DefaultDisconnect = 1.5;
HandoffSim.RunAll((n, ok) => Check("real timing: " + n, ok));

if (failed.Count == 0) { Console.WriteLine($"selftest OK ({count} checks)"); return 0; }
foreach (var f in failed) Console.WriteLine("FAIL " + f);
return 1;
