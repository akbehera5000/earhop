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
}

enum Proto {
    /// v1 has no call field, v2 appends it.
    static func canonical(_ s: Status, v: Int = 2) -> String {
        let base = "\(v)|\(s.dev)|\(s.ts)|\(s.seq)|\(s.playing ? 1 : 0)|\(s.ear.rawValue)|\(s.owner ? 1 : 0)"
        return v == 1 ? base : base + "|\(s.call ? 1 : 0)"
    }

    static func signRaw(_ secret: String, _ data: Data) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: Data(secret.utf8))))
    }

    static func sign(_ secret: String, _ text: String) -> String {
        signRaw(secret, Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func encode(_ s: Status, secret: String) -> Data? {
        let obj: [String: Any] = ["v": 2, "dev": s.dev, "ts": s.ts, "seq": s.seq,
                                  "playing": s.playing ? 1 : 0, "ear": s.ear.rawValue,
                                  "owner": s.owner ? 1 : 0, "call": s.call ? 1 : 0,
                                  "sig": sign(secret, canonical(s))]
        return try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    }

    /// For a packet that decode() rejected: if it is a well formed EarHop packet, which device sent it and why it was
    /// refused ("secret" or "clock"). Only used to tell the user; the packet itself is never accepted.
    static func rejection(_ data: Data, secret: String, nowMs: Int64) -> (dev: String, reason: String)? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dev = o["dev"] as? String, o["sig"] is String,
              let ts = (o["ts"] as? NSNumber)?.int64Value else { return nil }
        // Same secret but outside the 30 s window: the clocks differ. Otherwise the secret differs.
        if abs(nowMs - ts) > 30000, let ok = decodeIgnoringTime(o, secret: secret), ok { return (dev, "clock") }
        return (dev, "secret")
    }

    private static func decodeIgnoringTime(_ o: [String: Any], secret: String) -> Bool? {
        guard let v = o["v"] as? Int, let dev = o["dev"] as? String, let ts = (o["ts"] as? NSNumber)?.int64Value,
              let seq = o["seq"] as? Int, let playing = o["playing"] as? Int, let earRaw = o["ear"] as? String,
              let ear = Ear(rawValue: earRaw), let owner = o["owner"] as? Int, let sig = o["sig"] as? String else { return nil }
        let call = v == 2 && (o["call"] as? Int) == 1
        let s = Status(dev: dev, ts: ts, seq: seq, playing: playing == 1, ear: ear, owner: owner == 1, call: call)
        return sign(secret, canonical(s, v: v)) == sig
    }

    static func decode(_ data: Data, secret: String, nowMs: Int64) -> Status? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let v = o["v"] as? Int, v == 1 || v == 2,
              let dev = o["dev"] as? String, !dev.contains("|"),
              let ts = (o["ts"] as? NSNumber)?.int64Value,
              let seq = o["seq"] as? Int,
              let playing = o["playing"] as? Int,
              let earRaw = o["ear"] as? String, let ear = Ear(rawValue: earRaw),
              let owner = o["owner"] as? Int,
              let sig = o["sig"] as? String else { return nil }
        var call = false
        if v == 2 {
            guard let c = o["call"] as? Int else { return nil }
            call = c == 1
        }
        let s = Status(dev: dev, ts: ts, seq: seq, playing: playing == 1, ear: ear, owner: owner == 1, call: call)
        let expected = Array(sign(secret, canonical(s, v: v)).utf8)
        let got = Array(sig.utf8)
        guard expected.count == got.count else { return nil }
        var diff: UInt8 = 0
        for i in expected.indices { diff |= expected[i] ^ got[i] }
        guard diff == 0, abs(nowMs - ts) <= 30000 else { return nil }
        return s
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Bluetooth LE fallback payload, 16 bytes carried as one 128-bit service UUID.
/// [0]=0xEA [1]=dev code [2..3]=seq [4]=flags [5..12]=truncated HMAC of bytes 0..4 and 13..15
/// [13..15]=minutes since 1970 (low 24 bits). The signed minute stamp refuses recordings older than 2 minutes.
/// Flags: bit0 playing, bits1-2 ear (0 unknown 1 none 2 one 3 both), bit3 owner, bit4 call.
enum BlePayload {
    static let devCodes: [String: UInt8] = ["mac": 0, "phone": 1, "tab": 2]
    static func earCode(_ e: Ear) -> UInt8 { switch e { case .unknown: return 0; case .none: return 1; case .one: return 2; case .both: return 3 } }
    static func ear(_ c: UInt8) -> Ear { [Ear.unknown, .none, .one, .both][Int(c & 3)] }

    static func minute(_ ms: Int64) -> Int { Int((ms / 60000) & 0xFFFFFF) }

    static func encode(_ s: Status, secret: String, minute m: Int = minute(Proto.nowMs())) -> [UInt8]? {
        guard let dev = devCodes[s.dev] else { return nil }
        let flags = (s.playing ? 1 : 0) | (earCode(s.ear) << 1) | (s.owner ? 8 : 0) | (s.call ? 16 : 0)
        let head: [UInt8] = [0xEA, dev, UInt8((s.seq >> 8) & 0xFF), UInt8(s.seq & 0xFF), flags]
        let stamp: [UInt8] = [UInt8((m >> 16) & 0xFF), UInt8((m >> 8) & 0xFF), UInt8(m & 0xFF)]
        return head + Array(Proto.signRaw(secret, Data(head + stamp))[0 ..< 8]) + stamp
    }

    static func decode(_ b: [UInt8], secret: String, nowMinute: Int = minute(Proto.nowMs())) -> (dev: String, seq: Int, playing: Bool, ear: Ear, owner: Bool, call: Bool)? {
        guard b.count == 16, b[0] == 0xEA else { return nil }
        let mac = Array(Proto.signRaw(secret, Data(Array(b[0 ..< 5]) + Array(b[13 ..< 16])))[0 ..< 8])
        var diff: UInt8 = 0
        for i in 0 ..< 8 { diff |= mac[i] ^ b[5 + i] }
        guard diff == 0, let name = devCodes.first(where: { $0.value == b[1] })?.key else { return nil }
        let m = Int(b[13]) << 16 | Int(b[14]) << 8 | Int(b[15])
        let d = (nowMinute - m) & 0xFFFFFF
        guard min(d, 0x1000000 - d) <= 2 else { return nil }
        let f = b[4]
        return (name, Int(b[2]) << 8 | Int(b[3]), f & 1 != 0, ear(f >> 1), f & 8 != 0, f & 16 != 0)
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
    static func accept(lastSeq: Int?, seq: Int, ble: Bool) -> (take: Bool, seq: Int) {
        guard let last = lastSeq else { return (true, seq) }
        let full = ble ? widen(seq, near: last) : seq
        if full > last { return (true, full) }
        if last - full > restartGap { return (true, seq) }
        return (false, full)
    }
}
