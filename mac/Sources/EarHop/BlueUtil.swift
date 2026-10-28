import Foundation
import IOBluetooth

/// Connects and disconnects the earbuds. Everything runs inside the app through macOS Bluetooth (IOBluetooth); the
/// Homebrew tool blueutil is optional and only used as a second try when it is installed.
struct BlueUtil {
    let path: String
    let address: String

    var available: Bool { !address.isEmpty }
    /// Whether the optional blueutil tool is installed at the configured path.
    var hasTool: Bool { FileManager.default.isExecutableFile(atPath: path) }

    @discardableResult
    private func run(_ args: [String]) -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let out = Pipe()
        p.standardOutput = out
        p.standardError = out
        do { try p.run() } catch { Log.write("blueutil: \(error)"); return nil }
        p.waitUntilExit()
        return String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Instant connection check through IOBluetooth (no process spawn). nil if the device is unknown.
    func fastConnected() -> Bool? {
        guard !address.isEmpty, let d = IOBluetoothDevice(addressString: address) else { return nil }
        return d.isConnected()
    }

    func isConnected() -> Bool { fastConnected() ?? (hasTool && run(["--is-connected", address]) == "1") }
    func connect() { Log.write("blueutil connect: \(run(["--connect", address]) ?? "failed")") }

    /// Asks macOS to connect the earbuds, without waiting: the caller watches the link and moves on once it is up.
    /// macOS then brings up the audio profiles of a paired audio device by itself, as it does from the Bluetooth menu.
    func startConnectInApp() {
        let addr = address
        DispatchQueue.main.async {
            guard let d = IOBluetoothDevice(addressString: addr) else { Log.write("connect: device \(addr) unknown to macOS"); return }
            let r = d.openConnection(ConnectWaiter.shared)
            Log.write("connect: asked macOS to connect (status \(r))")
        }
    }

    /// Starts blueutil --connect without waiting for it. Only used when the tool is installed.
    func startConnect() -> Process? {
        guard hasTool else { return nil }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = ["--connect", address]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { Log.write("blueutil: \(error)"); return nil }
        return p
    }
    /// Disconnects in process first (fast), and falls back to blueutil only when the link is still up after 3 s.
    func disconnect() {
        if let d = IOBluetoothDevice(addressString: address) {
            var r: IOReturn = kIOReturnError
            if Thread.isMainThread { r = d.closeConnection() } else { DispatchQueue.main.sync { r = d.closeConnection() } }
            for _ in 0 ..< 20 {
                if !(fastConnected() ?? true) { Log.write("disconnect: closed in app (status \(r))"); return }
                Thread.sleep(forTimeInterval: 0.15)
            }
            Log.write("disconnect: still connected after in-app close (status \(r)), using blueutil")
        }
        Log.write("blueutil disconnect: \(run(["--disconnect", address]) ?? "failed")")
    }

    struct Paired: Identifiable { let name: String; let address: String; var id: String { address } }

    /// Lists paired devices from macOS Bluetooth.
    static func paired() -> [Paired] {
        let list = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        return list.compactMap { d in
            guard let a = d.addressString else { return nil }
            return Paired(name: d.name ?? a, address: a.lowercased().replacingOccurrences(of: ":", with: "-"))
        }
    }

    /// Lists paired devices using `blueutil --paired --format json`.
    static func paired(path: String) -> [Paired] {
        guard FileManager.default.isExecutableFile(atPath: path) else { return [] }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = ["--paired", "--format", "json"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = Pipe()
        guard (try? p.run()) != nil else { return [] }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        guard let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap {
            guard let a = $0["address"] as? String else { return nil }
            return Paired(name: ($0["name"] as? String) ?? a, address: a)
        }
    }
}

/// Receives the result of an in-app connect, for the log. The link itself is watched by the caller.
final class ConnectWaiter: NSObject {
    static let shared = ConnectWaiter()
    @objc func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        Log.write("connect: macOS finished connecting (status \(status))")
    }
}
