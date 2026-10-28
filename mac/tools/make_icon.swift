// Draws the EarHop app icon at every size macOS needs and writes an .iconset folder.
// Usage: swift tools/make_icon.swift <output.iconset>   then   iconutil -c icns <output.iconset>
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.iconset")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func draw(_ px: Int) -> Data? {
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                                     samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                     bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = CGFloat(px)
    // macOS icon grid: the shape fills about 80 percent of the canvas, with room for the shadow.
    let inset = s * 0.1
    let rect = NSRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    let shape = NSBezierPath(roundedRect: rect, xRadius: rect.width * 0.225, yRadius: rect.width * 0.225)
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.3)
    shadow.shadowBlurRadius = s * 0.02
    shadow.shadowOffset = NSSize(width: 0, height: -s * 0.01)
    NSGraphicsContext.saveGraphicsState()
    shadow.set()
    NSColor.black.setFill()
    shape.fill()
    NSGraphicsContext.restoreGraphicsState()
    // Blue (AirPods) to violet (Galaxy Buds), the two profile accents.
    NSGradient(colors: [NSColor(red: 0.30, green: 0.62, blue: 1.0, alpha: 1), NSColor(red: 0.58, green: 0.40, blue: 1.0, alpha: 1)])?
        .draw(in: shape, angle: -45)
    // A soft highlight along the top edge.
    NSGradient(colors: [NSColor.white.withAlphaComponent(0.28), NSColor.white.withAlphaComponent(0)])?
        .draw(in: NSBezierPath(roundedRect: rect.insetBy(dx: s * 0.01, dy: s * 0.01), xRadius: rect.width * 0.22, yRadius: rect.width * 0.22), angle: -90)
    // The earbuds, white, centred.
    let cfg = NSImage.SymbolConfiguration(pointSize: s * 0.38, weight: .medium)
    if let sym = NSImage(systemSymbolName: "earbuds", accessibilityDescription: nil)?.withSymbolConfiguration(cfg) {
        let tinted = NSImage(size: sym.size, flipped: false) { r in
            sym.draw(in: r)
            NSColor.white.set()
            r.fill(using: .sourceAtop)
            return true
        }
        let w = tinted.size.width, h = tinted.size.height
        tinted.draw(in: NSRect(x: (s - w) / 2, y: (s - h) / 2 + s * 0.03, width: w, height: h))
    }
    // A small arrow under the buds: they hop between devices.
    let arrowCfg = NSImage.SymbolConfiguration(pointSize: s * 0.11, weight: .bold)
    if let arrow = NSImage(systemSymbolName: "arrow.left.arrow.right", accessibilityDescription: nil)?.withSymbolConfiguration(arrowCfg) {
        let tinted = NSImage(size: arrow.size, flipped: false) { r in
            arrow.draw(in: r)
            NSColor.white.withAlphaComponent(0.85).set()
            r.fill(using: .sourceAtop)
            return true
        }
        let w = tinted.size.width, h = tinted.size.height
        tinted.draw(in: NSRect(x: (s - w) / 2, y: s * 0.2, width: w, height: h))
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])
}

for base in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let name = scale == 1 ? "icon_\(base)x\(base).png" : "icon_\(base)x\(base)@2x.png"
        if let d = draw(base * scale) { try? d.write(to: out.appendingPathComponent(name)) }
    }
}
print("wrote \(out.path)")
