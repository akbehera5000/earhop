import AppKit
import CoreBluetooth
import CryptoKit
import Foundation
import Network

// Clipboard sync. Text goes over Bluetooth LE and Wi-Fi at the same time (the first copy to arrive wins, the other is
// recognised by its id and ignored); images go over Wi-Fi only. Every clip is encrypted with AES-256-GCM using a key
// derived from the shared secret, so neither link carries readable data.

/// Text that looks like a password: one word, 8 to 64 characters, at least 3 of lower case, upper case, digits and
/// symbols; links and email addresses never count. Used only when "Skip password-like text" is on (off by default).
/// Same rule as PasswordLike in ClipCodec.kt.
enum PasswordLike {
    static func looks(_ t: String) -> Bool {
        guard (8 ... 64).contains(t.count), !t.contains(where: { $0.isWhitespace }) else { return false }
        if t.contains("://") || t.lowercased().hasPrefix("www.") { return false }
        if t.range(of: "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", options: .regularExpression) != nil { return false }
        let kinds = [t.contains { $0.isLowercase }, t.contains { $0.isUppercase }, t.contains { $0.isNumber },
                     t.contains { !$0.isLetter && !$0.isNumber }].filter { $0 }.count
        return kinds >= 3
    }
}

/// Pinned clips, the only ones saved: encrypted with the shared secret (a ClipCodec frame, kind "pins") in
/// ~/.earhop/pins.bin. Text, links and file names only; unpinned history stays in memory.
enum PinStore {
    static var file: URL { Config.dir.appendingPathComponent("pins.bin") }

    static func load(secret: String) -> [ClipRecent] {
        guard let d = try? Data(contentsOf: file), let item = ClipCodec.decode(d, secret: secret),
              let arr = try? JSONSerialization.jsonObject(with: item.data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            guard let text = o["text"] as? String, let kind = o["kind"] as? String else { return nil }
            var r = ClipRecent(sent: o["sent"] as? Bool ?? false, dev: o["dev"] as? String ?? "", kind: kind, text: text)
            r.date = Date(timeIntervalSince1970: o["at"] as? Double ?? 0)
            r.pinned = true
            return r
        }
    }

    static func save(_ pins: [ClipRecent], secret: String) {
        guard !Config.readOnly else { return }
        let arr: [[String: Any]] = pins.compactMap { r in
            r.text.map { ["text": $0, "kind": r.kind, "dev": r.dev, "sent": r.sent, "at": r.date.timeIntervalSince1970] }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: arr),
              let frame = ClipCodec.encode(ClipItem(id: "pins", dev: "", kind: "pins", mime: "application/json", ts: Proto.nowMs(), data: data), secret: secret)
        else { return }
        try? frame.write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
}

/// One clip in the popover's recent list.
struct ClipRecent: Identifiable {
    let id = UUID()
    /// The clip's id on the wire, so delivery receipts can be matched to it.
    var clipId = ""
    /// For sent clips: who confirmed it ("Delivered to Mac ✓, Tablet ✓"), who is still waited for, who never answered.
    var receipt = ""
    var sent: Bool
    var dev: String // who it came from, or who it went to
    var kind: String
    var text: String?
    var image: Data?
    var date = Date()
    var pinned = false
}

struct ClipItem {
    var id: String
    var dev: String
    /// "text" and "image" are clips; "ack" (a delivery receipt, data = clip id), "diag-req" and "diag" (diagnostics)
    /// are control messages between devices.
    var kind: String
    var mime: String
    var ts: Int64
    var data: Data
    /// The one device this is for, or "" for every device.
    var to: String = ""
}

/// Delivery receipts for sent clips: every device that applies a clip answers with an "ack". Devices that did not
/// answer get the clip again after 5 and 15 s (quietly; the clip id stops a second copy there), and after 60 s count as
/// not delivered. Same rule as Receipts in ClipCodec.kt. Pure, tested by the self test.
final class Receipts {
    struct Entry { var targets: Set<String>; var delivered: Set<String> = []; var sentAt: Date; var retries = 0 }
    private(set) var entries: [String: Entry] = [:]
    static let retryAfter: [TimeInterval] = [5, 15]
    static let giveUp: TimeInterval = 60

    func track(_ id: String, targets: Set<String>, now: Date) {
        entries = entries.filter { now.timeIntervalSince($0.value.sentAt) < 600 }
        entries[id] = Entry(targets: targets, sentAt: now)
    }

    /// Records a receipt; true when it was new.
    @discardableResult
    func ack(_ id: String, from dev: String) -> Bool {
        guard var e = entries[id], e.targets.contains(dev), !e.delivered.contains(dev) else { return false }
        e.delivered.insert(dev)
        entries[id] = e
        return true
    }

    /// Clips to send again now, and to whom.
    func due(now: Date) -> [(id: String, devs: [String])] {
        var out: [(String, [String])] = []
        for (id, var e) in entries {
            let waiting = e.targets.subtracting(e.delivered)
            guard !waiting.isEmpty, e.retries < Receipts.retryAfter.count,
                  now.timeIntervalSince(e.sentAt) >= Receipts.retryAfter[e.retries] else { continue }
            e.retries += 1
            entries[id] = e
            out.append((id, waiting.sorted()))
        }
        return out
    }

    func state(_ id: String, now: Date) -> (delivered: [String], waiting: [String], failed: [String]) {
        guard let e = entries[id] else { return ([], [], []) }
        let rest = e.targets.subtracting(e.delivered).sorted()
        let over = now.timeIntervalSince(e.sentAt) >= Receipts.giveUp
        return (e.delivered.sorted(), over ? [] : rest, over ? rest : [])
    }

    /// "Delivered to Mac ✓, Tablet ✓", plus who is still waited for or did not answer.
    func words(_ id: String, now: Date) -> String {
        let st = state(id, now: now)
        var parts: [String] = []
        if !st.delivered.isEmpty { parts.append("Delivered to " + st.delivered.map { DeviceName.pretty($0) + " ✓" }.joined(separator: ", ")) }
        if !st.waiting.isEmpty { parts.append("sending to " + st.waiting.map(DeviceName.pretty).joined(separator: ", ")) }
        if !st.failed.isEmpty { parts.append("not delivered to " + st.failed.map(DeviceName.pretty).joined(separator: ", ")) }
        guard let first = parts.first else { return "" }
        return ([first.prefix(1).uppercased() + first.dropFirst()] + parts.dropFirst()).joined(separator: "; ")
    }
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
        var header: [String: Any] = ["v": 1, "id": item.id, "dev": item.dev, "kind": item.kind, "mime": item.mime, "ts": item.ts]
        if !item.to.isEmpty { header["to"] = item.to }
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
        return ClipItem(id: id, dev: dev, kind: kind, mime: mime, ts: ts, data: Data(p[(2 + hl)...]), to: o["to"] as? String ?? "")
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
    /// True while the Wi-Fi clipboard port is open (status page).
    private(set) var listening = false
    private let queue = DispatchQueue(label: "earhop.clip.tcp")
    var onFrame: ((Data) -> Void)?

    func start() {
        do {
            let l = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
            l.newConnectionHandler = { [weak self] c in self?.serve(c) }
            l.stateUpdateHandler = { [weak self] s in
                if case .failed(let e) = s { Log.write("clip: Wi-Fi listener failed \(e)") }
                if case .ready = s { self?.listening = true } else if case .failed = s { self?.listening = false } else if case .cancelled = s { self?.listening = false }
            }
            l.start(queue: queue)
            listener = l
        } catch { Log.write("clip: Wi-Fi listener not started: \(error)") }
    }

    /// File streams: the secret, where to write an accepted file (nil refuses), and what to do when it is complete.
    var secret = ""
    var acceptFile: ((ClipItem) -> URL?)?
    var onFile: ((ClipItem, URL?) -> Void)?

    /// Whether a connecting address belongs to one of the user's devices (learned from a signed status in the last 10
    /// minutes). Anything else on the network is closed at once, so it cannot tie the listener up with junk.
    var allowed: (String) -> Bool = { _ in true }
    private var open = 0
    static let maxOpen = 4

    private func serve(_ c: NWConnection) {
        var host = ""
        if case .hostPort(let h, _) = c.endpoint {
            host = "\(h)".components(separatedBy: "%").first ?? ""
            if host.hasPrefix("::ffff:") { host = String(host.dropFirst(7)) } // an IPv4 peer on the dual-stack listener
        }
        guard allowed(host), open < ClipTcp.maxOpen else { c.cancel(); return }
        open += 1
        c.stateUpdateHandler = { [weak self] st in
            switch st { case .cancelled, .failed: self?.open -= 1; c.stateUpdateHandler = nil; default: break }
        }
        c.start(queue: queue)
        // The length must arrive within 10 s.
        final class Flag { var got = false; var isFile = false }
        let headGot = Flag()
        queue.asyncAfter(deadline: .now() + 10) { if !headGot.got { c.cancel() } }
        c.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] head, _, _, _ in
            headGot.got = true
            guard let head, head.count == 4 else { c.cancel(); return }
            // A file stream (FileTransfer.swift) instead of one clip frame.
            if head == FileStream.magic, let self, let accept = self.acceptFile, let done = self.onFile {
                headGot.isFile = true
                self.serveFile(c, secret: self.secret, accept: accept, done: done)
                return
            }
            let b = [UInt8](head)
            let len = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
            guard len > 0, len <= ClipTcp.maxFrame else { c.cancel(); return }
            self?.read(c, remaining: len, into: Data())
        }
        queue.asyncAfter(deadline: .now() + 30) { if !headGot.isFile { c.cancel() } }
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
    /// Read: the device's name (v4 advertisements carry only an id).
    static let nameChar = CBUUID(string: "EAC1B002-5EED-4C1E-8A00-000000000001")
    /// Text frames above this size go over Wi-Fi only.
    static let maxFrame = 64 * 1024

    private var server: CBPeripheralManager!
    private var central: CBCentralManager!
    private let assembler = ClipChunks.Assembler()
    var onFrame: ((Data) -> Void)?

    /// A send of chunks to a device, or (lookup set) reading the name of the device behind a Bluetooth LE id.
    private struct Job { let dev: String; let id: UUID; let chunks: [Data]; let attempt: Int; var lookup: Int? = nil }
    private var lookedUp: [Int: Date] = [:]

    /// Reads the name of an unknown device id once (at most every 60 s per id) and adds it to the name book.
    func lookupName(id devId: Int, peripheral: UUID) {
        if let at = lookedUp[devId], Date().timeIntervalSince(at) < 60 { return }
        lookedUp[devId] = Date()
        jobs.append(Job(dev: "id \(String(format: "%04x", devId))", id: peripheral, chunks: [], attempt: 2, lookup: devId))
        nextJob()
    }
    /// The newest Bluetooth identifier of a device, for a retry after a failed send.
    var freshId: (String) -> UUID? = { _ in nil }
    private var jobs: [Job] = []
    private var current: (job: Job, peripheral: CBPeripheral, next: Int)?
    private var jobTimer: Timer?

    /// The shared secret, to check a name read from another device against its id.
    var secret = ""

    override init() {
        super.init()
        server = CBPeripheralManager(delegate: self, queue: .main)
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: server

    /// This device's name, served by the name characteristic so others can map its Bluetooth LE id to it.
    var selfName = ""
    /// True once macOS confirmed the clipboard service was added (status page).
    private(set) var serviceUp = false
    var centralOn: Bool { central.state == .poweredOn }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        serviceUp = error == nil
        if let error { Log.write("clip: Bluetooth clipboard service not added: \(error.localizedDescription)") }
    }

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        guard p.state == .poweredOn else { serviceUp = false; return }
        // Both need an encrypted link: the first connection between two devices asks once to pair them. Paired devices
        // recognise each other's changing Bluetooth addresses, so a send never goes to an address that is gone.
        let ch = CBMutableCharacteristic(type: ClipBle.inbox, properties: [.write, .writeWithoutResponse], value: nil,
                                         permissions: [.writeEncryptionRequired])
        let name = CBMutableCharacteristic(type: ClipBle.nameChar, properties: [.read], value: nil, permissions: [.readEncryptionRequired])
        let svc = CBMutableService(type: ClipBle.service, primary: true)
        svc.characteristics = [ch, name]
        p.removeAllServices()
        p.add(svc)
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveRead r: CBATTRequest) {
        guard r.characteristic.uuid == ClipBle.nameChar else { p.respond(to: r, withResult: .attributeNotFound); return }
        let v = Data(selfName.utf8)
        guard r.offset <= v.count else { p.respond(to: r, withResult: .invalidOffset); return }
        r.value = v.subdata(in: r.offset ..< v.count)
        p.respond(to: r, withResult: .success)
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
        var job = jobs.removeFirst()
        // Android devices may take a new Bluetooth address each minute: use the newest one seen, not the one from when
        // the clip was queued (sends to other devices may have run in between).
        if job.attempt == 1, job.lookup == nil, let id = freshId(job.dev), id != job.id { job = Job(dev: job.dev, id: id, chunks: job.chunks, attempt: 1) }
        guard let p = central.retrievePeripherals(withIdentifiers: [job.id]).first else {
            Log.write("clip: Bluetooth peer not reachable right now"); nextJob(); return
        }
        current = (job, p, 0)
        p.delegate = self
        stage = "connecting"
        central.connect(p)
        jobTimer?.invalidate()
        armTimer(6)
    }

    /// Connecting gets 6 s; once connected the rest gets 30 s, enough to accept the one-time pairing request.
    private func armTimer(_ seconds: TimeInterval) {
        jobTimer?.invalidate()
        jobTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.finish(ok: false, why: "timed out while \(self?.stage ?? "")")
        }
    }

    private func finish(ok: Bool, why: String) {
        jobTimer?.invalidate()
        if let c = current {
            central.cancelPeripheralConnection(c.peripheral)
            if c.job.lookup != nil { if !ok { Log.write("ble: could not read the name of \(c.job.dev) (\(why))") }; current = nil; nextJob(); return }
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

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        stage = "finding the clipboard service"; armTimer(30); p.discoverServices([ClipBle.service])
    }
    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { finish(ok: false, why: "could not connect") }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == ClipBle.service }) else {
            // Usually EarHop on that device lost its Bluetooth clipboard service (its Bluetooth restarted); it sets it up again.
            let found = (p.services ?? []).map(\.uuid.uuidString).joined(separator: ", ")
            finish(ok: false, why: "no clipboard service\(error.map { ", \($0.localizedDescription)" } ?? "")\(found.isEmpty ? "" : ", found \(found)")")
            return
        }
        p.discoverCharacteristics([ClipBle.inbox, ClipBle.nameChar], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        if current?.job.lookup != nil {
            guard let ch = s.characteristics?.first(where: { $0.uuid == ClipBle.nameChar }) else { finish(ok: false, why: "no name"); return }
            stage = "reading the name (accept the pairing request if one appears)"
            p.readValue(for: ch)
            return
        }
        stage = "writing (accept the pairing request if one appears)"; writeNext(p)
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let want = current?.job.lookup, ch.uuid == ClipBle.nameChar else { return }
        if let error { finish(ok: false, why: error.localizedDescription); return }
        let name = String(data: ch.value ?? Data(), encoding: .utf8) ?? ""
        // Only a name that really belongs to this id (by the shared secret) is learned.
        if DeviceNames.valid(name), DeviceNames.id(name, secret: secret) == want {
            if NameBook.shared.add(name) { Log.write("ble: learned the device name \(name)") }
            finish(ok: true, why: "")
        } else { finish(ok: false, why: "name does not match its id") }
    }

    private func writeNext(_ p: CBPeripheral) {
        guard let c = current, let ch = p.services?.first(where: { $0.uuid == ClipBle.service })?
            .characteristics?.first(where: { $0.uuid == ClipBle.inbox }) else { finish(ok: false, why: "no inbox"); return }
        if c.next >= c.job.chunks.count { finish(ok: true, why: ""); return }
        p.writeValue(c.job.chunks[c.next], for: ch, type: .withResponse)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        if let error {
            let e = error as NSError
            let auth = e.domain == CBATTErrorDomain && [CBATTError.insufficientAuthentication.rawValue, CBATTError.insufficientEncryption.rawValue].contains(e.code)
            finish(ok: false, why: auth ? "not paired: accept the pairing request on both devices" : "write refused: \(e.localizedDescription)")
            return
        }
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
    /// Other control messages (battery, notices, volume, settings): handled by the app.
    var onControl: ((ClipItem) -> Void)?
    /// A delivery receipt changed for a sent clip: (clip id, words for the recent list).
    var onReceipt: ((String, String) -> Void)?
    /// Another device asked for diagnostics: return this Mac's part (status and the end of its log).
    var diagnostics: () -> String = { "" }
    /// A diagnostics reply arrived: (device, text).
    var onDiagnostics: ((String, String) -> Void)?
    let receipts = Receipts()
    /// Sent clips kept for a quiet retry until every device confirmed them (at most a minute).
    private var outbox: [String: (frame: Data, kind: String, at: Date)] = [:]
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
        tcp.secret = cfg.secret
        tcp.acceptFile = { [weak self] item in self?.acceptFile(item) }
        tcp.onFile = { [weak self] item, url in DispatchQueue.main.async { self?.fileDone(item, url) } }
        tcp.onFrame = { [weak self] f in self?.receive(f, via: "Wi-Fi") }
        ble.onFrame = { [weak self] f in self?.receive(f, via: "Bluetooth") }
        tcp.start()
    }

    /// Called about twice a second from the main loop.
    func poll() {
        retryUnconfirmed()
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChange else { return }
        lastChange = pb.changeCount
        guard cfg.clipSync, cfg.clipAuto, !paused, pb.changeCount != ownWrite else { return }
        // Copies made in an excluded app (Settings, Clipboard, Exclude apps) stay on this Mac.
        if let app = NSWorkspace.shared.frontmostApplication?.bundleIdentifier, cfg.clipExcludeApps.contains(app) {
            Log.write("clip: not sent, copied in an excluded app")
            return
        }
        sendCurrent()
    }

    /// Sends unconfirmed clips again to the devices that have not answered (after 5 and 15 s), and refreshes the words.
    private func retryUnconfirmed() {
        let now = Date()
        for (id, devs) in receipts.due(now: now) {
            guard let o = outbox[id] else { continue }
            deliver(o.frame, kind: o.kind, to: Set(devs))
            Log.write("clip: no receipt from \(devs.joined(separator: ", ")) yet, sending again")
        }
        for (id, o) in outbox where now.timeIntervalSince(o.at) >= Receipts.giveUp {
            outbox[id] = nil
            onReceipt?(id, receipts.words(id, now: now))
            let st = receipts.state(id, now: now)
            if !st.failed.isEmpty { Log.write("clip: not delivered to \(st.failed.joined(separator: ", ")) (no receipt in 60 s)") }
        }
    }

    /// Sends a frame to the given devices (nil: every device not excluded) over the clipboard's method: text and small
    /// control messages over Bluetooth LE and Wi-Fi, larger items over Wi-Fi. Returns the links used.
    @discardableResult
    private func deliver(_ frame: Data, kind: String, to only: Set<String>?) -> [String] {
        let skip = Set(cfg.clipExclude)
        func wanted(_ d: String) -> Bool { only.map { $0.contains(d) } ?? !skip.contains(d) }
        let wifi = wifiPeers().filter { wanted($0.dev) }.map(\.host)
        var links: [String] = []
        var bleCarries = false
        if kind != "image", cfg.clipTransport != "wifi", frame.count <= ClipBle.maxFrame {
            let peers = blePeers().filter { wanted($0.dev) }
            if !peers.isEmpty { ble.send(frame, to: peers); links.append("Bluetooth"); bleCarries = true }
        }
        // Wi-Fi as well, unless "Bluetooth" is chosen and Bluetooth can carry this (images and over 64 KB cannot).
        if cfg.clipTransport != "ble" || !bleCarries {
            for h in wifi { tcp.send(frame, to: h) }
            if !wifi.isEmpty { links.append("Wi-Fi") }
        }
        return links
    }

    /// A control message (receipt or diagnostics) to one device, or to all when `to` is nil.
    func sendControl(kind: String, data: Data, to dev: String?) {
        let id = (0 ..< 8).map { _ in String(format: "%02x", UInt8.random(in: 0 ... 255)) }.joined()
        let item = ClipItem(id: id, dev: cfg.deviceName, kind: kind, mime: "application/octet-stream", ts: Proto.nowMs(), data: data, to: dev ?? "")
        guard let frame = ClipCodec.encode(item, secret: cfg.secret) else { return }
        remember(id)
        deliver(frame, kind: kind, to: dev.map { [$0] })
    }

    // MARK: files and links

    /// Sends files (any size, Wi-Fi only) to the given devices (nil: every device not excluded), one stream per device.
    func sendFiles(_ urls: [URL], to only: Set<String>?) {
        let skip = Set(cfg.clipExclude)
        let byDev = Dictionary(grouping: wifiPeers(), by: \.dev)
        let devs = byDev.keys.filter { d in d != cfg.deviceName && (only.map { set in set.contains(d) } ?? !skip.contains(d)) }
        guard !devs.isEmpty else { onEvent?("Not sent: no device on Wi-Fi"); Log.write("file: not sent, no device on Wi-Fi"); return }
        for url in urls {
            let size = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
            let name = FileStream.safeName(url.lastPathComponent)
            let id = (0 ..< 8).map { _ in String(format: "%02x", UInt8.random(in: 0 ... 255)) }.joined()
            receipts.track(id, targets: Set(devs), now: Date())
            onRecord?(ClipRecent(clipId: id, receipt: "Sending to " + devs.sorted().map(DeviceName.pretty).joined(separator: ", "), sent: true,
                                 dev: devs.sorted().map(DeviceName.pretty).joined(separator: ", "), kind: "file", text: name))
            for dev in devs {
                guard let host = byDev[dev]?.first?.host else { continue }
                let meta = (try? JSONSerialization.data(withJSONObject: ["name": name, "size": size])) ?? Data()
                let item = ClipItem(id: id, dev: cfg.deviceName, kind: "file", mime: "application/octet-stream", ts: Proto.nowMs(), data: meta, to: dev)
                guard let header = ClipCodec.encode(item, secret: cfg.secret) else { continue }
                Log.write("file: sending \(name) (\(size / 1024) KB) to \(dev)")
                tcp.sendFile(url, header: header, id: id, secret: cfg.secret, to: host) { ok in
                    Log.write("file: \(name) to \(dev) \(ok ? "sent, waiting for the receipt" : "failed")")
                }
            }
        }
        onEvent?("Sending \(urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files")")
    }

    /// Where an incoming file goes while it arrives (Downloads, hidden part file), or nil to refuse it.
    private func acceptFile(_ item: ClipItem) -> URL? {
        guard cfg.clipSync, cfg.clipReceive, !paused, item.dev != cfg.deviceName, item.to.isEmpty || item.to == cfg.deviceName,
              ClockGuard.shared.fresh(dev: item.dev, senderMs: item.ts, nowMs: Proto.nowMs(), maxAgeMs: ClipCodec.maxAgeMs) else { return nil }
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? Config.dir
        return dir.appendingPathComponent(".earhop-\(item.id).part")
    }

    private func fileDone(_ item: ClipItem, _ part: URL?) {
        let meta = (try? JSONSerialization.jsonObject(with: item.data) as? [String: Any]) ?? [:]
        let name = FileStream.safeName(meta["name"] as? String ?? "file")
        guard let part else { Log.write("file: \(name) from \(item.dev) broke off"); return }
        let dest = FileStream.freePath(part.deletingLastPathComponent(), name)
        do { try FileManager.default.moveItem(at: part, to: dest) } catch { Log.write("file: could not save \(name): \(error.localizedDescription)"); return }
        Log.write("file: received \(dest.lastPathComponent) from \(item.dev)")
        sendControl(kind: "ack", data: Data(item.id.utf8), to: item.dev)
        onRecord?(ClipRecent(clipId: item.id, sent: false, dev: DeviceName.pretty(item.dev), kind: "file", text: dest.lastPathComponent))
        onEvent?("Received \(dest.lastPathComponent)")
        Notifier.post("\(dest.lastPathComponent) from \(DeviceName.pretty(item.dev))", "Saved in Downloads")
    }

    /// Opens a link on another device ("continue on"): http and https only.
    func sendLink(_ url: String, to dev: String) {
        guard let u = URL(string: url), ["http", "https"].contains(u.scheme?.lowercased() ?? "") else { onEvent?("Not a web link"); return }
        let id = (0 ..< 8).map { _ in String(format: "%02x", UInt8.random(in: 0 ... 255)) }.joined()
        let item = ClipItem(id: id, dev: cfg.deviceName, kind: "link", mime: "text/uri-list", ts: Proto.nowMs(), data: Data(url.utf8), to: dev)
        guard let frame = ClipCodec.encode(item, secret: cfg.secret) else { return }
        remember(id)
        let links = deliver(frame, kind: "link", to: [dev])
        guard !links.isEmpty else { onEvent?("\(DeviceName.pretty(dev)) is not in reach"); return }
        receipts.track(id, targets: [dev], now: Date())
        outbox[id] = (frame, "link", Date())
        Log.write("link: sent to \(dev)")
        onRecord?(ClipRecent(clipId: id, receipt: "Sending to \(DeviceName.pretty(dev))", sent: true, dev: DeviceName.pretty(dev), kind: "link", text: url))
        onEvent?("Opening on \(DeviceName.pretty(dev))")
    }

    /// A link from another device: opened at once in the default browser (web links only).
    private func openLink(_ item: ClipItem) {
        guard let s = String(data: item.data, encoding: .utf8), let u = URL(string: s), ["http", "https"].contains(u.scheme?.lowercased() ?? "") else { return }
        NSWorkspace.shared.open(u)
        Log.write("link: opened a link from \(item.dev)")
        sendControl(kind: "ack", data: Data(item.id.utf8), to: item.dev)
        onRecord?(ClipRecent(clipId: item.id, sent: false, dev: DeviceName.pretty(item.dev), kind: "link", text: s))
    }

    /// Asks every device for its diagnostics; replies arrive through onDiagnostics.
    func requestDiagnostics() {
        sendControl(kind: "diag-req", data: Data(), to: nil)
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
            if !manual, cfg.clipSkipPasswords, PasswordLike.looks(s) {
                Log.write("clip: skipped text that looks like a password (Skip password-like text is on)")
                return
            }
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
        let targets = Set(blePeers().map(\.dev) + wifiPeers().map(\.dev)).subtracting(skip).subtracting([cfg.deviceName])
        let links = deliver(frame, kind: kind, to: nil)
        let what = kind == "text" ? "text (\(data.count) bytes)" : "image (\(data.count / 1024) KB)"
        if links.isEmpty {
            Log.write("clip: \(what) not sent, no other device \(kind == "image" ? "on Wi-Fi" : "in reach")")
            onEvent?("Not sent, no device in reach")
        } else {
            Log.write("clip: sent \(what) over \(links.joined(separator: " and "))")
            onEvent?("Sent \(kind == "text" ? "text" : "an image")")
            receipts.track(id, targets: targets, now: Date())
            outbox[id] = (frame, kind, Date())
            let names = targets.sorted().map(DeviceName.pretty).joined(separator: ", ")
            onRecord?(ClipRecent(clipId: id, receipt: targets.isEmpty ? "" : "Sending to \(names)", sent: true,
                                 dev: names.isEmpty ? "your devices" : names, kind: kind,
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
        guard let item = ClipCodec.decode(frame, secret: cfg.secret) else { return }
        // Receipts and diagnostics work whatever the clipboard settings; clips only when receiving is on.
        let content = item.kind == "text" || item.kind == "image" || item.kind == "link"
        guard !content || (cfg.clipSync && cfg.clipReceive && !paused) else { return }
        // Within 5 minutes by the sender's own clock (its offset is learned from its status), so clocks may differ.
        guard item.dev != cfg.deviceName,
              ClockGuard.shared.fresh(dev: item.dev, senderMs: item.ts, nowMs: Proto.nowMs(), maxAgeMs: ClipCodec.maxAgeMs) else { return }
        guard item.to.isEmpty || item.to == cfg.deviceName else { return }
        // Checked and recorded in one step: the copies over Bluetooth LE and Wi-Fi can arrive at the same moment.
        lock.lock()
        let dup = seen.contains(item.id)
        if !dup { seen.append(item.id); if seen.count > 100 { seen.removeFirst(seen.count - 100) } }
        lock.unlock()
        // A second copy (the other link, or a retry because the first receipt was lost) is confirmed again.
        if dup {
            if content { DispatchQueue.main.async { self.sendControl(kind: "ack", data: Data(item.id.utf8), to: item.dev) } }
            return
        }
        DispatchQueue.main.async {
            switch item.kind {
            case "ack":
                let id = String(data: item.data, encoding: .utf8) ?? ""
                if self.receipts.ack(id, from: item.dev) { self.onReceipt?(id, self.receipts.words(id, now: Date())) }
            case "diag-req":
                Log.write("diag: \(item.dev) asked for diagnostics")
                self.sendControl(kind: "diag", data: Data(self.diagnostics().utf8), to: item.dev)
            case "diag":
                self.onDiagnostics?(item.dev, String(data: item.data, encoding: .utf8) ?? "")
            case "link":
                self.openLink(item)
            case "text", "image":
                self.apply(item, via: via)
            default:
                self.onControl?(item)
            }
        }
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
        sendControl(kind: "ack", data: Data(item.id.utf8), to: item.dev)
        onRecord?(ClipRecent(sent: false, dev: DeviceName.pretty(item.dev), kind: item.kind,
                             text: item.kind == "text" ? String(data: item.data, encoding: .utf8) : nil, image: item.kind == "image" ? item.data : nil))
        onEvent?("Received \(item.kind == "text" ? "text" : "an image") from \(item.dev)")
        if cfg.clipNotify {
            let preview = item.kind == "text" ? String((String(data: item.data, encoding: .utf8) ?? "").prefix(80)) : "An image"
            Notifier.post("Clipboard from \(DeviceName.pretty(item.dev))", preview)
        }
    }
}
