import Foundation

/// Settings shared between devices, so one change applies everywhere: switching rules, clipboard options, low battery
/// alerts, speaker fallback and the sleep timer length. Device name, secret, earbud addresses and the per-device Buds
/// settings stay local. Every shared setting carries when it was last changed; the newest change wins on every device
/// (stamps are corrected for each device's clock offset). Same rule and names as SettingsSync.kt.
enum SettingsSync {
    /// The shared settings, by their names on the wire.
    static let keys = ["autoSwitch", "speakerFallback", "followAttention", "lowBatteryAlerts", "volumeHandover", "pauseWhenOneOut",
                       "transport", "clipSync", "clipImages", "clipReceive", "clipTransport", "clipSkipPasswords", "sleepMinutes"]

    /// The changes to apply from a remote set: keys whose remote stamp (converted to this clock) is newer than ours and
    /// whose value differs from ours. An equal value is never taken: every device sends the whole set every 5 minutes,
    /// and each conversion moves a stamp a little, so taking equal values made the same settings bounce between the
    /// devices forever, with stamps that kept growing until an old value could beat a real newer change.
    static func merge(local: [String: Int64], remote: [String: (value: Any, stamp: Int64)], offsetMs: Int64,
                      localValues: [String: Any] = [:], nowMs: Int64 = .max / 2) -> [String: (value: Any, stamp: Int64)] {
        var out: [String: (Any, Int64)] = [:]
        for (k, r) in remote where keys.contains(k) {
            if let v = localValues[k], same(v, r.value) { continue }
            let mine = r.stamp - offsetMs
            // A stamp more than a minute in the future (a clock that jumped ahead) is refused: kept, it would beat
            // every real change on every device until that time came.
            if mine > nowMs + 60_000 { continue }
            if mine > (local[k] ?? 0) { out[k] = (r.value, mine) }
        }
        return out.mapValues { (value: $0.0, stamp: $0.1) }
    }

    /// Values from JSON arrive as NSNumber or String; ours are Bool, Int or String.
    static func same(_ a: Any, _ b: Any) -> Bool {
        if let x = a as? String, let y = b as? String { return x == y }
        if let x = a as? NSNumber, let y = b as? NSNumber { return x.isEqual(to: y) }
        return false
    }

    static func values(_ c: Config) -> [String: Any] {
        ["autoSwitch": c.autoSwitch, "speakerFallback": c.speakerFallback, "followAttention": c.followAttention,
         "lowBatteryAlerts": c.lowBatteryAlerts, "volumeHandover": c.volumeHandover, "pauseWhenOneOut": c.pauseWhenOneOut,
         "transport": c.transport, "clipSync": c.clipSync, "clipImages": c.clipImages, "clipReceive": c.clipReceive,
         "clipTransport": c.clipTransport, "clipSkipPasswords": c.clipSkipPasswords, "sleepMinutes": c.sleepMinutes]
    }

    static func apply(_ k: String, _ v: Any, to c: inout Config) {
        switch k {
        case "autoSwitch": if let b = v as? Bool { c.autoSwitch = b }
        case "speakerFallback": if let b = v as? Bool { c.speakerFallback = b }
        case "followAttention": if let b = v as? Bool { c.followAttention = b }
        case "lowBatteryAlerts": if let b = v as? Bool { c.lowBatteryAlerts = b }
        case "volumeHandover": if let b = v as? Bool { c.volumeHandover = b }
        case "pauseWhenOneOut": if let b = v as? Bool { c.pauseWhenOneOut = b }
        case "transport": if let s = v as? String, ["ble", "both", "wifi"].contains(s) { c.transport = s }
        case "clipSync": if let b = v as? Bool { c.clipSync = b }
        case "clipImages": if let b = v as? Bool { c.clipImages = b }
        case "clipReceive": if let b = v as? Bool { c.clipReceive = b }
        case "clipTransport": if let s = v as? String, ["ble", "both", "wifi"].contains(s) { c.clipTransport = s }
        case "clipSkipPasswords": if let b = v as? Bool { c.clipSkipPasswords = b }
        case "sleepMinutes": if let n = (v as? NSNumber)?.intValue, (5 ... 240).contains(n) { c.sleepMinutes = n }
        default: break
        }
    }

    /// The wire form: {"v": {key: value}, "t": {key: stamp}}.
    static func encode(_ c: Config, stamps: [String: Int64]) -> Data {
        let obj: [String: Any] = ["v": values(c), "t": stamps.filter { keys.contains($0.key) }]
        return (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
    }

    static func decode(_ d: Data) -> [String: (value: Any, stamp: Int64)] {
        guard let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let v = o["v"] as? [String: Any],
              let t = o["t"] as? [String: Any] else { return [:] }
        var out: [String: (value: Any, stamp: Int64)] = [:]
        for (k, val) in v { if let s = (t[k] as? NSNumber)?.int64Value, s > 0 { out[k] = (val, s) } }
        return out
    }
}
