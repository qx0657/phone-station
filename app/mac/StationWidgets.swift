import AppKit
import QuartzCore
import SwiftUI

// Resolve the property wrapper explicitly: Command Line Tools do not include
// the newer SwiftUI.State macro plugin with the same name.
typealias StationViewState<Value> = SwiftUI.State<Value>

enum StationChrome {
    static let cardRadius: CGFloat = 12
}

enum StationPalette {
    static let background = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 37 / 255, green: 37 / 255, blue: 39 / 255, alpha: 1),
        light: NSColor(srgbRed: 0.95, green: 0.95, blue: 0.96, alpha: 1)))
    static let tile = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 50 / 255, green: 50 / 255, blue: 52 / 255, alpha: 1),
        light: NSColor.white))
    static let connected = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 0.463, green: 0.694, blue: 0.537, alpha: 1),
        light: NSColor(srgbRed: 33 / 255, green: 107 / 255, blue: 64 / 255, alpha: 1)))
    static let caution = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 0.93, green: 0.66, blue: 0.36, alpha: 1),
        light: NSColor(srgbRed: 0.70, green: 0.42, blue: 0.12, alpha: 1)))
    static let recording = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 0.93, green: 0.45, blue: 0.42, alpha: 1),
        light: NSColor(srgbRed: 0.74, green: 0.22, blue: 0.20, alpha: 1)))

    private static func dynamic(dark: NSColor, light: NSColor) -> NSColor {
        NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        }
    }
}

enum StationRows {
    static func connectionRow(_ title: String, symbol: String, status: String, online: Bool, checking: Bool = false) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).frame(width: 18).foregroundStyle(.secondary).accessibilityHidden(true)
            Text(title).font(.subheadline)
            Spacer(minLength: 8)
            if checking { ProgressView().controlSize(.mini).accessibilityHidden(true) }
            Text(status).font(.system(size: 12, weight: online ? .medium : .regular))
                .foregroundStyle(online ? StationPalette.connected : checking ? StationPalette.caution : Color.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }.padding(.horizontal, 12).padding(.vertical, 11)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
    }

    static var groupDivider: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.08))
            .frame(height: 1)
    }

    static func sectionTitle(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 2)
    }

    static func subpageHeader(_ title: String, back: @escaping () -> Void) -> some View {
        Button(action: back) {
            HStack(spacing: 6) {
                Image(systemName: "chevron.left")
                    .font(.caption.weight(.semibold))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .accessibilityLabel("返回")
        .accessibilityHint("返回上一页，当前页面：\(title)")
    }

    static func navigationRow(_ title: String, symbol: String, detail: String? = nil,
                              detailLineLimit: Int? = nil, needsAttention: Bool = false,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .frame(width: 18)
                    .foregroundStyle(needsAttention ? StationPalette.caution : Color.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                    if let detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(needsAttention ? StationPalette.caution : Color.secondary)
                            .lineLimit(detailLineLimit)
                            .fixedSize(horizontal: false, vertical: true)
                            .help(detail)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, detail == nil ? 9 : 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .background { HoverWash(radius: 0) }
        .font(.body)
    }

    static func toggleRow(_ title: String, symbol: String, subtitle: String? = nil,
                          isOn: Binding<Bool>, enabled: Bool) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.body)
                    .frame(width: 18)
                    .foregroundStyle(isOn.wrappedValue ? Color.primary : Color.secondary)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.body)
                Spacer(minLength: 8)
                Toggle(title, isOn: isOn)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
                    .fixedSize()
                    .disabled(!enabled)
            }
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 28)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .opacity(enabled ? 1 : 0.4)
    }

    static func feedbackBanner(_ text: String, busy: Bool) -> some View {
        HStack(alignment: .center, spacing: 8) {
            if busy {
                ProgressView().controlSize(.small)
            }
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityAddTraits(.updatesFrequently)
    }

    static func appVersion() -> String {
        let short = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if short.isEmpty { return "未知" }
        if build.isEmpty || build == short { return short }
        return "\(short) (\(build))"
    }
}

/// Keep operation feedback beside its controls; completed notices can be dismissed.
struct StationOperationFeedback: View {
    @ObservedObject var station: Station
    var body: some View {
        if let text = station.feedbackText {
            HStack(alignment: .top, spacing: 8) {
                StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                if station.feedback.activity == nil, station.feedback.notice != nil {
                    Button { station.feedback.notice = nil } label: {
                        Image(systemName: "xmark").font(.system(size: 10, weight: .medium))
                            .frame(width: 22, height: 22).contentShape(Rectangle())
                    }.buttonStyle(.borderless).help("收起操作反馈").accessibilityLabel("收起操作反馈")
                }
            }
        }
    }
}

private struct StationContentHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Hug short pages, while keeping expanded help and long states within the screen.
struct StationPageScroll<Content: View>: View {
    var maxHeight: CGFloat = 560
    @ViewBuilder var content: () -> Content
    @StationViewState private var contentHeight: CGFloat = 300

    var body: some View {
        ScrollView {
            content().frame(maxWidth: .infinity, alignment: .leading)
                .background(GeometryReader { geometry in
                    Color.clear.preference(key: StationContentHeight.self, value: geometry.size.height)
                })
        }
        .frame(height: min(contentHeight, maxHeight, max(200, (NSScreen.main?.visibleFrame.height ?? 900) - 96)))
        .onPreferenceChange(StationContentHeight.self) { height in
            if height > 0 { contentHeight = height }
        }
    }
}

extension View {
    /// Content is flush with the card. Row washes are rectangular; this clip
    /// rounds the outer corners so the highlight fills the row, including the
    /// corners, instead of leaving the original tile color around a smaller slab.
    func elevatedGroup() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: StationChrome.cardRadius, style: .continuous)
                    .fill(StationPalette.tile)
            }
            .clipShape(RoundedRectangle(cornerRadius: StationChrome.cardRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: StationChrome.cardRadius, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
            }
    }
}

/// Icon-above-title tile. Drawn here because the system bordered button clips the caption.
struct ActionTile: View {
    let title: String
    let symbol: String
    let active: Bool
    let tint: Color
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .medium))
                    .symbolRenderingMode(active ? .monochrome : .hierarchical)
                    .foregroundStyle(active ? tint : Color.primary)
                Text(title)
                    .font(.system(size: 12))
                    .foregroundStyle(active ? tint : Color.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .padding(.horizontal, 4)
            .background {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(active ? tint.opacity(0.16) : StationPalette.tile)
            }
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(active ? tint.opacity(0.45) : Color.primary.opacity(0.08), lineWidth: 1)
            }
            .overlay { HoverWash(radius: 10, enabled: enabled) }
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(PressFadeStyle())
        .disabled(!enabled)
        .opacity(enabled || active ? 1 : 0.4)
        .animation(.easeOut(duration: 0.16), value: active)
        .accessibilityLabel(title)
    }
}

struct PressFadeStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.62 : 1)
    }
}

/// Hover wash without `@State`. Command Line Tools ship SwiftUI property wrappers, not the State macro plugin.
struct HoverWash: NSViewRepresentable {
    var radius: CGFloat = 8
    var enabled: Bool = true
    var onInside: ((Bool) -> Void)?
    var onFrame: ((NSRect, NSWindow) -> Void)?

    func makeNSView(context: Context) -> HoverWashView {
        let view = HoverWashView()
        view.radius = radius
        view.tracksHover = enabled
        return view
    }

    func updateNSView(_ nsView: HoverWashView, context: Context) {
        nsView.radius = radius
        nsView.tracksHover = enabled
        nsView.onInside = onInside
        nsView.onFrame = onFrame
        if !enabled { nsView.clearWash() }
        if onFrame != nil { nsView.reportFrameIfNeeded(deferred: true) }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: HoverWashView, context: Context) -> CGSize? {
        guard let width = proposal.width, let height = proposal.height else { return nil }
        return CGSize(width: width, height: height)
    }
}

final class HoverWashView: NSView {
    var radius: CGFloat = 8 {
        didSet { applyCorner() }
    }
    var tracksHover = true
    var onInside: ((Bool) -> Void)?
    var onFrame: ((NSRect, NSWindow) -> Void)?
    private var tracking: NSTrackingArea?
    private var lastReported: NSRect?

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        wantsLayer = true
        applyCorner()
    }

    private func applyCorner() {
        // Grouped rows pass radius 0. The card's clip rounds their outer corners;
        // a radius here would also round the edge that meets the next row.
        layer?.cornerRadius = radius
        layer?.cornerCurve = .continuous
        layer?.masksToBounds = radius > 0
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let tracking { removeTrackingArea(tracking) }
        let area = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect],
            owner: self,
            userInfo: nil)
        addTrackingArea(area)
        tracking = area
    }

    override func mouseEntered(with event: NSEvent) {
        guard tracksHover else { return }
        fade(to: NSColor.labelColor.withAlphaComponent(0.06).cgColor)
        onInside?(true)
        reportFrameIfNeeded(deferred: false)
    }

    override func mouseExited(with event: NSEvent) {
        lastReported = nil
        clearWash()
        onInside?(false)
    }

    func reportFrameIfNeeded(deferred: Bool) {
        guard let window, let onFrame else { return }
        let rect = window.convertToScreen(convert(bounds, to: nil))
        guard rect.width > 1, rect.height > 1 else { return }
        if let lastReported, lastReported.equalTo(rect) { return }
        lastReported = rect
        if deferred {
            DispatchQueue.main.async { onFrame(rect, window) }
        } else {
            onFrame(rect, window)
        }
    }

    func clearWash() {
        fade(to: NSColor.clear.cgColor)
    }

    private func fade(to color: CGColor?) {
        let fade = CABasicAnimation(keyPath: "backgroundColor")
        fade.fromValue = layer?.backgroundColor
        fade.toValue = color
        fade.duration = 0.12
        fade.timingFunction = CAMediaTimingFunction(name: .easeOut)
        layer?.add(fade, forKey: "wash")
        layer?.backgroundColor = color
    }
}

/// Shared install and receipt actions, with the same authorization checks at every entry.
struct PhoneAppInstallControls: View {
    @ObservedObject var station: Station
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { station.setup.install() } label: {
                Label("安装或更新手机应用", systemImage: "arrow.down.app")
                    .font(.body).padding(10).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(PressFadeStyle())
                .disabled(station.feedback.activity != nil || !station.featureAllows("shell") || !station.featureAllows("files.read") || !station.featureAllows("files.write") || (station.link.serial == nil && station.remoteBlocker([.install]) != nil))
            if station.link.serial == nil, let blocker = station.remoteBlocker([.install]) {
                RemotePermissionHint(station: station, blocker: blocker).padding(12)
            }
            if let id = station.setup.installJobID {
                VStack(alignment: .leading, spacing: 6) {
                    Text("上次安装任务").font(.caption).foregroundStyle(.secondary)
                    Text(id).font(.caption.monospaced()).textSelection(.enabled)
                    HStack {
                        Button("查询原任务") { station.setup.queryInstall() }.disabled(station.feedback.activity != nil)
                        Button("复制编号") { station.setup.copyInstallJobID() }
                    }.buttonStyle(.borderless)
                }.padding(10)
            }
        }
    }
}
