import Foundation

/// Which link carries a clipboard message to which device (same rule as ClipRoute in ClipCodec.kt). Pure, tested.
/// "both" (the default): text and small control messages over Bluetooth, Wi-Fi only for devices Bluetooth cannot reach
/// right now; images and large replies over Wi-Fi, Bluetooth only for devices with no Wi-Fi route (when small enough).
/// "ble": Bluetooth only for what fits, Wi-Fi only when Bluetooth reaches nobody. "wifi": Wi-Fi only.
enum ClipRoute {
    static let large: Set<String> = ["image", "diag"]

    static func plan(kind: String, size: Int, mode: String, wanted: Set<String>, ble: Set<String>, wifi: Set<String>,
                     maxBle: Int) -> (ble: Set<String>, wifi: Set<String>) {
        let fits = size <= maxBle
        let b = wanted.intersection(ble), w = wanted.intersection(wifi)
        if mode == "wifi" { return ([], w) }
        if large.contains(kind) {
            if mode == "ble" { return fits && !b.isEmpty ? (b, []) : ([], w) }
            return (fits ? b.subtracting(w) : [], w)
        }
        if !fits { return ([], w) }
        if mode == "ble" { return (b, b.isEmpty ? w : []) }
        return (b, w.subtracting(b))
    }
}

/// Bluetooth health per device (same rule as BleHealth in ClipCodec.kt). A device that refused for lack of pairing gets
/// no Bluetooth tries, so no pairing requests, for 6 hours (or until EarHop restarts); other failures wait 2, 4, 8 and
/// at most 10 minutes from the second in a row. Pure, tested.
final class BleHealth {
    private var until: [String: Date] = [:]
    private var fails: [String: Int] = [:]
    private var auth: Set<String> = []
    /// Devices that sent or received over Bluetooth with this Mac since EarHop started: they are paired.
    private var worked: Set<String> = []

    func usable(_ dev: String, now: Date) -> Bool { now >= (until[dev] ?? .distantPast) }
    func needsPairing(_ dev: String) -> Bool { auth.contains(dev) }
    /// True when Bluetooth with that device worked since EarHop started, so a timed out write is not a pairing problem.
    func knownGood(_ dev: String) -> Bool { worked.contains(dev) }

    /// Records a result; returns a line for the log when the state of that device changed.
    func report(_ dev: String, ok: Bool, pairing: Bool, now: Date) -> String? {
        if ok {
            worked.insert(dev)
            let was = until.removeValue(forKey: dev) != nil || (fails.removeValue(forKey: dev) ?? 0) >= 2 || auth.remove(dev) != nil
            return was ? "clip: Bluetooth to \(dev) works again" : nil
        }
        if pairing {
            auth.insert(dev); until[dev] = now.addingTimeInterval(6 * 3600)
            return "clip: Bluetooth to \(dev) needs pairing again (remove each from the other's Bluetooth list, then pair); no Bluetooth tries to it for 6 hours, Wi-Fi carries its clips"
        }
        let n = (fails[dev] ?? 0) + 1
        fails[dev] = n
        if n < 2 { return nil }
        let wait = min(600, 120 * pow(2, Double(min(n - 2, 3))))
        until[dev] = now.addingTimeInterval(wait)
        return "clip: Bluetooth to \(dev) failed \(n) times in a row; next try in \(Int(wait / 60)) min, Wi-Fi carries its clips"
    }

    func paired(_ dev: String) { until[dev] = nil; fails[dev] = nil; auth.remove(dev); worked.insert(dev) }
}
