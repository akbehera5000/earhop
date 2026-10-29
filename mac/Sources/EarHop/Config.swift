import Foundation
import AppKit

struct Config: Codable, Equatable {
    var secret: String
    var deviceName: String = "mac"
    var airpodsAddress: String = ""
    var blueutilPath: String = "/opt/homebrew/bin/blueutil"
    var liveMode: Bool = false
    var port: Int = 47474
    var debounceSeconds: Double = 0.5
    var cooldownSeconds: Double = 2
    var quietSeconds: Double = 2
    var releaseAfterOutSeconds: Double = 120
    var minRSSI: Int = -75
    var allowAnyModel: Bool = false
    var models: [Int] = [0x2014, 0x2924, 0x2024]
    var minPlaySeconds: Double = 1
    var pauseWhenOneOut: Bool = true
    var resumeWithinSeconds: Double = 120
    var priority: [String] = ["mac", "tab", "phone"]
    var ignoredApps: [String] = []
    var notifications: Bool = true
    var hotkeyEnabled: Bool = true
    var detectCalls: Bool = true
    var safeModeAfterFailures: Int = 3
    var gentleScan: Bool = false
    var quietEnabled: Bool = false
    var quietStartMinutes: Int = 22 * 60
    var quietEndMinutes: Int = 7 * 60
    var wifiOnly: Bool = false
    var wifiNames: [String] = []
    var volumeEnabled: Bool = false
    var volumePercent: Int = 40
    var menuBarBattery: Bool = true
    var appearance: String = "system"
    var accent: String = "auto"
    var onboarded: Bool = false
    var earbuds: String = "airpods"
    var budsAddress: String = ""
    /// "auto": explicit Pause and Play commands, with the Play/Pause key as a checked fallback. "key": the key only.
    var mediaControl: String = "auto"
    /// "both": Bluetooth LE and Wi-Fi together (default). "ble": Bluetooth LE only. "wifi": Wi-Fi only.
    var transport: String = "both"
    /// Clipboard sync with the other devices, and whether images are included (Wi-Fi only).
    var clipSync: Bool = true
    var clipImages: Bool = true
    /// How clips travel: "both" (Bluetooth LE and Wi-Fi together), "ble" (Bluetooth LE, Wi-Fi only for what it cannot
    /// carry), "wifi" (Wi-Fi only). Separate from `transport`, which is for the earbud handoff.
    var clipTransport: String = "both"
    /// Send copies as they happen; off means only when Send clipboard is pressed.
    var clipAuto: Bool = true
    /// Put clips from the other devices on this Mac's clipboard.
    var clipReceive: Bool = true
    /// Devices this Mac does not send clips to.
    var clipExclude: [String] = []
    /// A notification when a clip arrives.
    var clipNotify: Bool = false
    /// Recent clips kept in the popover (memory only); 0 turns the list off.
    var clipHistory: Int = 10
    /// Automatic earbud switching. Off: only Take and Release move the earbuds.
    var autoSwitch: Bool = true
    /// "auto": follow the earbuds in use (connected here, or the pair the device holding them reports).
    /// "manual": keep `earbuds` as chosen.
    var earbudsMode: String = "auto"

    var isBuds: Bool { earbuds == "buds" }
    /// Address of the active pair.
    var activeAddress: String { isBuds ? budsAddress : airpodsAddress }
    var earbudsShort: String { isBuds ? "Buds" : "AirPods" }
    var earbudsName: String { isBuds ? "Galaxy Buds2 Pro" : "AirPods" }

    static let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".earhop")
    static let file = dir.appendingPathComponent("config.json")

    init(secret: String) { self.secret = secret }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        var base = Config(secret: try c.decode(String.self, forKey: .secret))
        func opt<T: Decodable>(_ k: CodingKeys, _ v: inout T) { if let x = try? c.decode(T.self, forKey: k) { v = x } }
        opt(.deviceName, &base.deviceName); opt(.airpodsAddress, &base.airpodsAddress)
        opt(.blueutilPath, &base.blueutilPath); opt(.liveMode, &base.liveMode); opt(.port, &base.port)
        opt(.debounceSeconds, &base.debounceSeconds); opt(.cooldownSeconds, &base.cooldownSeconds)
        opt(.quietSeconds, &base.quietSeconds); opt(.releaseAfterOutSeconds, &base.releaseAfterOutSeconds)
        opt(.minRSSI, &base.minRSSI); opt(.allowAnyModel, &base.allowAnyModel); opt(.models, &base.models)
        opt(.minPlaySeconds, &base.minPlaySeconds); opt(.pauseWhenOneOut, &base.pauseWhenOneOut)
        opt(.resumeWithinSeconds, &base.resumeWithinSeconds); opt(.priority, &base.priority)
        opt(.ignoredApps, &base.ignoredApps); opt(.notifications, &base.notifications)
        opt(.hotkeyEnabled, &base.hotkeyEnabled); opt(.detectCalls, &base.detectCalls)
        opt(.safeModeAfterFailures, &base.safeModeAfterFailures); opt(.gentleScan, &base.gentleScan)
        opt(.quietEnabled, &base.quietEnabled); opt(.quietStartMinutes, &base.quietStartMinutes)
        opt(.quietEndMinutes, &base.quietEndMinutes); opt(.wifiOnly, &base.wifiOnly)
        opt(.wifiNames, &base.wifiNames); opt(.volumeEnabled, &base.volumeEnabled)
        opt(.volumePercent, &base.volumePercent); opt(.menuBarBattery, &base.menuBarBattery)
        opt(.appearance, &base.appearance); opt(.accent, &base.accent); opt(.onboarded, &base.onboarded)
        opt(.earbuds, &base.earbuds); opt(.budsAddress, &base.budsAddress); opt(.mediaControl, &base.mediaControl); opt(.transport, &base.transport)
        opt(.clipSync, &base.clipSync); opt(.clipImages, &base.clipImages); opt(.clipTransport, &base.clipTransport)
        opt(.clipAuto, &base.clipAuto); opt(.clipReceive, &base.clipReceive); opt(.clipExclude, &base.clipExclude)
        opt(.clipNotify, &base.clipNotify); opt(.clipHistory, &base.clipHistory)
        opt(.autoSwitch, &base.autoSwitch); opt(.earbudsMode, &base.earbudsMode)
        self = base
    }

    static func load() -> Config {
        let fm = FileManager.default
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: file),
           let c = try? JSONDecoder().decode(Config.self, from: data) {
            return c
        }
        var bytes = [UInt8](repeating: 0, count: 16)
        for i in bytes.indices { bytes[i] = UInt8.random(in: 0...255) }
        let c = Config(secret: bytes.map { String(format: "%02x", $0) }.joined())
        c.save()
        return c
    }

    /// Set for the self test and snapshots: their sample settings must never reach ~/.earhop/config.json.
    static var readOnly = false

    func save() {
        guard !Config.readOnly else { return }
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? enc.encode(self) else { return }
        try? data.write(to: Config.file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Config.file.path)
    }
}

enum Log {
    static let file = Config.dir.appendingPathComponent("earhop.log")
    private static let q = DispatchQueue(label: "earhop.log")
    static var onLine: ((String) -> Void)?
    /// Off for the self test and snapshots, so test decisions never mix with the real log.
    static var toFile = true
    private static let fmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss.SSS"; return f
    }()

    /// Last lines of the log file, for the Copy log button.
    static func tail(_ n: Int = 400) -> String {
        // Right after a rotation the current file is short, so the previous one fills in the earlier lines.
        let old = (try? String(contentsOf: file.appendingPathExtension("1"), encoding: .utf8)) ?? ""
        let cur = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        return (old + cur).split(separator: "\n", omittingEmptySubsequences: true).suffix(n).joined(separator: "\n")
    }

    static func copyToPasteboard() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(tail(), forType: .string)
    }

    static func write(_ msg: String) {
        let line = "\(fmt.string(from: Date())) \(msg)\n"
        print(line, terminator: "")
        let shown = line.trimmingCharacters(in: .newlines)
        DispatchQueue.main.async { onLine?(shown) }
        guard toFile else { return }
        q.async {
            // Keep the log bounded: at 2 MB the current file becomes earhop.log.1 (replacing the older one).
            if let size = (try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? Int, size > 2_000_000 {
                let old = file.appendingPathExtension("1")
                try? FileManager.default.removeItem(at: old)
                try? FileManager.default.moveItem(at: file, to: old)
            }
            if let h = try? FileHandle(forWritingTo: file) {
                h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close()
            } else {
                try? line.write(to: file, atomically: true, encoding: .utf8)
            }
        }
    }
}
