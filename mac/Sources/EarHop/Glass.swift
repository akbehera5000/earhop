import SwiftUI

/// Liquid Glass on macOS 26 and later, regular material before that.
extension View {
    @ViewBuilder
    func glassCard(cornerRadius: CGFloat = 18) -> some View {
        if #available(macOS 26.0, *) {
            self.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            self.background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }

    @ViewBuilder
    func glassCapsule(tint: Color? = nil) -> some View {
        if #available(macOS 26.0, *) {
            self.glassEffect(tint.map { .regular.tint($0.opacity(0.35)) } ?? .regular, in: .capsule)
        } else {
            self.background(tint?.opacity(0.25) ?? Color.clear, in: Capsule())
                .background(.regularMaterial, in: Capsule())
        }
    }
}

struct GlassButtonStyle: ViewModifier {
    var prominent = false
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            if prominent { content.buttonStyle(.glassProminent) } else { content.buttonStyle(.glass) }
        } else {
            if prominent { content.buttonStyle(.borderedProminent) } else { content.buttonStyle(.bordered) }
        }
    }
}

extension View {
    func glassButton(prominent: Bool = false) -> some View { modifier(GlassButtonStyle(prominent: prominent)) }
}

/// Wraps glass children so they blend, on macOS 26 and later.
struct GlassGroup<Content: View>: View {
    let content: Content
    init(@ViewBuilder _ content: () -> Content) { self.content = content() }
    var body: some View {
        if #available(macOS 26.0, *) {
            GlassEffectContainer(spacing: 12) { content }
        } else {
            content
        }
    }
}
