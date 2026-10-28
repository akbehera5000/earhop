import AppKit
import CoreBluetooth
import CryptoKit
import Foundation
import Network

// Clipboard sync. Text goes over Bluetooth LE and Wi-Fi at the same time (the first copy to arrive wins, the other is
// recognised by its id and ignored); images go over Wi-Fi only. Every clip is encrypted with AES-256-GCM using a key
// derived from the shared secret, so neither link carries readable data.

/// One clipboard item travelling between devices.
/// One clip in the popover's recent list.
struct ClipRecent: Identifiable {
    let id = UUID()
    var sent: Bool
    var dev: String // who it came from, or who it went to
    var kind: String
    var text: String?
    var image: Data?
    var date = Date()
}

struct ClipItem {
    var id: String
    var dev: String
    var kind: String // "text" or "image"
    var mime: String
    var ts: Int64
    var data: Data
}

/// The encrypted frame, identical on Android (ClipCodec.kt):
/// "EHC1" | nonce (12) | AES-256-GCM ciphertext | tag (16), with "EHC1" as associated data.
/// Plaintext: header length (2 bytes, big endian) | header JSON | data. Key: HMAC-SHA256(secret, "earhop clip v1").
enum ClipCodec {
    static let magic = Data("EHC1".utf8)
    /// Clips older than this are refused (a recording cannot be replayed later).
    static let maxAgeMs: Int64 = 300_000

    static func key(_ secret: String) -> SymmetricKey {
        let mac = HMAC<SHA256>.authenticationCode(for: Data("earhop clip v1".utf8), using: SymmetricKey(data: Data(secret.utf8)))
        return SymmetricKey(data: Data(mac))
    }

    static func encode(_ item: ClipItem, secret: String, nonce: Data? = nil) -> Data? {
        let header: [String: Any] = ["v": 1, "id": item.id, "dev": item.dev, "kind": item.kind, "mime": item.mime, "ts": item.ts]
        guard let h = try? JSONSerialization.data(withJSONObject: header, options: [.sortedKeys]), h.count < 65536 else { return nil }
        var plain = Data([UInt8(h.count >> 8), UInt8(h.count & 0xFF)])
        plain.append(h)
        plain.append(item.data)
        do {
            let n = try nonce.map { try AES.GCM.Nonce(data: $0) } ?? AES.GCM.Nonce()
            guard let combined = try AES.GCM.seal(plain, using: key(secret), nonce: n, authenticating: magic).combined else { return nil }
            return magic + combined
        } catch { return nil }
    }

    static func decode(_ frame: Data, secret: String) -> ClipItem? {
        guard frame.count > 4 + 12 + 16, Data(frame.prefix(4)) == magic,
              let box = try? AES.GCM.SealedBox(combined: Data(frame.dropFirst(4))),
              let plain = try? AES.GCM.open(box, using: key(secret), authenticating: magic) else { return nil }
        let p = [UInt8](plain)
        guard p.count >= 2 else { return nil }
        let hl = Int(p[0]) << 8 | Int(p[1])
        guard p.count >= 2 + hl,
              let o = try? JSONSerialization.jsonObject(with: Data(p[2 ..< 2 + hl])) as? [String: Any],
              let id = o["id"] as? String, let dev = o["dev"] as? String, let kind = o["kind"] as? String,
              let mime = o["mime"] as? String, let ts = (o["ts"] as? NSNumber)?.int64Value else { return nil }
        return ClipItem(id: id, dev: dev, kind: kind, mime: mime, ts: ts, data: Data(p[(2 + hl)...]))
    }
}

/// Splits a frame into Bluetooth LE writes and puts them back together.
/// Each chunk: tag (4 bytes) | index (2) | count (2) | payload. The tag tells messages apart.
enum ClipChunks {
    static func split(_ frame: Data, payload size: Int, tag: UInt32) -> [Data] {
        let size = max(1, size)
        let count = (frame.count + size - 1) / size
        guard count > 0, count <= 0xFFFF else { return [] }
        return (0 ..< count).map { i in
            var c = Data([UInt8(tag >> 24), UInt8((tag >> 16) & 0xFF), UInt8((tag >> 8) & 0xFF), UInt8(tag & 0xFF),
                          UInt8(i >> 8), UInt8(i & 0xFF), UInt8(count >> 8), UInt8(count & 0xFF)])
            c.append(frame.subdata(in: (frame.startIndex + i * size) ..< min(frame.endIndex, frame.startIndex + (i + 1) * size)))
            return c
        }
    }

    final class Assembler {
        private var parts: [String: (count: Int, got: [Int: Data], at: Date)] = [:]

        /// Adds one chunk from a sender; returns the whole frame once every chunk has arrived.
        func add(_ chunk: Data, from sender: String, now: Date = Date()) -> Data? {
            parts = parts.filter { now.timeIntervalSince($0.value.at) < 30 }
            let b = [UInt8](chunk)
            guard b.count >= 8 else { return nil }
            let tag = "\(sender)/\(b[0]).\(b[1]).\(b[2]).\(b[3])"
            let index = Int(b[4]) << 8 | Int(b[5]), count = Int(b[6]) << 8 | Int(b[7])
            guard count > 0, index < count else { return nil }
            var entry = parts[tag] ?? (count, [:], now)
            entry.got[index] = Data(b[8...])
            entry.at = now
            if entry.got.count == entry.count {
                parts[tag] = nil
                return (0 ..< entry.count).reduce(into: Data()) { $0.append(entry.got[$1] ?? Data()) }
            }
            parts[tag] = entry
            return nil
        }
    }
}

/// Wi-Fi: a direct TCP connection to the peer, port 47475, one length prefixed frame per connection.
final class ClipTcp {
    static let defaultPort: UInt16 = 47475
    let port: UInt16
    init(port: UInt16 = ClipTcp.defaultPort) { self.port = port }
    static let maxFrame = 16 * 1024 * 1024
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "earhop.clip.tcp")
    var onFrame: ((Data) -> Void)?

    func start() {
        do {
            let l = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
            l.newConnectionHandler = { [weak self] c in self?.serve(c) }
            l.stateUpdateHandler = { s in if case .failed(let e) = s { Log.write("clip: Wi-Fi listener failed \(e)") } }
            l.start(queue: queue)
            listener = l
        } catch { Log.write("clip: Wi-Fi listener not started: \(error)") }
    }

    private func serve(_ c: NWConnection) {
        c.start(queue: queue)
        c.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] head, _, _, _ in
            guard let head, head.count == 4 else { c.cancel(); return }
            let b = [UInt8](head)
            let len = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
            guard len > 0, len <= ClipTcp.maxFrame else { c.cancel(); return }
            self?.read(c, remaining: len, into: Data())
        }
        queue.asyncAfter(deadline: .now() + 30) { c.cancel() }
    }

    private func read(_ c: NWConnection, remaining: Int, into buf: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: min(remaining, 65536)) { [weak self] data, _, done, err in
            var b = buf
            if let data { b.append(data) }
            let left = remaining - (data?.count ?? 0)
            if left <= 0 { c.cancel(); self?.onFrame?(b); return }
            if done || err != nil { c.cancel(); return }
            self?.read(c, remaining: left, into: b)
        }
    }

    func send(_ frame: Data, to host: String) {
        let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        let n = frame.count
        var msg = Data([UInt8((n >> 24) & 0xFF), UInt8((n >> 16) & 0xFF), UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)])
        msg.append(frame)
        c.start(queue: queue)
        c.send(content: msg, completion: .contentProcessed { err in
            if let err { Log.write("clip: Wi-Fi send to \(host) failed: \(err)") }
            c.cancel()
        })
        queue.asyncAfter(deadline: .now() + 20) { c.cancel() }
    }
}

/// Bluetooth LE: a small GATT service other devices write clips into, and a client that connects to a peer for a
/// moment, writes the chunks and disconnects. Peers are found through their status advertisements.
final class ClipBle: NSObject, CBPeripheralManagerDelegate, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "EAC1B000-5EED-4C1E-8A00-000000000001")
    static let inbox = CBUUID(string: "EAC1B001-5EED-4C1E-8A00-000000000001")
    /// Text frames above this size go over Wi-Fi only.
    static let maxFrame = 64 * 1024

    private var server: CBPeripheralManager!
    private var central: CBCentralManager!
    private let assembler = ClipChunks.Assembler()
    var onFrame: ((Data) -> Void)?

    private struct Job { let dev: String; let id: UUID; let chunks: [Data]; let attempt: Int }
    /// The newest Bluetooth identifier of a device, for a retry after a failed send.
    var freshId: (String) -> UUID? = { _ in nil }
    private var jobs: [Job] = []
    private var current: (job: Job, peripheral: CBPeripheral, next: Int)?
    private var jobTimer: Timer?

    override init() {
        super.init()
        server = CBPeripheralManager(delegate: self, queue: .main)
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: server

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        guard p.state == .poweredOn else { return }
        let ch = CBMutableCharacteristic(type: ClipBle.inbox, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
        let svc = CBMutableService(type: ClipBle.service, primary: true)
        svc.characteristics = [ch]
        p.removeAllServices()
        p.add(svc)
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests {
            guard r.characteristic.uuid == ClipBle.inbox, let v = r.value else { continue }
            if let frame = assembler.add(v, from: r.central.identifier.uuidString) { onFrame?(frame) }
        }
        if let first = requests.first { p.respond(to: first, withResult: .success) }
    }

    // MARK: client

    func centralManagerDidUpdateState(_ c: CBCentralManager) {}

    /// Sends a frame to each peer (by the identifier its status advertisement came from), one after the other.
    func send(_ frame: Data, to peers: [(dev: String, id: UUID)]) {
        guard frame.count <= ClipBle.maxFrame, central.state == .poweredOn else { return }
        let tag = UInt32.random(in: 0 ... UInt32.max)
        // 180 byte writes fit the usual negotiated MTU of 185 on both Android and macOS.
        for p in peers { jobs.append(Job(dev: p.dev, id: p.id, chunks: ClipChunks.split(frame, payload: 172, tag: tag), attempt: 1)) }
        nextJob()
    }

    private var stage = ""

    private func nextJob() {
        guard current == nil, !jobs.isEmpty else { return }
        let job = jobs.removeFirst()
        guard let p = central.retrievePeripherals(withIdentifiers: [job.id]).first else {
            Log.write("clip: Bluetooth peer not reachable right now"); nextJob(); return
        }
        current = (job, p, 0)
        p.delegate = self
        stage = "connecting"
        central.connect(p)
        jobTimer?.invalidate()
        jobTimer = Timer.scheduledTimer(withTimeInterval: 8, repeats: false) { [weak self] _ in
            self?.finish(ok: false, why: "timed out while \(self?.stage ?? "")")
        }
    }

    private func finish(ok: Bool, why: String) {
        jobTimer?.invalidate()
        if let c = current {
            central.cancelPeripheralConnection(c.peripheral)
            Log.write("clip: Bluetooth send to \(c.job.dev) \(ok ? "done" : "failed (\(why))")")
            // One retry with the device's newest address: some Android devices change it whenever they restart advertising.
            if !ok, c.job.attempt == 1, let id = freshId(c.job.dev) {
                jobs.append(Job(dev: c.job.dev, id: id, chunks: c.job.chunks, attempt: 2))
                Log.write("clip: retrying \(c.job.dev) with its newest Bluetooth address\(id == c.job.id ? " (unchanged)" : "")")
            }
        }
        current = nil
        nextJob()
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) { stage = "finding the clipboard service"; p.discoverServices([ClipBle.service]) }
    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { finish(ok: false, why: "could not connect") }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == ClipBle.service }) else { finish(ok: false, why: "no clipboard service"); return }
        p.discoverCharacteristics([ClipBle.inbox], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) { stage = "writing"; writeNext(p) }

    private func writeNext(_ p: CBPeripheral) {
        guard let c = current, let ch = p.services?.first(where: { $0.uuid == ClipBle.service })?
            .characteristics?.first(where: { $0.uuid == ClipBle.inbox }) else { finish(ok: false, why: "no inbox"); return }
        if c.next >= c.job.chunks.count { finish(ok: true, why: ""); return }
        p.writeValue(c.job.chunks[c.next], for: ch, type: .withResponse)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        if error != nil { finish(ok: false, why: "write refused"); return }
        current?.next += 1
        writeNext(p)
    }
}

/// Watches this Mac's clipboard, sends new items to the other devices and applies items that arrive.
final class ClipSync {
    var cfg: Config
    let tcp = ClipTcp()
    let ble = ClipBle()
    /// Wi-Fi addresses of the other devices (from their signed status packets) and their Bluetooth identifiers.
    var wifiPeers: () -> [(dev: String, host: String)] = { [] }
    var blePeers: () -> [(dev: String, id: UUID)] = { [] }
    var onEvent: ((String) -> Void)?
    var onRecord: ((ClipRecent) -> Void)?
    /// Sync is paused until then (set from the popover).
    var pausedUntil = Date.distantPast
    private var paused: Bool { pausedUntil > Date() }

    private var lastChange = NSPasteboard.general.changeCount
    private var ownWrite = -1
    private var seen: [String] = []
    private let lock = NSLock()
    static let maxText = 1024 * 1024
    static let maxImage = 10 * 1024 * 1024

    init(cfg: Config) { self.cfg = cfg }

    func start() {
        tcp.onFrame = { [weak self] f in self?.receive(f, via: "Wi-Fi") }
        ble.onFrame = { [weak self] f in self?.receive(f, via: "Bluetooth") }
        tcp.start()
    }

    /// Called about twice a second from the main loop.
    func poll() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChange else { return }
        lastChange = pb.changeCount
        guard cfg.clipSync, cfg.clipAuto, !paused, pb.changeCount != ownWrite else { return }
        sendCurrent()
    }

    /// Send clipboard now (popover button), also when automatic sending is off.
    func sendNow() {
        guard cfg.clipSync else { onEvent?("Clipboard sync is off"); return }
        lastChange = NSPasteboard.general.changeCount
        sendCurrent(manual: true)
    }

    private func sendCurrent(manual: Bool = false) {
        let pb = NSPasteboard.general
        let types = Set(pb.types ?? [])
        // Password managers mark secrets as concealed or transient: never send those.
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
            || types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) {
            Log.write("clip: skipped a concealed (password) item")
            return
        }
        if let s = pb.string(forType: .string), !s.isEmpty {
            let d = Data(s.utf8)
            guard d.count <= ClipSync.maxText else { Log.write("clip: text too large to send (\(d.count) bytes)"); return }
            send(kind: "text", mime: "text/plain", data: d)
        } else if cfg.clipImages, let img = NSImage(pasteboard: pb), let tiff = img.tiffRepresentation,
                  let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
            guard png.count <= ClipSync.maxImage else { Log.write("clip: image too large to send (\(png.count / 1024) KB)"); return }
            send(kind: "image", mime: "image/png", data: png)
        } else if manual { onEvent?("Nothing to send") }
    }

    private func send(kind: String, mime: String, data: Data) {
        let id = (0 ..< 8).map { _ in String(format: "%02x", UInt8.random(in: 0 ... 255)) }.joined()
        let item = ClipItem(id: id, dev: cfg.deviceName, kind: kind, mime: mime, ts: Proto.nowMs(), data: data)
        guard let frame = ClipCodec.encode(item, secret: cfg.secret) else { return }
        remember(id)
        let skip = Set(cfg.clipExclude)
        let wifi = wifiPeers().filter { !skip.contains($0.dev) }.map(\.host)
        var links: [String] = []
        // The clipboard's own method (Settings, Clipboard). Text: Bluetooth LE and Wi-Fi at the same time. Images: Wi-Fi only.
        var bleCarries = false
        if kind == "text", cfg.clipTransport != "wifi", frame.count <= ClipBle.maxFrame {
            let peers = blePeers().filter { !skip.contains($0.dev) }
            if !peers.isEmpty { ble.send(frame, to: peers); links.append("Bluetooth"); bleCarries = true }
        }
        // Wi-Fi as well, unless "Bluetooth" is chosen and Bluetooth can carry this clip (images and text over 64 KB cannot).
        if cfg.clipTransport != "ble" || !bleCarries {
            for h in wifi { tcp.send(frame, to: h) }
            if !wifi.isEmpty { links.append("Wi-Fi") }
        }
        let what = kind == "text" ? "text (\(data.count) bytes)" : "image (\(data.count / 1024) KB)"
        if links.isEmpty {
            Log.write("clip: \(what) not sent, no other device \(kind == "image" ? "on Wi-Fi" : "in reach")")
            onEvent?("Not sent, no device in reach")
        } else {
            Log.write("clip: sent \(what) over \(links.joined(separator: " and "))")
            onEvent?("Sent \(kind == "text" ? "text" : "an image")")
            let to = (blePeers().map(\.dev) + wifiPeers().map(\.dev)).filter { !skip.contains($0) }
            let names = Array(Set(to)).sorted().map(DeviceName.pretty).joined(separator: ", ")
            onRecord?(ClipRecent(sent: true, dev: names.isEmpty ? "your devices" : names, kind: kind,
                                 text: kind == "text" ? String(data: data, encoding: .utf8) : nil, image: kind == "image" ? data : nil))
        }
    }

    /// Puts a recent clip back on this Mac's clipboard. It counts as a new copy, so it is sent again when automatic.
    func recopy(_ r: ClipRecent) {
        let pb = NSPasteboard.general
        if let t = r.text { pb.clearContents(); pb.setString(t, forType: .string) }
        else if let d = r.image, let img = NSImage(data: d) { pb.clearContents(); pb.writeObjects([img]) }
    }

    private func remember(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        seen.append(id)
        if seen.count > 100 { seen.removeFirst(seen.count - 100) }
    }

    private func receive(_ frame: Data, via: String) {
        guard cfg.clipSync, cfg.clipReceive, !paused, let item = ClipCodec.decode(frame, secret: cfg.secret) else { return }
        guard item.dev != cfg.deviceName, abs(Proto.nowMs() - item.ts) <= ClipCodec.maxAgeMs else { return }
        lock.lock()
        let dup = seen.contains(item.id)
        lock.unlock()
        if dup { return } // the copy from the other link already arrived
        remember(item.id)
        DispatchQueue.main.async { self.apply(item, via: via) }
    }

    private func apply(_ item: ClipItem, via: String) {
        let pb = NSPasteboard.general
        if item.kind == "text", let s = String(data: item.data, encoding: .utf8) {
            pb.clearContents()
            pb.setString(s, forType: .string)
        } else if item.kind == "image", cfg.clipImages, let img = NSImage(data: item.data) {
            pb.clearContents()
            pb.writeObjects([img])
        } else { return }
        ownWrite = pb.changeCount
        lastChange = pb.changeCount
        Log.write("clip: received \(item.kind) from \(item.dev) over \(via)")
        onRecord?(ClipRecent(sent: false, dev: DeviceName.pretty(item.dev), kind: item.kind,
                             text: item.kind == "text" ? String(data: item.data, encoding: .utf8) : nil, image: item.kind == "image" ? item.data : nil))
        onEvent?("Received \(item.kind == "text" ? "text" : "an image") from \(item.dev)")
        if cfg.clipNotify {
            let preview = item.kind == "text" ? String((String(data: item.data, encoding: .utf8) ?? "").prefix(80)) : "An image"
            Notifier.post("Clipboard from \(DeviceName.pretty(item.dev))", preview)
        }
    }
}
