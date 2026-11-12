import Foundation

/// What kind of device a name belongs to, for icons and words. Devices announce their kind with a "hello" control
/// message (encrypted like a clip: `{"type":"phone","app":"android","version":"1.4.0"}`); older builds that never say
/// get a guess from their name. A kind picked by hand in Settings, Devices wins over both.
enum DeviceKind: String, CaseIterable, Identifiable {
    case mac, phone, tablet, iphone, ipad, windows, linux, other
    var id: String { rawValue }

    var title: String {
        switch self {
        case .mac: return "Mac"
        case .phone: return "Android phone"
        case .tablet: return "Android tablet"
        case .iphone: return "iPhone"
        case .ipad: return "iPad"
        case .windows: return "Windows PC"
        case .linux: return "Linux computer"
        case .other: return "Other device"
        }
    }

    /// SF Symbols that exist on macOS 13.
    var symbol: String {
        switch self {
        case .mac: return "laptopcomputer"
        case .phone: return "smartphone"
        case .tablet: return "ipad.landscape"
        case .iphone: return "iphone"
        case .ipad: return "ipad"
        case .windows: return "pc"
        case .linux: return "desktopcomputer"
        case .other: return "display"
        }
    }

    /// A guess from the device name, for devices that have not said what they are.
    static func guess(_ name: String) -> DeviceKind {
        let n = name.lowercased()
        if n.hasPrefix("mac") { return .mac }
        if n.hasPrefix("iphone") { return .iphone }
        if n.hasPrefix("ipad") { return .ipad }
        if n.hasPrefix("tab") { return .tablet }
        if n.hasPrefix("phone") || n.hasPrefix("android") || n.hasPrefix("pixel") || n.hasPrefix("galaxy") { return .phone }
        if n.hasPrefix("pc") || n.hasPrefix("win") || n.hasSuffix("-pc") || n.hasPrefix("laptop") { return .windows }
        if n.hasPrefix("linux") || n.hasPrefix("ubuntu") || n.hasPrefix("fedora") { return .linux }
        return .other
    }
}

/// Friendly names and icons for device names. The kinds come from the settings (announced and picked by hand) and are
/// refreshed whenever the settings change, so every view shows the same icon for a device.
enum DeviceName {
    private static let lock = NSLock()
    private static var announced: [String: String] = [:]
    private static var overrides: [String: String] = [:]

    static func update(_ cfg: Config) {
        lock.lock(); announced = cfg.deviceKinds; overrides = cfg.deviceKindOverrides; lock.unlock()
    }

    static func pretty(_ n: String) -> String {
        switch n {
        case "mac": return "Mac"
        case "phone": return "Phone"
        case "tab": return "Tablet"
        default:
            // Apple's own spelling for iPhone and iPad names (iphone, ipad-2), else a capital first letter.
            for (low, nice) in [("iphone", "iPhone"), ("ipad", "iPad")] where n.hasPrefix(low) { return nice + n.dropFirst(low.count) }
            return n.prefix(1).uppercased() + n.dropFirst()
        }
    }

    static func kind(_ n: String) -> DeviceKind {
        lock.lock(); defer { lock.unlock() }
        if let k = overrides[n].flatMap(DeviceKind.init(rawValue:)) { return k }
        if let k = announced[n].flatMap(DeviceKind.init(rawValue:)) { return k }
        return DeviceKind.guess(n)
    }

    /// Whether the kind was announced by the device itself (else guessed from the name or set by hand).
    static func announcedKind(_ n: String) -> DeviceKind? {
        lock.lock(); defer { lock.unlock() }
        return announced[n].flatMap(DeviceKind.init(rawValue:))
    }

    static func symbol(_ n: String) -> String { kind(n).symbol }
}

/// The "hello" control message: what this device is. Sent at start, every 10 minutes and when a device joins.
enum Hello {
    static func encode(kind: DeviceKind, app: String, version: String) -> Data {
        (try? JSONSerialization.data(withJSONObject: ["type": kind.rawValue, "app": app, "version": version], options: [.sortedKeys])) ?? Data()
    }

    /// The announced kind, or nil when the message is not a valid hello.
    static func decode(_ d: Data) -> (kind: DeviceKind, app: String, version: String)? {
        guard let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let t = o["type"] as? String,
              let k = DeviceKind(rawValue: t) else { return nil }
        return (k, o["app"] as? String ?? "", o["version"] as? String ?? "")
    }
}
