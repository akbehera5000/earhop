import Foundation
import CoreBluetooth

/// Reads the AirPods proximity pairing BLE beacon (EXPERIMENTAL byte layout, see project brief).
final class EarMonitor: NSObject, CBCentralManagerDelegate {
    private var central: CBCentralManager!
    private let minRSSI: Int
    private let allowAnyModel: Bool
    private let knownModels: Set<UInt16>
    private var lastRejectLog = Date.distantPast
    /// One entry per broadcasting address: in-ear count, smoothed signal strength, battery.
    private struct Source { var count: Int; var rssi: Double; var at: Date; var budA: Int?; var budB: Int?; var cas: Int?; var casAt: Date? }
    private var sources: [UUID: Source] = [:]
    var onServiceUUIDs: (([CBUUID], UUID) -> Void)?
    private var lastRawLog = Date.distantPast
    private var lastKey = ""

    init(minRSSI: Int, allowAnyModel: Bool, models: [Int]) {
        self.knownModels = Set(models.map { UInt16($0) })
        self.minRSSI = minRSSI
        self.allowAnyModel = allowAnyModel
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    private var gentle = false
    private var dutyTimer: Timer?

    var ear: Ear {
        let fresh = sources.values.filter { Date().timeIntervalSince($0.at) < (gentle ? 6 : 2.5) }
        guard let n = Beacon.pick(fresh.map { ($0.count, $0.rssi) }) else { return .unknown }
        return n == 2 ? .both : (n == 1 ? .one : .none)
    }

    /// The strongest recent source: your own AirPods, for battery.
    private var strongest: Source? {
        sources.values.filter { Date().timeIntervalSince($0.at) < 10 }.max { $0.rssi < $1.rssi }
    }

    /// The beacon works well on the Mac (each source tracked, about 0.5 s latency), so a fresh reading is high confidence.
    var reading: EarReading {
        let e = ear
        return e == .unknown ? .unknown : EarReading(ear: e, source: "airpods-beacon", confidence: .high)
    }

    /// Battery of the two buds as percent, from your AirPods (the strongest recent beacon).
    var battery: (Int, Int)? {
        guard let b = strongest, let x = b.budA, let y = b.budB else { return nil }
        return (x * 10, y * 10)
    }

    /// Charging case battery as percent, when the case is reporting.
    var caseBattery: Int? {
        guard let b = strongest, let c = b.cas, let at = b.casAt, Date().timeIntervalSince(at) < 60 else { return nil }
        return c * 10
    }

    private func startScan() {
        guard central.state == .poweredOn, !central.isScanning else { return }
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    /// While audio is streaming to the AirPods, scan in short bursts instead of continuously.
    /// Continuous scanning shares the radio with the audio link and can cause crackling.
    func setGentle(_ on: Bool) {
        guard on != gentle else { return }
        gentle = on
        dutyTimer?.invalidate()
        dutyTimer = nil
        if on {
            dutyTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                self.startScan()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { if self.gentle { self.central.stopScan() } }
            }
            central.stopScan()
        } else {
            startScan()
        }
        Log.write("ear: scanning \(on ? "gently (audio streaming)" : "continuously")")
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        Log.write("ear: bluetooth state \(c.state.rawValue)")
        if c.state == .poweredOn {
            c.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if let uuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID], !uuids.isEmpty {
            onServiceUUIDs?(uuids, p.identifier)
        }
        guard let md = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, md.count >= 9,
              md[0] == 0x4C, md[1] == 0x00, md[2] == 0x07 else { return }
        let rssi = RSSI.intValue
        if rssi < minRSSI || rssi == 127 { return }
        guard let beacon = Beacon.parse(md) else { return }
        let model = beacon.model
        if !allowAnyModel && !knownModels.contains(model) {
            if Date().timeIntervalSince(lastRejectLog) > 30 {
                lastRejectLog = Date()
                Log.write("ear: ignored other AirPods model=\(String(format: "%04x", model)) rssi=\(rssi)")
            }
            return
        }
        let ear = beacon.ear
        let key = md[7...min(12, md.count - 1)].map { String(format: "%02x", $0) }.joined()
        if key != lastKey {
            lastKey = key
            Log.write("ear: CHANGE status-bytes=\(key) state=\(ear.rawValue) rssi=\(rssi)")
        }
        if Date().timeIntervalSince(lastRawLog) > 5 {
            lastRawLog = Date()
            Log.write("ear: raw=\(md.map { String(format: "%02x", $0) }.joined()) rssi=\(rssi) model=\(String(format: "%04x", model)) state=\(ear.rawValue)")
        }
        // Each address keeps its own reading; the signal strength is smoothed so one weak packet does not move it.
        let count = ear == .both ? 2 : (ear == .one ? 1 : 0)
        var s = sources[p.identifier] ?? Source(count: count, rssi: Double(rssi), at: Date())
        s.count = count
        s.rssi = s.rssi * 0.7 + Double(rssi) * 0.3
        s.at = Date()
        s.budA = beacon.budA; s.budB = beacon.budB
        if let c = beacon.caseLevel { s.cas = c; s.casAt = Date() }
        sources[p.identifier] = s
        sources = sources.filter { Date().timeIntervalSince($0.value.at) < 30 }
    }
}
