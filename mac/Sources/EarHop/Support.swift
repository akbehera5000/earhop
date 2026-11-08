import CoreWLAN
import Foundation
import AppKit
import Carbon.HIToolbox
import CoreImage
import UserNotifications

enum Notifier {
    /// Without a delegate macOS drops notifications while EarHop counts as the active app (popover or settings open).
    private final class Presenter: NSObject, UNUserNotificationCenterDelegate {
        func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification,
                                    withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
            if #available(macOS 11.0, *) { done([.banner, .list, .sound]) } else { done([.alert, .sound]) }
        }
        /// Clicking a "file received" notification shows the file in Finder.
        func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse,
                                    withCompletionHandler done: @escaping () -> Void) {
            if let p = r.notification.request.content.userInfo["file"] as? String, FileManager.default.fileExists(atPath: p) {
                DispatchQueue.main.async { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: p)]) }
            }
            done()
        }
    }
    private static let presenter = Presenter()
    /// False when the user turned EarHop's notifications off in System Settings (shown on the Settings page).
    static var allowed = true

    static func requestAuth() {
        let c = UNUserNotificationCenter.current()
        c.delegate = presenter
        c.requestAuthorization(options: [.alert, .sound]) { ok, err in
            c.getNotificationSettings { s in
                allowed = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
                Log.write("notifications: \(allowed ? "allowed" : "not allowed in System Settings")"
                          + (s.alertSetting == .disabled ? ", banners off" : "") + (err.map { " (\($0.localizedDescription))" } ?? "") + (ok ? "" : ", request refused"))
            }
        }
    }
    static func post(_ title: String, _ body: String, file: URL? = nil) {
        let c = UNMutableNotificationContent()
        c.title = title; c.body = body; c.sound = .default
        if let file { c.userInfo = ["file": file.path] }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil)) { err in
            if let err { Log.write("notifications: could not show \"\(title)\": \(err.localizedDescription)") }
        }
    }
}

/// Global hotkey Control+Option+Command+E.
enum HotKey {
    static var fire: (() -> Void)?
    private static var ref: EventHotKeyRef?
    private static var handler: EventHandlerRef?

    static func register() {
        guard ref == nil else { return }
        // The handler is installed once. Installing it again after unregister() would run fire() twice per press,
        // which takes the earbuds and gives them straight back.
        if handler == nil {
            var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
                HotKey.fire?(); return noErr
            }, 1, &spec, nil, &handler)
        }
        RegisterEventHotKey(UInt32(kVK_ANSI_E), UInt32(controlKey | optionKey | cmdKey),
                            EventHotKeyID(signature: OSType(0x45415248), id: 1),
                            GetApplicationEventTarget(), 0, &ref)
    }

    static func unregister() {
        if let r = ref { UnregisterEventHotKey(r); ref = nil }
    }
}

enum Setup {
    struct Found { let name: String; let address: String; let model: Int? }

    /// Finds the currently connected AirPods through system_profiler.
    static func detectConnectedAirPods(nameContains: String = "airpods") -> Found? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/system_profiler")
        p.arguments = ["SPBluetoothDataType", "-json"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = Pipe()
        guard (try? p.run()) != nil else { return nil }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let top = (root["SPBluetoothDataType"] as? [[String: Any]])?.first,
              let connected = top["device_connected"] as? [[String: Any]] else { return nil }
        for entry in connected {
            for (name, v) in entry where name.localizedCaseInsensitiveContains(nameContains) {
                guard let d = v as? [String: Any], let addr = d["device_address"] as? String else { continue }
                var model: Int?
                if let pid = d["device_productID"] as? String { model = Int(pid.replacingOccurrences(of: "0x", with: ""), radix: 16) }
                return Found(name: name, address: addr.lowercased().replacingOccurrences(of: ":", with: "-"), model: model)
            }
        }
        return nil
    }
}

enum QR {
    static func image(_ text: String, size: CGFloat = 180) -> NSImage? {
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(text.utf8), forKey: "inputMessage")
        f.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f.outputImage else { return nil }
        let scale = size / out.extent.width
        let scaled = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}


enum WifiInfo {
    /// Current Wi-Fi name, or nil when macOS does not reveal it (it can need Location permission).
    static func ssid() -> String? { CWWiFiClient.shared().interface()?.ssid() }
}

enum AutoGate {
    static func reason(_ c: Config, now: Date = Date()) -> String? {
        if c.quietEnabled {
            let cal = Calendar.current
            let m = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
            let a = c.quietStartMinutes, b = c.quietEndMinutes
            let inside = a <= b ? (m >= a && m < b) : (m >= a || m < b)
            if inside { return "quiet hours" }
        }
        if c.wifiOnly, !c.wifiNames.isEmpty, let s = WifiInfo.ssid(), !c.wifiNames.contains(s) {
            return "not on a listed Wi-Fi (\(s))"
        }
        return nil
    }
}

