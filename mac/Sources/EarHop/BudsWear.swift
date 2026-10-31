import Foundation
import IOBluetooth

/// Galaxy Buds serial protocol: framing, wear status and the controls EarHop uses. Byte positions follow the open
/// source omarchy-galaxy-buds-pro project (MIT licensed), which documents them per model.
/// Frame: 0xFD, 2 length bytes (low 10 bits = id + payload + crc), id, payload, 2 crc bytes (CRC16 XModem, little endian), 0xDD.
enum BudsProtocol {
    static let idStatus = 0x60, idExtended = 0x61, idAck = 66, idNoiseUpdate = 119, idNoise = 120, idSpatial = 124
    static let idEqualizer = 134, idLockTouchpad = 144, idFindStart = 160, idFindStop = 161, idMute = 162, idMuteUpdated = 163, idSeamless = 175
    static let noiseModes = ["off", "anc", "ambient"]
    static let eqPresets = ["off", "bass", "soft", "dynamic", "clear", "treble"]

    struct Msg { let id: Int; let payload: [UInt8] }

    struct State: Equatable {
        var revision = -1
        var battL = -1, battR = -1, battCase = -1
        var placeL = "unknown", placeR = "unknown"
        var chargeL = false, chargeR = false, chargeCase = false
        var noise = "unknown", eq = "unknown"
        var spatial: Bool?, touchOn: Bool?, seamless: Bool?
        var tap = false, double = false, triple = false, hold = false, doubleCall = false, holdCall = false
        var muteL = false, muteR = false
        var findEnded = false
        var wearing: Int { (placeL == "wearing" ? 1 : 0) + (placeR == "wearing" ? 1 : 0) }
        var ear: Ear { wearing == 2 ? .both : (wearing == 1 ? .one : Ear.none) }
    }

    static let crcTable: [UInt16] = (0 ..< 256).map { i in
        var c = UInt16(i) << 8
        for _ in 0 ..< 8 { c = (c & 0x8000) != 0 ? (c << 1) ^ 0x1021 : c << 1 }
        return c
    }

    static func crc16(_ data: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0
        for b in data { crc = (crc << 8) ^ crcTable[Int(((crc >> 8) ^ UInt16(b)) & 0xFF)] }
        return crc
    }

    static func encode(_ id: Int, _ payload: [UInt8] = []) -> [UInt8] {
        let size = 1 + payload.count + 2
        let body = [UInt8(id)] + payload
        let crc = crc16(body)
        return [0xFD, UInt8(size & 0xFF), UInt8(size >> 8)] + body + [UInt8(crc & 0xFF), UInt8(crc >> 8), 0xDD]
    }

    /// Largest frame EarHop accepts. Real status frames are well under 100 bytes; a bigger length is junk.
    static let maxSize = 600

    /// Checksum bookkeeping for one connection. Every frame is checked, but bad checksums are only dropped once at least
    /// one frame on this connection has passed, so a wrong assumption about the checksum can never silence the Buds.
    final class CrcStats {
        var good = 0, bad = 0
        var enforcing: Bool { good > 0 }
    }

    /// True when the two checksum bytes match id + payload, in either byte order.
    static func crcOk(_ id: Int, _ payload: [UInt8], _ lo: UInt8, _ hi: UInt8) -> Bool {
        let c = crc16([UInt8(id)] + payload)
        return c == UInt16(lo) | (UInt16(hi) << 8) || c == UInt16(hi) | (UInt16(lo) << 8)
    }

    /// Takes complete frames off the front of buf. With crc set, frames are checked as described on CrcStats.
    static func extract(_ buf: inout [UInt8], crc: CrcStats? = nil) -> [Msg] {
        var out: [Msg] = []
        while true {
            guard let start = buf.firstIndex(of: 0xFD) else { buf.removeAll(); break }
            if start > 0 { buf.removeFirst(start) }
            if buf.count < 4 { break }
            let size = (Int(buf[1]) | (Int(buf[2]) << 8)) & 0x3FF
            if size < 3 || size > maxSize { buf.removeFirst(); continue }
            let total = 4 + size
            if buf.count < total { break }
            if buf[total - 1] != 0xDD { buf.removeFirst(); continue }
            let id = Int(buf[3])
            let payload = Array(buf[4 ..< (4 + size - 3)])
            if let crc {
                if crcOk(id, payload, buf[total - 3], buf[total - 2]) { crc.good += 1 }
                else {
                    crc.bad += 1
                    if crc.enforcing { buf.removeFirst(); continue }
                }
            }
            out.append(Msg(id: id, payload: payload))
            buf.removeFirst(total)
        }
        return out
    }

    static func noiseCommand(_ mode: String) -> [UInt8] { encode(idNoise, [UInt8(max(0, noiseModes.firstIndex(of: mode) ?? 0))]) }
    static func eqCommand(_ preset: String) -> [UInt8] { encode(idEqualizer, [UInt8(max(0, eqPresets.firstIndex(of: preset) ?? 0))]) }
    static func spatialCommand(_ on: Bool) -> [UInt8] { encode(idSpatial, [on ? 1 : 0]) }
    /// Quick connect is inverted on the wire: 0 means on.
    static func seamlessCommand(_ on: Bool) -> [UInt8] { encode(idSeamless, [on ? 0 : 1]) }
    static func muteCommand(_ l: Bool, _ r: Bool) -> [UInt8] { encode(idMute, [l ? 1 : 0, r ? 1 : 0]) }
    static func touchCommand(_ on: Bool, _ s: State) -> [UInt8] {
        var g = [s.tap, s.double, s.triple, s.hold]
        if on, !g.contains(true) { g = [true, true, true, true] }
        return encode(idLockTouchpad, [on ? 1 : 0] + g.map { $0 ? 1 : 0 } + [s.doubleCall ? 1 : 0, s.holdCall ? 1 : 0])
    }

    private static func place(_ n: Int) -> String {
        switch n { case 0: return "disconnected"; case 1: return "wearing"; case 2: return "idle"; case 3, 4: return "case"; default: return "unknown" }
    }
    private static func battery(_ v: Int) -> Int { (1 ... 100).contains(v) ? v : -1 }

    /// Applies one message to the state. Returns true when something changed.
    @discardableResult
    static func apply(_ m: Msg, _ s: inout State) -> Bool {
        let before = s
        let p = m.payload.map { Int($0) }
        switch m.id {
        case idExtended where p.count > 12:
            s.revision = p[0]
            s.battL = battery(p[2]); s.battR = battery(p[3])
            s.placeL = place(p[6] >> 4); s.placeR = place(p[6] & 15)
            s.battCase = battery(p[7])
            s.eq = p[9] < eqPresets.count ? eqPresets[p[9]] : (p[9] == 6 ? "custom" : "unknown")
            let t = p[10]
            s.touchOn = t & 0x80 != 0
            s.tap = t & 0x08 != 0; s.double = t & 0x04 != 0; s.triple = t & 0x02 != 0; s.hold = t & 0x01 != 0
            s.doubleCall = t & 0x10 != 0; s.holdCall = t & 0x20 != 0
            s.noise = p[12] < noiseModes.count ? noiseModes[p[12]] : "unknown"
            if p.count > 19 { s.seamless = p[19] == 0 }
            if p.count > 35, s.revision >= 1 { s.spatial = p[35] == 1 }
        case idStatus where p.count > 6:
            s.battL = battery(p[1]); s.battR = battery(p[2])
            s.placeL = place(p[5] >> 4); s.placeR = place(p[5] & 15)
            s.battCase = battery(p[6])
            if p.count > 7 {
                let c = p[7]
                s.chargeL = c & 0x10 != 0; s.chargeR = c & 0x04 != 0; s.chargeCase = c & 0x01 != 0
                if s.chargeL { s.placeL = "case" }
                if s.chargeR { s.placeR = "case" }
            }
        case idNoiseUpdate where !p.isEmpty:
            s.noise = p[0] < noiseModes.count ? noiseModes[p[0]] : "unknown"
        case idMuteUpdated where p.count >= 2:
            s.muteL = p[0] != 0; s.muteR = p[1] != 0
        case idFindStop:
            s.findEnded = true
        case idAck where p.count >= 2:
            let v = p[1]
            switch p[0] {
            case idNoise: if v < noiseModes.count { s.noise = noiseModes[v] }
            case idSpatial: s.spatial = v == 1
            case idSeamless: s.seamless = v == 0
            case idLockTouchpad: s.touchOn = v == 1
            case idEqualizer: s.eq = v < eqPresets.count ? eqPresets[v] : (v == 6 ? "custom" : "unknown")
            case idMute: if p.count >= 3 { s.muteL = v != 0; s.muteR = p[2] != 0 }
            default: break
            }
        default: break
        }
        return s != before
    }

    // Find my earbuds safety, same rules as the project: never ring a bud that may be in an ear.
    private static let outOfEar: Set<String> = ["idle", "case", "disconnected"]
    static func mustMute(_ s: State) -> (l: Bool, r: Bool) { (!outOfEar.contains(s.placeL), !outOfEar.contains(s.placeR)) }
    static func findRefusal(_ s: State) -> String? {
        let m = mustMute(s)
        if m.l && m.r { return s.placeL == "wearing" && s.placeR == "wearing" ? "Both Buds are in your ears" : "Buds position unknown, take them out first" }
        let canL = !m.l && s.placeL != "disconnected", canR = !m.r && s.placeR != "disconnected"
        if !canL && !canR { return "Neither bud can ring" }
        return nil
    }
}

/// EXPERIMENTAL wear status for Galaxy Buds on the Mac. While the Buds are connected here, open Samsung's serial channel
/// and read the status messages they push. May be refused by the Buds or the system; every outcome is logged.
final class BudsWear: NSObject, IOBluetoothRFCOMMChannelDelegate {
    private(set) var ear: Ear?
    private(set) var status = "off"
    private(set) var state = BudsProtocol.State()
    /// Called on the main thread when the earbuds report something new.
    var onState: ((BudsProtocol.State) -> Void)?
    private(set) var findActive = false
    private(set) var findMessage = ""
    private var findDeadline = Date.distantPast
    private var findConfirmBy: Date?
    private var findWant = (l: false, r: false)
    private var findTimer: Timer?
    private var seenAt = Date.distantPast
    private var channel: IOBluetoothRFCOMMChannel?
    private var buffer: [UInt8] = []
    private var crc = BudsProtocol.CrcStats()
    private var address = ""
    private var connecting = false
    private var failures = 0
    private var nextTryAt = Date.distantPast
    private var holdUntil = Date.distantPast
    private let uuidBytes: [UInt8] = [0x2E, 0x73, 0xA4, 0xAD, 0x33, 0x2D, 0x41, 0xFC, 0x90, 0xE2, 0x16, 0xBE, 0xF0, 0x65, 0x23, 0xF2]


    /// The wear state with its source: live from the channel (high), the last report for 20 s after the channel
    /// closes (low, never pauses or resumes), or assumed in the ears when nothing is known (low).
    var reading: EarReading {
        if channel != nil, let e = ear, Date().timeIntervalSince(seenAt) < 120 { return EarReading(ear: e, source: "buds-rfcomm", confidence: .high) }
        if let e = ear, Date().timeIntervalSince(seenAt) < 20 { return EarReading(ear: e, source: "last-known", confidence: .low) }
        return EarReading(ear: .both, source: "assumed", confidence: .low)
    }

    /// Call about once a second on the main thread.
    func update(address: String, owner: Bool, wanted: Bool) {
        self.address = address
        guard wanted, owner else { if channel != nil || status != "off" { close(); status = "off" }; return }
        guard channel == nil, !connecting, Date() >= nextTryAt, Date() >= holdUntil, !address.isEmpty else { return }
        connect()
    }

    private func fail(_ why: String) {
        connecting = false
        failures += 1
        status = "refused (\(why))"
        if failures == 1 || failures % 5 == 0 { Log.write("buds: wear channel failed: \(why)") }
        // Back off 2, 4, 8, 15 s so a refused channel never becomes a busy loop but comes back soon.
        nextTryAt = Date().addingTimeInterval(min(15, 2 * pow(2, Double(min(failures - 1, 3)))))
    }

    private func connect() {
        // IOBluetooth's own isConnected() can miss a connected pair, so ask the same check the claims use.
        guard let dev = IOBluetoothDevice(addressString: address), BlueUtil(path: "", address: address).fastConnected() == true else { return }
        connecting = true
        status = "connecting"
        let uuid = IOBluetoothSDPUUID(bytes: uuidBytes, length: 16)
        guard let record = dev.getServiceRecord(for: uuid) else {
            dev.performSDPQuery(nil)
            fail("service not found yet")
            nextTryAt = Date().addingTimeInterval(3)
            return
        }
        var id: BluetoothRFCOMMChannelID = 0
        guard record.getRFCOMMChannelID(&id) == kIOReturnSuccess else { fail("no serial channel"); return }
        var ch: IOBluetoothRFCOMMChannel?
        let r = dev.openRFCOMMChannelAsync(&ch, withChannelID: id, delegate: self)
        if r != kIOReturnSuccess { fail("open error \(r)"); return }
        channel = ch
    }

    // MARK: commands

    @discardableResult
    func send(_ bytes: [UInt8]) -> Bool {
        guard let ch = channel, ch.isOpen() else { return false }
        var b = bytes
        return ch.writeSync(&b, length: UInt16(b.count)) == kIOReturnSuccess
    }
    func setNoise(_ m: String) { send(BudsProtocol.noiseCommand(m)) }
    func setEq(_ p: String) { send(BudsProtocol.eqCommand(p)) }
    func setSpatial(_ on: Bool) { send(BudsProtocol.spatialCommand(on)) }
    func setSeamless(_ on: Bool) { send(BudsProtocol.seamlessCommand(on)) }
    func setTouch(_ on: Bool) { send(BudsProtocol.touchCommand(on, state)) }

    /// Rings the Buds. Refuses unless it is safe, and stops itself when the safety mute is not confirmed.
    func findStart() {
        guard !findActive else { return }
        guard channel != nil else { findMessage = "Buds are not connected to this Mac"; onState?(state); return }
        if let why = BudsProtocol.findRefusal(state) { findMessage = why; onState?(state); return }
        let m = BudsProtocol.mustMute(state)
        findWant = m
        findActive = true
        findMessage = "Ringing"
        findDeadline = Date().addingTimeInterval(60)
        send(BudsProtocol.encode(BudsProtocol.idFindStart))
        if m.l || m.r { send(BudsProtocol.muteCommand(m.l, m.r)); findConfirmBy = Date().addingTimeInterval(2) } else { findConfirmBy = nil }
        findTimer?.invalidate()
        findTimer = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: true) { [weak self] _ in self?.findTick() }
        onState?(state)
    }

    func findStop(_ why: String = "Search stopped") {
        guard findActive else { return }
        findActive = false
        findMessage = why
        findTimer?.invalidate(); findTimer = nil
        send(BudsProtocol.encode(BudsProtocol.idFindStop))
        onState?(state)
    }

    private func findTick() {
        guard findActive else { findTimer?.invalidate(); return }
        if channel == nil { findActive = false; findMessage = "Search ended"; findTimer?.invalidate(); onState?(state); return }
        if Date() > findDeadline { findStop("Search timed out"); return }
        if BudsProtocol.findRefusal(state) != nil { findStop("A bud went into an ear, search stopped"); return }
        if let by = findConfirmBy {
            let ok = (!findWant.l || state.muteL) && (!findWant.r || state.muteR)
            if ok { findConfirmBy = nil } else if Date() > by { findStop("The Buds did not confirm the safety mute, search stopped"); return }
        }
        let m = BudsProtocol.mustMute(state)
        if (m.l && !findWant.l) || (m.r && !findWant.r) {
            findWant = (findWant.l || m.l, findWant.r || m.r)
            send(BudsProtocol.muteCommand(findWant.l, findWant.r))
            findConfirmBy = Date().addingTimeInterval(1)
        }
    }

    /// Closes the status channel before EarHop disconnects the Buds, and keeps it closed for a few seconds.
    /// An open channel kept the Bluetooth link alive and made the disconnect take about 10 s.
    func suspend(_ seconds: TimeInterval) {
        holdUntil = Date().addingTimeInterval(seconds)
        if channel != nil { Log.write("buds: closing wear channel before disconnect"); close(); status = "off" }
    }

    private func close() {
        if findActive { findActive = false; findMessage = "Search ended"; findTimer?.invalidate() }
        channel?.close()
        channel = nil
        buffer.removeAll()
        connecting = false
    }

    func rfcommChannelOpenComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel!, status error: IOReturn) {
        connecting = false
        if error == kIOReturnSuccess {
            failures = 0
            crc = BudsProtocol.CrcStats()
            status = "connected"
            Log.write("buds: wear channel open")
        } else {
            channel = nil
            fail("open status \(error)")
        }
    }

    func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        buffer.append(contentsOf: UnsafeBufferPointer(start: dataPointer.assumingMemoryBound(to: UInt8.self), count: dataLength))
        let hadGood = crc.good, hadBad = crc.bad
        let msgs = BudsProtocol.extract(&buffer, crc: crc)
        if hadGood == 0 && crc.good > 0 { Log.write("buds: frame checksums verified, bad frames are now dropped") }
        if crc.bad > hadBad && (crc.bad == 1 || crc.bad % 20 == 0) {
            Log.write("buds: \(crc.bad) frames with a bad checksum (\(crc.enforcing ? "dropped" : "kept, checksum not confirmed yet"))")
        }
        for m in msgs {
            let wasEar = ear
            let hadRevision = state.revision
            let changed = BudsProtocol.apply(m, &state)
            if hadRevision < 0, state.revision >= 0 {
                Log.write("buds: status rev \(state.revision) noise=\(state.noise) eq=\(state.eq) battery L\(state.battL) R\(state.battR) case \(state.battCase) spatial=\(String(describing: state.spatial)) touch=\(String(describing: state.touchOn)) quickConnect=\(String(describing: state.seamless))")
            }
            if state.findEnded {
                state.findEnded = false
                if findActive { findActive = false; findMessage = "The Buds stopped the search"; findTimer?.invalidate() }
            }
            if m.id == BudsProtocol.idStatus || m.id == BudsProtocol.idExtended {
                let e = state.ear
                if e != wasEar { Log.write("buds: wear \(wasEar.map { "\($0)" } ?? "?") -> \(e) (L=\(state.placeL) R=\(state.placeR))") }
                ear = e
                seenAt = Date()
            }
            if changed || findActive { onState?(state) }
        }
    }

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        Log.write("buds: wear channel closed")
        channel = nil
        buffer.removeAll()
        status = "closed"
        nextTryAt = Date().addingTimeInterval(3)
    }
}
