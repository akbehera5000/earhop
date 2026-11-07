import Foundation
import CryptoKit

enum Ear: String {
    case both, one, none, unknown
}

struct Status {
    var dev: String
    var ts: Int64
    var seq: Int
    var playing: Bool
    var ear: Ear
    var owner: Bool
    var call: Bool = false
    /// Which earbuds this device uses: "airpods", "buds", or "" when unknown (v1, v2 and older Bluetooth LE packets).
    var prof: String = ""
    /// v4: the user was active on this device (unlocked it or typed) within the last 30 s.
    var att: Bool = false
    /// Protocol version it arrived in. v4 carries the sender's own clock in `ts` (checked by ClockGuard); older versions
    /// were checked against this device's clock when decoded.
    var v: Int = 4
}

/// Device names are free: 1 to 20 characters, lower case letters, digits and "-", starting with a letter or digit.
/// Bluetooth LE carries a 16 bit id instead of the name: the first two bytes of HMAC(secret, "earhop dev v4|" + name),
/// so only devices with the secret can tell which name an id belongs to.
enum DeviceNames {
    static func valid(_ n: String) -> Bool {
        guard (1 ... 20).contains(n.count), let f = n.first, f.isASCII, f.isLetter || f.isNumber else { return false }
        return n.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == "-") }
    }

    static func id(_ name: String, secret: String) -> Int {
        let m = Proto.signRaw(secret, Data("earhop dev v4|\(name)".utf8))
        return Int(m[0]) << 8 | Int(m[1])
    }
}

/// Every device name this device knows, and the Bluetooth LE id of each. Starts with mac, phone and tab, learns names
/// from Wi-Fi packets and from the name a device reports over its Bluetooth clipboard service, and is saved in the
/// settings so a device seen once is known after a restart.
final class NameBook {
    static let shared = NameBook()
    private let lock = NSLock()
    private var names: [String] = ["mac", "phone", "tab"]
    private var ids: [Int: String] = [:]
    private var secret = ""
    /// Called (on any thread) when a new name was learned.
    var onChange: (([String]) -> Void)?

    func setup(secret: String, known: [String]) {
        lock.lock()
        self.secret = secret
        for n in known where DeviceNames.valid(n) && !names.contains(n) { names.append(n) }
        ids = Dictionary(names.map { (DeviceNames.id($0, secret: secret), $0) }, uniquingKeysWith: { a, _ in a })
        lock.unlock()
    }

    /// Adds a name; returns true when it is new.
    @discardableResult
    func add(_ n: String) -> Bool {
        guard DeviceNames.valid(n) else { return false }
        lock.lock()
        if names.contains(n) { lock.unlock(); return false }
        names.append(n)
        ids[DeviceNames.id(n, secret: secret)] = n
        let all = names
        lock.unlock()
        onChange?(all)
        return true
    }

    func name(for id: Int) -> String? { lock.lock(); defer { lock.unlock() }; return ids[id] }
    var all: [String] { lock.lock(); defer { lock.unlock() }; return names }
}

/// Learns how far each device's clock is from this one and refuses status copies and clips that are far off that
/// offset, so devices with clocks minutes apart still work while an old recording replayed later is refused.
/// The first copy from a device sets its offset. A copy within `tolerance` of it is accepted (and nudges the offset
/// towards the freshest copy seen, as Bluetooth LE advertisements can be up to a minute old). A copy outside it is
/// refused, unless three copies in a row with rising sequence numbers agree on a new offset (the device's clock was
/// changed): then that becomes the offset. A replayed recording cannot produce new sequence numbers.
final class ClockGuard {
    static let shared = ClockGuard()
    private struct Entry { var offset: Int64; var cand: Int64?; var candCount = 0; var candSeq = 0 }
    private var map: [String: Entry] = [:]
    private let lock = NSLock()

    /// Wi-Fi status is sent every second; Bluetooth LE advertisements are refreshed at least once a minute.
    static let wifiTolerance: Int64 = 15_000
    static let bleTolerance: Int64 = 90_000

    func check(dev: String, senderMs: Int64, nowMs: Int64, toleranceMs: Int64, seq: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let seen = senderMs - nowMs
        guard var e = map[dev] else { map[dev] = Entry(offset: seen); return true }
        if abs(seen - e.offset) <= toleranceMs {
            // Copies are never early, only late: the largest offset seen is the closest to the truth.
            if seen > e.offset { e.offset = seen }
            e.cand = nil; e.candCount = 0
            map[dev] = e
            return true
        }
        if let c = e.cand, abs(seen - c) <= toleranceMs, BlePayload.isNewer(seq & 0xFFFF, than: e.candSeq & 0xFFFF) {
            e.candCount += 1; e.candSeq = seq
            if e.candCount >= 3 { e.offset = max(c, seen); e.cand = nil; e.candCount = 0; map[dev] = e; return true }
        } else if e.cand.map({ abs(seen - $0) > toleranceMs }) ?? true {
            e.cand = seen; e.candCount = 1; e.candSeq = seq
        }
        map[dev] = e
        return false
    }

    /// The learned offset (their clock minus this one) in milliseconds, nil before the first copy.
    func offset(_ dev: String) -> Int64? { lock.lock(); defer { lock.unlock() }; return map[dev]?.offset }

    /// For clips: whether a time stamp from `dev` is within `maxAgeMs` of now, by that device's clock. Before its first
    /// status copy the clip is accepted (the clip id still stops a second copy).
    func fresh(dev: String, senderMs: Int64, nowMs: Int64, maxAgeMs: Int64) -> Bool {
        guard let o = offset(dev) else { return true }
        return abs(senderMs - nowMs - o) <= maxAgeMs
    }

    func reset() { lock.lock(); map = [:]; lock.unlock() }
}

enum Proto {
    /// v1 has no call field, v2 appends it, v3 also appends the earbud profile.
    static func canonical(_ s: Status, v: Int = 2) -> String {
        let base = "\(v)|\(s.dev)|\(s.ts)|\(s.seq)|\(s.playing ? 1 : 0)|\(s.ear.rawValue)|\(s.owner ? 1 : 0)"
        switch v {
        case 1: return base
        case 2: return base + "|\(s.call ? 1 : 0)"
        case 3: return base + "|\(s.call ? 1 : 0)|\(s.prof)"
        default: return base + "|\(s.call ? 1 : 0)|\(s.prof)|\(s.att ? 1 : 0)"
        }
    }

    static func signRaw(_ secret: String, _ data: Data) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: Data(secret.utf8))))
    }

    static func sign(_ secret: String, _ text: String) -> String {
        signRaw(secret, Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func encode(_ s: Status, secret: String) -> Data? {
        let obj: [String: Any] = ["v": 4, "dev": s.dev, "ts": s.ts, "seq": s.seq,
                                  "playing": s.playing ? 1 : 0, "ear": s.ear.rawValue,
                                  "owner": s.owner ? 1 : 0, "call": s.call ? 1 : 0, "prof": s.prof, "att": s.att ? 1 : 0,
                                  "sig": sign(secret, canonical(s, v: 4))]
        return try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    }

    /// For a packet that decode() rejected: if it is a well formed EarHop packet, which device sent it and why it was
    /// refused ("secret" or "clock"). Only used to tell the user; the packet itself is never accepted.
    static func rejection(_ data: Data, secret: String, nowMs: Int64) -> (dev: String, reason: String)? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dev = o["dev"] as? String, o["sig"] is String,
              let ts = (o["ts"] as? NSNumber)?.int64Value else { return nil }
        // v1 to v3 only: same secret but outside the 30 s window means the clocks differ. Otherwise the secret differs.
        if (o["v"] as? Int ?? 4) < 4, abs(nowMs - ts) > 30000, let ok = decodeIgnoringTime(o, secret: secret), ok { return (dev, "clock") }
        return (dev, "secret")
    }

    private static func decodeIgnoringTime(_ o: [String: Any], secret: String) -> Bool? {
        guard let v = o["v"] as? Int, let dev = o["dev"] as? String, let ts = (o["ts"] as? NSNumber)?.int64Value,
              let seq = o["seq"] as? Int, let playing = o["playing"] as? Int, let earRaw = o["ear"] as? String,
              let ear = Ear(rawValue: earRaw), let owner = o["owner"] as? Int, let sig = o["sig"] as? String else { return nil }
        let call = v >= 2 && (o["call"] as? Int) == 1
        let prof = v >= 3 ? (o["prof"] as? String ?? "") : ""
        let s = Status(dev: dev, ts: ts, seq: seq, playing: playing == 1, ear: ear, owner: owner == 1, call: call, prof: prof,
                       att: v >= 4 && (o["att"] as? Int) == 1, v: v)
        return sign(secret, canonical(s, v: v)) == sig
    }

    static func decode(_ data: Data, secret: String, nowMs: Int64) -> Status? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let v = o["v"] as? Int, (1 ... 4).contains(v),
              let dev = o["dev"] as? String, !dev.contains("|"),
              let ts = (o["ts"] as? NSNumber)?.int64Value,
              let seq = o["seq"] as? Int,
              let playing = o["playing"] as? Int,
              let earRaw = o["ear"] as? String, let ear = Ear(rawValue: earRaw),
              let owner = o["owner"] as? Int,
              let sig = o["sig"] as? String else { return nil }
        var call = false
        if v >= 2 {
            guard let c = o["call"] as? Int else { return nil }
            call = c == 1
        }
        var prof = ""
        if v >= 3 {
            guard let p = o["prof"] as? String, ["", "airpods", "buds"].contains(p) else { return nil }
            prof = p
        }
        var att = false
        if v >= 4 {
            guard let a = o["att"] as? Int, DeviceNames.valid(dev) else { return nil }
            att = a == 1
        }
        let s = Status(dev: dev, ts: ts, seq: seq, playing: playing == 1, ear: ear, owner: owner == 1, call: call, prof: prof, att: att, v: v)
        let expected = Array(sign(secret, canonical(s, v: v)).utf8)
        let got = Array(sig.utf8)
        guard expected.count == got.count else { return nil }
        var diff: UInt8 = 0
        for i in expected.indices { diff |= expected[i] ^ got[i] }
        // v4 carries the sender's clock: ClockGuard checks it against the offset learned for that device. Older
        // versions still need the clocks within 30 s.
        guard diff == 0, v >= 4 || abs(nowMs - ts) <= 30000 else { return nil }
        return s
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Bluetooth LE fallback payload, 16 bytes carried as one 128-bit service UUID.
/// [0]=0xEA [1]=dev code [2..3]=seq [4]=flags [5..12]=truncated HMAC of bytes 0..4 and 13..15
/// [13..15]=minutes since 1970 (low 24 bits). The signed minute stamp refuses recordings older than 2 minutes.
/// Flags: bit0 playing, bits1-2 ear (0 unknown 1 none 2 one 3 both), bit3 owner, bit4 call, bit5 earbud profile known,
/// bit6 profile is Galaxy Buds (else AirPods).
enum BlePayload {
    static let devCodes: [String: UInt8] = ["mac": 0, "phone": 1, "tab": 2]
    static func earCode(_ e: Ear) -> UInt8 { switch e { case .unknown: return 0; case .none: return 1; case .one: return 2; case .both: return 3 } }
    static func ear(_ c: UInt8) -> Ear { [Ear.unknown, .none, .one, .both][Int(c & 3)] }

    static func minute(_ ms: Int64) -> Int { Int((ms / 60000) & 0xFFFFFF) }

    static func encode(_ s: Status, secret: String, minute m: Int = minute(Proto.nowMs())) -> [UInt8]? {
        guard let dev = devCodes[s.dev] else { return nil }
        let profBits: UInt8 = s.prof == "buds" ? 96 : s.prof == "airpods" ? 32 : 0
        let flags = (s.playing ? 1 : 0) | (earCode(s.ear) << 1) | (s.owner ? 8 : 0) | (s.call ? 16 : 0) | profBits
        let head: [UInt8] = [0xEA, dev, UInt8((s.seq >> 8) & 0xFF), UInt8(s.seq & 0xFF), flags]
        let stamp: [UInt8] = [UInt8((m >> 16) & 0xFF), UInt8((m >> 8) & 0xFF), UInt8(m & 0xFF)]
        return head + Array(Proto.signRaw(secret, Data(head + stamp))[0 ..< 8]) + stamp
    }

    static func decode(_ b: [UInt8], secret: String, nowMinute: Int = minute(Proto.nowMs())) -> (dev: String, seq: Int, playing: Bool, ear: Ear, owner: Bool, call: Bool, prof: String)? {
        guard b.count == 16, b[0] == 0xEA else { return nil }
        let mac = Array(Proto.signRaw(secret, Data(Array(b[0 ..< 5]) + Array(b[13 ..< 16])))[0 ..< 8])
        var diff: UInt8 = 0
        for i in 0 ..< 8 { diff |= mac[i] ^ b[5 + i] }
        guard diff == 0, let name = devCodes.first(where: { $0.value == b[1] })?.key else { return nil }
        let m = Int(b[13]) << 16 | Int(b[14]) << 8 | Int(b[15])
        let d = (nowMinute - m) & 0xFFFFFF
        guard min(d, 0x1000000 - d) <= 2 else { return nil }
        let f = b[4]
        let prof = f & 32 == 0 ? "" : (f & 64 != 0 ? "buds" : "airpods")
        return (name, Int(b[2]) << 8 | Int(b[3]), f & 1 != 0, ear(f >> 1), f & 8 != 0, f & 16 != 0, prof)
    }

    /// v4, 16 bytes: [0]=0xEB [1..2]=device id (DeviceNames.id) [3..4]=seq (low 16 bits) [5]=flags
    /// [6..11]=first 6 bytes of HMAC(secret, bytes 0..5 + 12..15) [12..15]=sender's clock, seconds since 1970.
    /// Flags as before, plus bit7: the user was active on this device in the last 30 s. The receiver checks the time
    /// against the sender's learned clock offset (ClockGuard) and refuses copies that are not newer (PeerOrder).
    static func encode4(_ s: Status, secret: String, sec: UInt32) -> [UInt8] {
        let id = DeviceNames.id(s.dev, secret: secret)
        let profBits: UInt8 = s.prof == "buds" ? 96 : s.prof == "airpods" ? 32 : 0
        let flags = (s.playing ? 1 : 0) | (earCode(s.ear) << 1) | (s.owner ? 8 : 0) | (s.call ? 16 : 0) | profBits | (s.att ? 128 : 0)
        let head: [UInt8] = [0xEB, UInt8(id >> 8), UInt8(id & 0xFF), UInt8((s.seq >> 8) & 0xFF), UInt8(s.seq & 0xFF), flags]
        let stamp: [UInt8] = [UInt8(sec >> 24), UInt8((sec >> 16) & 0xFF), UInt8((sec >> 8) & 0xFF), UInt8(sec & 0xFF)]
        return head + Array(Proto.signRaw(secret, Data(head + stamp))[0 ..< 6]) + stamp
    }

    struct V4 { let id: Int; let seq: Int; let flags: UInt8; let sec: UInt32 }

    static func decode4(_ b: [UInt8], secret: String) -> V4? {
        guard b.count == 16, b[0] == 0xEB else { return nil }
        let mac = Array(Proto.signRaw(secret, Data(Array(b[0 ..< 6]) + Array(b[12 ..< 16])))[0 ..< 6])
        var diff: UInt8 = 0
        for i in 0 ..< 6 { diff |= mac[i] ^ b[6 + i] }
        guard diff == 0 else { return nil }
        let sec = UInt32(b[12]) << 24 | UInt32(b[13]) << 16 | UInt32(b[14]) << 8 | UInt32(b[15])
        return V4(id: Int(b[1]) << 8 | Int(b[2]), seq: Int(b[3]) << 8 | Int(b[4]), flags: b[5], sec: sec)
    }

    /// A v4 payload as a status, once the id's name is known.
    static func status(_ m: V4, name: String) -> Status {
        let f = m.flags
        let prof = f & 32 == 0 ? "" : (f & 64 != 0 ? "buds" : "airpods")
        return Status(dev: name, ts: Int64(m.sec) * 1000, seq: m.seq, playing: f & 1 != 0, ear: ear(f >> 1), owner: f & 8 != 0,
                      call: f & 16 != 0, prof: prof, att: f & 128 != 0, v: 4)
    }

    /// True when `new` is ahead of `old` in 16 bit wrapping order.
    static func isNewer(_ new: Int, than old: Int) -> Bool {
        let d = (new - old) & 0xFFFF
        return d != 0 && d < 0x8000
    }
}

/// Merges the copies of one device's status that arrive over Wi-Fi and Bluetooth LE. Both links carry the same sequence
/// number (Wi-Fi in full, Bluetooth LE only its low 16 bits), so the newest copy wins whichever link brought it.
enum PeerOrder {
    /// A number this far below the last one means the device restarted and counts from 1 again.
    static let restartGap = 300

    /// The full sequence number closest to `last` whose low 16 bits are `low16`.
    static func widen(_ low16: Int, near last: Int) -> Int {
        let base = (last & ~0xFFFF) | (low16 & 0xFFFF)
        return [base - 0x10000, base, base + 0x10000].min { abs($0 - last) < abs($1 - last) }!
    }

    /// Whether to take this copy, and its full sequence number. A copy that is not newer only shows the device is alive.
    /// `ts` and `lastTs` are the sender's clock (v4): a number far below the last one counts as a restart only when the
    /// copy is also newer by the sender's clock, so an old recording replayed later is never taken for a restart.
    static func accept(lastSeq: Int?, seq: Int, ble: Bool, ts: Int64 = .max, lastTs: Int64 = .min) -> (take: Bool, seq: Int) {
        guard let last = lastSeq else { return (true, seq) }
        let full = ble ? widen(seq, near: last) : seq
        if full > last { return (true, full) }
        if last - full > restartGap && ts > lastTs { return (true, seq) }
        return (false, full)
    }
}
