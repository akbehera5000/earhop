import SwiftUI

/// The main card's picture, the same as on the phone: both earbuds and the case, drawn live. A bud in an ear floats
/// above the case and bobs gently, with sound waves while audio plays here; a bud in the case sits in its slot; a bud
/// that is out but not worn rests beside the case, dimmed. Each bud has a battery ring with its level under it, the case
/// shows its own level, and charging shows a bolt. Buds glide between places. With Reduce Motion they still move to the
/// right place, but without bobbing or waves.
struct BudsPicture: View {
    struct Bud: Equatable { var battery = -1; var place = "unknown"; var charging = false }
    struct Model: Equatable {
        var left = Bud(), right = Bud(), caseLevel = -1, caseCharging = false
        var playing = false, here = false, stem = false
    }

    let model: Model
    var tint: Color = .accentColor
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var motion = Motion()

    /// Eased positions (x, y, scale, opacity) of the left and right bud, carried from frame to frame.
    final class Motion: ObservableObject {
        var pos: [[CGFloat]] = [[.nan, 0, 1, 1], [.nan, 0, 1, 1]]
        var moving = true
        let start = Date()
    }

    private var alive: Bool {
        motion.moving || (!reduceMotion && [model.left, model.right].contains { $0.place == "wearing" })
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !alive)) { tl in
            Canvas { ctx, size in draw(&ctx, size, t: tl.date.timeIntervalSince(motion.start)) }
        }
        .frame(height: 156)
        .onChange(of: model) { _ in motion.moving = true }
        .accessibilityElement()
        .accessibilityLabel(describe())
    }

    private func draw(_ c: inout GraphicsContext, _ size: CGSize, t: Double) {
        let m = model
        let w = size.width, h = size.height, cx = w / 2, r: CGFloat = 21
        let fg = Color.primary
        let low = Color.red

        // Case, with the lid tipped open while a bud is out.
        let caseW = min(w * 0.36, 128), caseH: CGFloat = 56
        let caseTop = h - caseH - 24
        let anyOut = [m.left, m.right].contains { $0.place == "wearing" || $0.place == "idle" }
        let body = Path(roundedRect: CGRect(x: cx - caseW / 2, y: caseTop, width: caseW, height: caseH), cornerRadius: 22)
        c.fill(body, with: .color(fg.opacity(0.08)))
        c.stroke(body, with: .color(fg.opacity(0.35)), lineWidth: 1.3)
        var lidCtx = c
        let lidY = caseTop + 3
        if anyOut {
            lidCtx.translateBy(x: cx - caseW / 2 + 9, y: lidY)
            lidCtx.rotate(by: .degrees(-14))
            lidCtx.translateBy(x: -(cx - caseW / 2 + 9), y: -lidY)
        }
        let lid = Path(roundedRect: CGRect(x: cx - caseW / 2, y: lidY - 14, width: caseW, height: 19), cornerRadius: 12)
        lidCtx.fill(lid, with: .color(fg.opacity(0.06)))
        lidCtx.stroke(lid, with: .color(fg.opacity(0.28)), lineWidth: 1.3)
        let caseText = "Case " + (m.caseLevel >= 0 ? "\(m.caseLevel)%" : "\u{2013}")
        c.draw(Text(caseText).font(.system(size: 11.5, weight: .medium)).foregroundColor(.secondary), at: CGPoint(x: cx, y: h - 9))
        if m.caseCharging { bolt(&c, CGPoint(x: cx + 36, y: h - 9), fg) }

        var moving = false
        for side in 0 ... 1 {
            let bud = side == 0 ? m.left : m.right
            let sign: CGFloat = side == 0 ? -1 : 1
            let bob = reduceMotion ? 0 : CGFloat(sin(t * 2 + Double(side) * 1.4)) * 3.5
            let target: [CGFloat]
            switch bud.place {
            case "wearing": target = [cx + sign * w * 0.29, 44 + bob, 1, 1]
            case "case": target = [cx + sign * caseW * 0.22, caseTop + caseH * 0.42, 0.62, 1]
            case "idle": target = [cx + sign * w * 0.33, caseTop + caseH * 0.2, 0.85, 0.55]
            default: target = [cx + sign * w * 0.29, 48, 0.95, m.here ? 0.75 : 0.45]
            }
            var p = motion.pos[side]
            if p[0].isNaN { p = target }
            let k: CGFloat = reduceMotion ? 0.3 : 0.14
            for i in 0 ..< 4 { p[i] += (target[i] - p[i]) * k }
            if abs(target[0] - p[0]) > 0.4 || abs(target[1] - p[1]) > 0.4 { moving = true }
            motion.pos[side] = p
            let x = p[0], y = p[1], s = p[2], a = p[3]

            // Sound waves beside a worn bud while audio plays here.
            if bud.place == "wearing", m.playing, m.here, !reduceMotion {
                for i in 0 ..< 3 {
                    let ph = (t * 0.9 + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                    let rr = r + 12 + CGFloat(ph) * 22
                    var arc = Path()
                    let mid: Double = side == 0 ? 180 : 0
                    arc.addArc(center: CGPoint(x: x, y: y), radius: rr, startAngle: .degrees(mid - 30), endAngle: .degrees(mid + 30), clockwise: false)
                    c.stroke(arc, with: .color(tint.opacity(0.75 * (1 - ph))), style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
                }
            }

            // Battery ring.
            let rr = (r + 7) * s
            let ring = Path(ellipseIn: CGRect(x: x - rr, y: y - rr, width: rr * 2, height: rr * 2))
            c.stroke(ring, with: .color(fg.opacity(0.14 * a)), lineWidth: 2.6 * s)
            if bud.battery >= 0 {
                var arc = Path()
                arc.addArc(center: CGPoint(x: x, y: y), radius: rr, startAngle: .degrees(-90),
                           endAngle: .degrees(-90 + Double(bud.battery) * 3.6), clockwise: false)
                c.stroke(arc, with: .color((bud.battery <= 20 ? low : tint).opacity(a)), style: StrokeStyle(lineWidth: 2.6 * s, lineCap: .round))
            }

            // The bud itself, mirrored for the right side: a bean with a nub (Galaxy Buds) or a head and stem (AirPods).
            var b = c
            b.translateBy(x: x, y: y)
            b.scaleBy(x: s * -sign, y: s)
            b.opacity = a
            var shape = Path()
            if m.stem {
                shape.addRoundedRect(in: CGRect(x: -r * 0.30, y: r * 0.10, width: r * 0.42, height: r * 1.15), cornerSize: CGSize(width: r * 0.2, height: r * 0.2))
                shape.addEllipse(in: CGRect(x: -r * 0.62, y: -r * 0.77, width: r * 1.24, height: r * 1.24))
            } else {
                shape.addEllipse(in: CGRect(x: -r * 0.78, y: -r * 0.70, width: r * 1.48, height: r * 1.42))
                shape.addEllipse(in: CGRect(x: r * 0.25, y: -r * 0.40, width: r * 0.70, height: r * 0.70))
            }
            // Drawn as one layer so a dimmed bud fades as a whole. Outline first, then the fill on top, so only the
            // outer edge shows (no seam where the parts overlap).
            let grille = m.stem ? CGPoint(x: r * 0.18, y: -r * 0.2) : CGPoint(x: r * 0.45, y: -r * 0.05)
            b.drawLayer { l in
                l.stroke(shape, with: .color(.black.opacity(0.3)), lineWidth: 1.6)
                l.fill(shape, with: .color(.white))
                l.fill(Path(ellipseIn: CGRect(x: grille.x - r * 0.2, y: grille.y - r * 0.2, width: r * 0.4, height: r * 0.4)), with: .color(.black.opacity(0.28)))
                l.fill(Path(ellipseIn: CGRect(x: -r * 0.54, y: -r * 0.31, width: r * 0.72, height: r * 0.72)), with: .color(.black.opacity(0.07)))
            }

            // Level under the ring (buds in the case show theirs beside it, below).
            if bud.place != "case" {
                let label = (side == 0 ? "L " : "R ") + (bud.battery >= 0 ? "\(bud.battery)%" : "\u{2013}")
                c.draw(Text(label).font(.system(size: 11.5, weight: .medium)).foregroundColor(fg.opacity(max(a, 0.7))),
                       at: CGPoint(x: x, y: y + rr + 11))
                if bud.charging { bolt(&c, CGPoint(x: x + 26, y: y + rr + 11), fg) }
            }
        }
        // Levels of buds in the case go beside it, level with the bud, clear of the lid.
        for (side, bud) in [(0, m.left), (1, m.right)] where bud.place == "case" {
            let sign: CGFloat = side == 0 ? -1 : 1
            let label = (side == 0 ? "L " : "R ") + (bud.battery >= 0 ? "\(bud.battery)%" : "\u{2013}")
            let x = cx + sign * (caseW / 2 + 30), y = caseTop + caseH * 0.42
            c.draw(Text(label).font(.system(size: 11.5, weight: .medium)).foregroundColor(fg), at: CGPoint(x: x, y: y))
            if bud.charging { bolt(&c, CGPoint(x: x + sign * 26, y: y), fg) }
        }
        if moving != motion.moving { DispatchQueue.main.async { motion.moving = moving } }
    }

    private func bolt(_ c: inout GraphicsContext, _ at: CGPoint, _ color: Color) {
        var p = Path()
        let x = at.x, y = at.y
        p.move(to: CGPoint(x: x + 1, y: y - 5.5)); p.addLine(to: CGPoint(x: x - 3, y: y + 1)); p.addLine(to: CGPoint(x: x, y: y + 1))
        p.addLine(to: CGPoint(x: x - 1, y: y + 5.5)); p.addLine(to: CGPoint(x: x + 3, y: y - 1)); p.addLine(to: CGPoint(x: x, y: y - 1))
        p.closeSubpath()
        c.fill(p, with: .color(color))
    }

    private func describe() -> String {
        func one(_ n: String, _ b: Bud) -> String {
            let where_ = ["wearing": " in ear", "case": " in case", "idle": " out"][b.place] ?? ""
            return n + where_ + (b.battery >= 0 ? " \(b.battery)%" : "") + (b.charging ? " charging" : "")
        }
        return [one("Left", model.left), one("Right", model.right), "Case" + (model.caseLevel >= 0 ? " \(model.caseLevel)%" : "")].joined(separator: ", ")
    }
}
