import SwiftUI

/// What the Status page shows: whether each link really works right now, and when each device was last heard.
struct LinkStatus: Equatable {
    struct Device: Equatable, Identifiable {
        var name: String
        var lastHeard: Date?
        var via: String
        var bleAt: Date?
        var wifiHosts: [String]
        var clockOffsetMs: Int64?
        var clockRefusedAt: Date?
        var id: String { name }
    }
    var bluetoothOn = false
    var advertising = false
    var scanResultAt: Date?
    var bleHeardAt: Date?
    var clipService = false
    var wifiHeardAt: Date?
    var clipPort = false
    var transport = ""
    var devices: [Device] = []
}

/// Settings, Status: each link with a clear working or not working, every device with when and how it was last heard,
/// and Copy diagnostics, which collects the status and log of every device into one file.
struct StatusPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        let s = state.status
        let now = Date()
        Form {
            Section {
                row("Bluetooth", s.bluetoothOn, s.bluetoothOn ? "On" : "Off or not allowed")
                row("Bluetooth LE advertising", s.advertising, s.advertising ? "Other devices can hear this Mac" : "Not advertising")
                row("Bluetooth LE scan", recent(s.scanResultAt, 30, now), ago("Last result", s.scanResultAt, now))
                row("Heard over Bluetooth LE", recent(s.bleHeardAt, 15, now), ago("Last device", s.bleHeardAt, now))
                row("Bluetooth clipboard service", s.clipService, s.clipService ? "Ready for clips over Bluetooth" : "Not registered")
                row("Wi-Fi link", recent(s.wifiHeardAt, 15, now), ago("Last device", s.wifiHeardAt, now))
                row("Wi-Fi clipboard port", s.clipPort, s.clipPort ? "Listening" : "Not listening")
                LabeledContent("Switching link now", value: s.transport)
            } header: { Text("This Mac") }
            Section {
                if s.devices.isEmpty { Text("No other device heard yet").foregroundStyle(.secondary) }
                ForEach(s.devices) { d in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Circle().strokeBorder(recent(d.lastHeard, 15, now) ? Color.green : Color.secondary.opacity(0.5), lineWidth: 2)
                                .frame(width: 10, height: 10)
                            Text(DeviceName.pretty(d.name)).font(.headline)
                            Spacer()
                            Text(ago("Heard", d.lastHeard, now) + (d.via.isEmpty ? "" : " over \(d.via == "ble" ? "Bluetooth LE" : "Wi-Fi")"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Text(details(d, now)).font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            } header: { Text("Devices") }
            Section {
                Button("Copy diagnostics") { state.onDiagnostics?() }
                if !state.diagMessage.isEmpty { Text(state.diagMessage).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
            } header: { Text("Diagnostics") } footer: {
                Text("Asks every device for its status and the end of its log, and saves everything in one file in Downloads. Logs never contain clipboard contents.")
            }
        }
    }

    private func recent(_ d: Date?, _ s: TimeInterval, _ now: Date) -> Bool { d.map { now.timeIntervalSince($0) < s } ?? false }

    private func ago(_ what: String, _ d: Date?, _ now: Date) -> String {
        guard let d, d > .distantPast else { return "\(what): never" }
        let s = Int(now.timeIntervalSince(d))
        return "\(what) " + (s < 2 ? "just now" : s < 120 ? "\(s) s ago" : "\(s / 60) min ago")
    }

    private func details(_ d: LinkStatus.Device, _ now: Date) -> String {
        var p: [String] = []
        p.append(d.bleAt.map { "Bluetooth LE " + (now.timeIntervalSince($0) < 15 ? "yes" : "\(Int(now.timeIntervalSince($0)) / 60) min ago") } ?? "Bluetooth LE never")
        p.append(d.wifiHosts.isEmpty ? "no Wi-Fi address" : "Wi-Fi " + d.wifiHosts.joined(separator: ", "))
        if let o = d.clockOffsetMs { p.append(abs(o) < 2000 ? "clock in step" : "clock \(o > 0 ? "ahead" : "behind") by \(abs(o) / 1000) s") }
        if let r = d.clockRefusedAt, now.timeIntervalSince(r) < 300 { p.append("copies refused for the clock \(Int(now.timeIntervalSince(r))) s ago") }
        return p.joined(separator: " · ")
    }

    private func row(_ title: String, _ ok: Bool, _ detail: String) -> some View {
        LabeledContent {
            Text(detail).foregroundStyle(.secondary)
        } label: {
            HStack(spacing: 8) {
                // State shown on the outline: green when working, grey when not.
                Circle().strokeBorder(ok ? Color.green : Color.secondary.opacity(0.5), lineWidth: 2).frame(width: 10, height: 10)
                Text(title)
            }
        }
        .accessibilityLabel("\(title): \(ok ? "working" : "not working"), \(detail)")
    }
}
