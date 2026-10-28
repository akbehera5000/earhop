// Draws the Android launcher icon as PNG files, the same design as res/drawable/ic_launcher_fg.xml and ic_launcher_bg.xml.
// Some phones show a generic robot for apps whose icon is only vector drawables, in notifications too,
// so the app also ships bitmap layers and legacy bitmap icons (on the phone the robot was a cached icon that a
// restart cleared).
// Run: swift tools/make_android_icon.swift android/app/src/main/res
import AppKit

let res = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "android/app/src/main/res"
let densities: [(String, CGFloat)] = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]

/// Draws in the 108 x 108 adaptive canvas, y down, like the vector.
func drawForeground(_ c: CGContext) {
    c.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    func bud(cx: CGFloat, stemX: CGFloat, deg: CGFloat, pivot: CGPoint) {
        c.saveGState()
        c.translateBy(x: pivot.x, y: pivot.y)
        c.rotate(by: deg * .pi / 180)
        c.translateBy(x: -pivot.x, y: -pivot.y)
        c.addEllipse(in: CGRect(x: cx - 11, y: 29, width: 22, height: 22))
        let stem = CGMutablePath()
        stem.move(to: CGPoint(x: stemX, y: 44))
        stem.addLine(to: CGPoint(x: stemX + 9, y: 44))
        stem.addLine(to: CGPoint(x: stemX + 9, y: 64))
        stem.addArc(center: CGPoint(x: stemX + 4.5, y: 64), radius: 4.5, startAngle: 0, endAngle: .pi, clockwise: false)
        stem.closeSubpath()
        c.addPath(stem)
        c.fillPath()
        c.restoreGState()
    }
    bud(cx: 42, stemX: 37.5, deg: -14, pivot: CGPoint(x: 42, y: 48))
    bud(cx: 66, stemX: 61.5, deg: 14, pivot: CGPoint(x: 66, y: 48))
    c.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.9))
    c.setLineWidth(3); c.setLineCap(.round); c.setLineJoin(.round)
    let lines: [[CGPoint]] = [
        [CGPoint(x: 43, y: 74), CGPoint(x: 65, y: 74)],
        [CGPoint(x: 61, y: 70.5), CGPoint(x: 65, y: 74), CGPoint(x: 61, y: 77.5)],
        [CGPoint(x: 65, y: 82), CGPoint(x: 43, y: 82)],
        [CGPoint(x: 47, y: 78.5), CGPoint(x: 43, y: 82), CGPoint(x: 47, y: 85.5)],
    ]
    for l in lines { c.addLines(between: l); c.strokePath() }
}

func drawBackground(_ c: CGContext, _ rect: CGRect) {
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [
        CGColor(red: 0x4D / 255, green: 0x9E / 255, blue: 1, alpha: 1),
        CGColor(red: 0x94 / 255, green: 0x66 / 255, blue: 1, alpha: 1)] as CFArray, locations: [0, 1])!
    c.drawLinearGradient(g, start: CGPoint(x: rect.minX, y: rect.minY), end: CGPoint(x: rect.maxX, y: rect.maxY), options: [])
}

/// side: pixels. crop: the part of the 108 canvas shown (108 for adaptive layers, 72 centred for legacy icons).
func png(_ path: String, side: Int, crop: CGFloat, background: Bool, foreground: Bool, mask: ((CGContext, CGRect) -> Void)? = nil) {
    let cs = CGColorSpaceCreateDeviceRGB()
    let c = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.setShouldAntialias(true)
    c.interpolationQuality = .high
    let full = CGRect(x: 0, y: 0, width: side, height: side)
    // y down, then map the visible part of the 108 canvas onto the image.
    c.translateBy(x: 0, y: CGFloat(side)); c.scaleBy(x: 1, y: -1)
    if let mask { mask(c, full); c.clip() }
    let s = CGFloat(side) / crop
    c.scaleBy(x: s, y: s)
    c.translateBy(x: -(108 - crop) / 2, y: -(108 - crop) / 2)
    if background { drawBackground(c, CGRect(x: 0, y: 0, width: 108, height: 108)) }
    if foreground { drawForeground(c) }
    let rep = NSBitmapImageRep(cgImage: c.makeImage()!)
    try! FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

for (name, k) in densities {
    let dir = "\(res)/mipmap-\(name)"
    png("\(dir)/ic_launcher_foreground.png", side: Int(108 * k), crop: 108, background: false, foreground: true)
    png("\(dir)/ic_launcher.png", side: Int(48 * k), crop: 72, background: true, foreground: true) { c, r in
        c.addPath(CGPath(roundedRect: r.insetBy(dx: r.width * 0.04, dy: r.height * 0.04), cornerWidth: r.width * 0.22, cornerHeight: r.height * 0.22, transform: nil))
    }
    png("\(dir)/ic_launcher_round.png", side: Int(48 * k), crop: 72, background: true, foreground: true) { c, r in
        c.addEllipse(in: r.insetBy(dx: r.width * 0.04, dy: r.height * 0.04))
    }
}
print("Android icons written to \(res)")
