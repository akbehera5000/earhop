import SwiftUI
import ServiceManagement

/// Observable state shared by the popover, the settings window and the tick loop.
final class AppState: ObservableObject {
    @Published var cfg: Config {
        didSet {
            guard cfg != oldValue else { return }
            cfg.save()
            onConfigChange?(cfg)
        }
    }
    @Published var playing = false
    @Published var ear: Ear = .unknown
    @Published var owner = false
    @Published var peers: [Controller.PeerInfo] = []
    @Published var logLines: [String] = []
    @Published var history: [HandoffEvent] = []
    @Published var safeMode = false
    @Published var phase: HandoffPhase = .idle
    @Published var stage: String?
    @Published var holdReason: String?
    @Published var lastFailure: String?
    @Published var earReading = EarReading.unknown
    /// The other device that holds the earbuds right now, if any.
    @Published var holder: String?
    /// Devices whose packets are refused, with the reason and when it was last seen.
    @Published var rejected: [String: (reason: String, at: Date)] = [:]
    /// Device names used by two devices at once, with when it was last seen.
    @Published var duplicates: [String: Date] = [:]
    @Published var inCall = false
    @Published var playingApps: [String] = []
    @Published var battery: (Int, Int)?
    @Published var caseBattery: Int?
    @Published var budsStatus = "off"
    @Published var keysAllowed = true
    @Published var buds = BudsProtocol.State()
    @Published var budsFindActive = false
    @Published var budsFindMessage = ""
    /// Set by the app; the Buds views call its commands directly.
    var budsLink: BudsWear?
    var budsConnected: Bool { budsStatus == "connected" }
    @Published var detectMessage = ""
    @Published var transport = "starting"
    /// Whether news from another device arrived over Bluetooth LE / Wi-Fi in the last 10 s.
    @Published var bleLive = false
    /// The last clipboard event ("Sent text", "Received an image from phone") and when.
    @Published var clipStatus = ""
    @Published var clipAt = Date.distantPast
    var onClipSend: (() -> Void)?
    /// Recent clips sent and received, newest first. Memory only: never written to disk, gone when EarHop quits.
    @Published var clipRecent: [ClipRecent] = []
    /// Clipboard sync paused until then (not saved; a restart resumes it).
    @Published var clipPausedUntil = Date.distantPast
    /// How many devices each link can reach for clips right now.
    @Published var clipBlePeers = 0
    @Published var clipWifiPeers = 0
    var onClipRecopy: ((ClipRecent) -> Void)?
    var onClipPause: ((Date) -> Void)?
    var clipPaused: Bool { clipPausedUntil > Date() }
    /// Automatic earbud switching paused until then (not saved; a restart resumes it).
    @Published var switchPausedUntil = Date.distantPast
    var onSwitchPause: ((Date) -> Void)?
    var switchPaused: Bool { switchPausedUntil > Date() }
    @Published var wifiLive = false
    @Published var discovered: [String] = []
    var onUndo: (() -> Void)?
    var onClearSafe: (() -> Void)?
    var onDetect: (() -> Void)?
    @Published var paired: [BlueUtil.Paired] = []
    @Published var launchAtLogin: Bool {
        didSet {
            guard launchAtLogin != oldValue else { return }
            do {
                if launchAtLogin { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                Log.write("launch at login failed: \(error.localizedDescription)")
                launchAtLogin = oldValue
            }
        }
    }

    var onConfigChange: ((Config) -> Void)?
    var onTake: (() -> Void)?
    var onRelease: (() -> Void)?
    var onRestart: (() -> Void)?
    var onOpenSettings: (() -> Void)?

    /// Values read once at launch. Changing them needs a restart.
    private let startup: (Int, String, Int, Bool, [Int], String)

    init(cfg: Config) {
        self.cfg = cfg
        self.startup = (cfg.port, cfg.secret, cfg.minRSSI, cfg.allowAnyModel, cfg.models, cfg.deviceName)
        self.launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    var needsRestart: Bool {
        (cfg.port, cfg.secret, cfg.minRSSI, cfg.allowAnyModel, cfg.models, cfg.deviceName) != startup
    }

    func addEvent(_ e: HandoffEvent) {
        history.insert(e, at: 0)
        if history.count > 100 { history.removeLast(history.count - 100) }
    }

    func appendLog(_ line: String) {
        logLines.append(line)
        if logLines.count > 300 { logLines.removeFirst(logLines.count - 300) }
    }

    func refreshPaired() {
        DispatchQueue.main.async { self.paired = BlueUtil.paired() }
    }
}

extension Ear {
    var label: String {
        switch self {
        case .both: return "Both in ears"
        case .one: return "One in ear"
        case .none: return "Out of ears"
        case .unknown: return "Not detected"
        }
    }
    var symbol: String {
        switch self {
        case .both: return "ear.fill"
        case .one: return "ear"
        case .none: return "ear.trianglebadge.exclamationmark"
        case .unknown: return "questionmark.circle"
        }
    }
}

extension Config {
    static let accents: [(id: String, name: String, color: Color)] = [
        ("blue", "Blue", Color(red: 0.18, green: 0.48, blue: 1.0)),
        ("purple", "Purple", Color(red: 0.48, green: 0.36, blue: 1.0)),
        ("green", "Green", Color(red: 0.12, green: 0.72, blue: 0.42)),
        ("orange", "Orange", Color(red: 1.0, green: 0.54, blue: 0.12)),
        ("pink", "Pink", Color(red: 1.0, green: 0.31, blue: 0.55)),
    ]
    /// "auto" follows the earbuds: cool blue for AirPods, violet for Galaxy Buds.
    static let airpodsAccent = Color(red: 0.30, green: 0.62, blue: 1.0)
    static let budsAccent = Color(red: 0.58, green: 0.40, blue: 1.0)
    var accentColor: Color {
        if accent == "auto" { return isBuds ? Config.budsAccent : Config.airpodsAccent }
        return Config.accents.first { $0.id == accent }?.color ?? Config.accents[0].color
    }
}
