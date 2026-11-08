import AppKit
import SwiftUI

/// The "Send to" panel for files from the Share menu, laid out like the Android share sheet: round device buttons in a
/// row with their names, and a Cancel pill. Green outlines mark devices that can be reached now (all listed ones are).
enum SharePicker {
    private static var panel: NSPanel?

    /// Shows the panel near the pointer. `pick` gets nil for "All devices" or a device name; nothing on Cancel.
    static func show(title: String, devices: [String], pick: @escaping (String?) -> Void) {
        close()
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 220),
                        styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
                        backing: .buffered, defer: false)
        p.titlebarAppearsTransparent = true
        p.titleVisibility = .hidden
        p.standardWindowButton(.closeButton)?.isHidden = true
        p.standardWindowButton(.miniaturizeButton)?.isHidden = true
        p.standardWindowButton(.zoomButton)?.isHidden = true
        p.isMovableByWindowBackground = true
        p.level = .floating
        p.hidesOnDeactivate = false
        let choices: [String?] = (devices.count > 1 ? [nil] : []) + devices.map { Optional($0) }
        let view = SharePickerView(title: title, choices: choices,
                                   pick: { c in close(); pick(c) },
                                   cancel: { Log.write("share: cancelled"); close() })
        let host = NSHostingView(rootView: view)
        p.contentView = host
        p.setContentSize(host.fittingSize)
        let m = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(m, $0.frame, false) } ?? NSScreen.main
        if let f = screen?.visibleFrame {
            let s = p.frame.size
            let x = min(max(m.x - s.width / 2, f.minX + 8), f.maxX - s.width - 8)
            let y = min(max(m.y - s.height / 2, f.minY + 8), f.maxY - s.height - 8)
            p.setFrameOrigin(NSPoint(x: x, y: y))
        }
        panel = p
        NSApp.activate(ignoringOtherApps: true)
        p.makeKeyAndOrderFront(nil)
    }

    static func close() {
        panel?.orderOut(nil)
        panel = nil
    }
}

private struct SharePickerView: View {
    let title: String
    let choices: [String?]
    let pick: (String?) -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Send to").font(.system(size: 20, weight: .bold))
                Text("\(title), over Wi-Fi").font(.system(size: 12)).foregroundColor(.secondary).lineLimit(1).truncationMode(.middle)
            }
            HStack(alignment: .top, spacing: 18) {
                ForEach(choices.indices, id: \.self) { i in
                    DeviceChoice(dev: choices[i]) { pick(choices[i]) }
                }
            }
            Button(action: cancel) {
                Text("Cancel").font(.system(size: 13, weight: .medium)).frame(maxWidth: .infinity).padding(.vertical, 8)
                    .background(Capsule().fill(Color.primary.opacity(0.08)))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .keyboardShortcut(.cancelAction)
        }
        .padding(.horizontal, 22).padding(.top, 26).padding(.bottom, 18)
        .frame(minWidth: 300)
    }
}

private struct DeviceChoice: View {
    let dev: String?
    let action: () -> Void
    private static let green = Color(red: 0.19, green: 0.82, blue: 0.35)

    var body: some View {
        Button(action: action) {
            VStack(spacing: 7) {
                ZStack {
                    Circle().fill(Color.primary.opacity(0.07))
                    Circle().stroke(Self.green, lineWidth: 2.5)
                    Image(systemName: dev.map(DeviceName.symbol) ?? "rectangle.on.rectangle")
                        .font(.system(size: 22, weight: .medium))
                }
                .frame(width: 62, height: 62)
                Text(dev.map(DeviceName.pretty) ?? "All devices").font(.system(size: 12)).lineLimit(1)
            }
            .frame(width: 76)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
