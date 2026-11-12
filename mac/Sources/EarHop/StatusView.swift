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

/// One link on the Connection page: green outline when it works, grey when not.
struct LinkRow: View {
    let title: String
    let ok: Bool
    let detail: String

    var body: some View {
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

    static func recent(_ d: Date?, _ s: TimeInterval, _ now: Date) -> Bool { d.map { now.timeIntervalSince($0) < s } ?? false }

    static func ago(_ what: String, _ d: Date?, _ now: Date) -> String {
        guard let d, d > .distantPast else { return "\(what): never" }
        let s = Int(now.timeIntervalSince(d))
        return "\(what) " + (s < 2 ? "just now" : s < 120 ? "\(s) s ago" : "\(s / 60) min ago")
    }
}
