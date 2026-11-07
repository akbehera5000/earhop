import Foundation
import CoreBluetooth

/// Bluetooth LE fallback transport. A status is advertised as one 128 bit service UUID
/// (see BlePayload) and picked up by the existing scan in EarMonitor.
final class BleLink: NSObject, CBPeripheralManagerDelegate {
    private var manager: CBPeripheralManager!
    private let secret: String
    private let selfName: String
    /// Per device: the last advertisement passed on and when, so the steady stream of repeats is thinned out.
    private var lastForward: [String: (seq: Int, at: Date)] = [:]
    /// When a device was last heard over Bluetooth LE. With nobody heard for a minute the advertisement is refreshed
    /// only every 30 s (a state change still goes out at once).
    private(set) var lastPeerAt = Date.distantPast
    private var activatedAt = Date.distantPast
    private var lastAdvert: [UInt8] = []
    private var lastAdvertAt = Date.distantPast
    private(set) var active = false
    var onStatus: ((Status) -> Void)?

    init(secret: String, selfName: String) {
        self.secret = secret
        self.selfName = selfName
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    /// Any valid name works since v4 (the advertisement carries an id derived from it).
    var supported: Bool { DeviceNames.valid(selfName) }
    /// A valid v4 advertisement from an id whose name is not known yet, and where it came from (looked up once).
    var onUnknownId: ((Int, UUID) -> Void)?

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        Log.write("ble: peripheral state \(p.state.rawValue)")
    }

    func setActive(_ on: Bool) {
        guard on != active else { return }
        active = on
        if on { activatedAt = Date() }
        if !on { manager.stopAdvertising(); lastAdvert = []; lastFlagsKey = [] }
    }

    private var lowPower = false
    private var lastFlagsKey: [UInt8] = []

    /// Advertises the status with the same sequence number Wi-Fi uses (low 16 bits), so receivers can tell which copy
    /// is newer. The advertisement is restarted when the state changes, otherwise every 5 s (30 s in low power).
    func advertise(_ s: Status) {
        guard active, supported, manager.state == .poweredOn else { return }
        var st = s
        st.seq = s.seq & 0xFFFF
        let probe = BlePayload.encode4(st, secret: secret, sec: UInt32(Proto.nowMs() / 1000))
        let key = [probe[1], probe[2], probe[5]]
        let quiet = Date().timeIntervalSince(max(lastPeerAt, activatedAt)) > 60
        if quiet != lowPower { lowPower = quiet; Log.write("ble: \(quiet ? "no device heard for a minute, low power" : "device heard, normal rate")") }
        if key == lastFlagsKey && Date().timeIntervalSince(lastAdvertAt) < (lowPower ? 30 : 5) { return }
        let bytes = probe
        lastFlagsKey = key
        lastAdvert = bytes
        lastAdvertAt = Date()
        manager.stopAdvertising()
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(data: Data(bytes))]])

    }

    /// Called by the scanner for every advertisement that carries service UUIDs.
    /// The Bluetooth identifier each device's status advertisements come from, so clipboard sync can connect back.
    private(set) var peerIds: [String: (id: UUID, at: Date)] = [:]

    func handle(_ uuids: [CBUUID], from peripheral: UUID) {
        guard active else { return }
        for u in uuids {
            let b = [UInt8](u.data)
            guard b.count == 16 else { continue }
            let st: Status
            if b[0] == 0xEB, let m = BlePayload.decode4(b, secret: secret) {
                guard let name = NameBook.shared.name(for: m.id) else { onUnknownId?(m.id, peripheral); continue }
                st = BlePayload.status(m, name: name)
            } else if b[0] == 0xEA, let m = BlePayload.decode(b, secret: secret) {
                // A device still on the old format (before 1.3): fixed names, checked against this clock.
                st = Status(dev: m.dev, ts: Proto.nowMs(), seq: m.seq, playing: m.playing, ear: m.ear, owner: m.owner,
                            call: m.call, prof: m.prof, v: 3)
            } else { continue }
            guard st.dev != selfName else { continue }
            lastPeerAt = Date()
            peerIds[st.dev] = (peripheral, Date())
            // The scan reports the same advertisement many times a second. Pass on a new one at once, a repeat every 0.5 s
            // (the controller decides which copy is newest and treats repeats as "still here").
            if let last = lastForward[st.dev], last.seq == st.seq, Date().timeIntervalSince(last.at) < 0.5 { continue }
            lastForward[st.dev] = (st.seq, Date())
            onStatus?(st)
        }
    }
}
