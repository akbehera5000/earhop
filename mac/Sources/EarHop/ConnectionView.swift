import SwiftUI

/// How EarHop talks to the other devices: Bluetooth LE, both (default) or Wi-Fi. Used in the popover and in Settings.
enum LinkMode: String, CaseIterable, Identifiable {
    case ble, both, wifi
    var id: String { rawValue }
    var title: String {
        switch self { case .ble: return "Bluetooth"; case .both: return "Both"; case .wifi: return "Wi-Fi" }
    }
    var symbol: String {
        switch self {
        case .ble: return "dot.radiowaves.left.and.right"
        case .both: return "point.3.connected.trianglepath.dotted"
        case .wifi: return "wifi"
        }
    }
    var explanation: String {
        switch self {
        case .both: return "Every status goes out over Bluetooth LE and Wi-Fi at once and the newest copy wins. If one link fails, nothing is lost. Recommended."
        case .ble: return "Bluetooth LE only. Works without any Wi-Fi network, within Bluetooth range (about one room). Good for testing Bluetooth on its own."
        case .wifi: return "Wi-Fi only. The devices must be on the same network (a router or the phone's hotspot). Works across rooms."
        }
    }
}

/// The three way switch plus a live dot for each link.
struct ConnectionControl: View {
    @ObservedObject var state: AppState
    var showExplanation = false

    private var mode: LinkMode { LinkMode(rawValue: state.cfg.transport) ?? .both }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Connection", selection: $state.cfg.transport) {
                ForEach(LinkMode.allCases) { m in
                    Label(m.title, systemImage: m.symbol).tag(m.rawValue)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("How EarHop talks to your other devices")
            HStack(spacing: 12) {
                pill("Bluetooth LE", on: mode != .wifi, live: state.bleLive)
                pill("Wi-Fi", on: mode != .ble, live: state.wifiLive)
                Spacer(minLength: 0)
            }
            if showExplanation {
                Text(mode.explanation).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Green: news from another device arrived over this link in the last 10 s. Orange: on, but nobody heard. Grey: off.
    private func pill(_ name: String, on: Bool, live: Bool) -> some View {
        let color: Color = !on ? .secondary.opacity(0.5) : (live ? .green : .orange)
        let word = !on ? "off" : (live ? "working" : "nobody heard")
        return HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text("\(name): \(word)").font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name) \(word)")
    }
}
