import AppKit
import QuartzCore
import SwiftUI

struct StationView: View {
    @ObservedObject var model: StationModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch model.page {
            case .main:
                mainPage
            case .connection:
                connectionPage
            case .more:
                morePage
            case .about:
                aboutPage
            }
        }
        .frame(width: 360, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(StationPalette.background)
        .task { model.refresh() }
    }

    private var mainPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            VStack(alignment: .leading, spacing: 12) {
                actionRow
                controlGroup
                if let text = model.feedbackText {
                    feedbackBanner(text)
                }
                if model.beatNeedsAudioPermission {
                    Button("打开「系统录音」") { model.openAudioCaptureSettings() }
                        .buttonStyle(.bordered)
                }
                VStack(spacing: 0) {
                    navigationRow("连接与配对", symbol: "wifi") { model.page = .connection }
                    groupDivider
                    navigationRow("更多工具与设置", symbol: "slider.horizontal.3") {
                        model.page = .more
                    }
                }
                .elevatedGroup()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
        }
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            actionTile(model.isMirroring ? "关闭投屏" : "打开投屏",
                       symbol: "display",
                       active: model.isMirroring,
                       tint: StationPalette.connected,
                       enabled: model.isMirroring || model.canStartScreen) {
                model.isMirroring ? model.stopMirror() : model.startMirror()
            }
            actionTile("截取画面", symbol: "camera", enabled: model.canOperate) {
                model.screenshot()
            }
            actionTile(model.isRecording ? (model.isStoppingRecording ? "保存中…" : "停止录屏") : "开始录屏",
                       symbol: model.isRecording ? "stop.circle.fill" : "video",
                       active: model.isRecording,
                       tint: StationPalette.recording,
                       enabled: (model.isRecording && !model.isStoppingRecording) || model.canStartScreen) {
                model.isRecording ? model.stopRecording() : model.startRecording()
            }
        }
    }

    private var controlGroup: some View {
        VStack(spacing: 0) {
            toggleRow("保持亮屏", symbol: "sun.max", isOn: stayAwakeBinding, enabled: model.canOperate)
            groupDivider.padding(.leading, 28)
            toggleRow("手电筒", symbol: model.torchOn ? "flashlight.on.fill" : "flashlight.off.fill",
                      isOn: torchBinding, enabled: model.canOperate || model.torchBeat)
            groupDivider.padding(.leading, 28)
            toggleRow("灯光跟随声音", symbol: "waveform",
                      subtitle: "跟随这台 Mac 正在播放的声音",
                      isOn: beatBinding, enabled: model.canOperate || model.torchBeat)
        }
        .elevatedGroup()
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.headline)
                    .font(.system(size: 16, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if !model.connectionLine.isEmpty {
                    Text(model.connectionLine)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            statusChip
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .accessibilityElement(children: .combine)
    }

    private var statusChip: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(statusTint)
                .frame(width: 6, height: 6)
                .accessibilityHidden(true)
            Text(model.statusLabel)
                .font(.system(size: 11, weight: .medium))
        }
        .foregroundStyle(statusTint)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(statusTint.opacity(0.14), in: Capsule())
        .fixedSize()
    }

    private var statusTint: Color {
        switch model.statusTone {
        case .ready: return StationPalette.connected
        case .caution: return StationPalette.caution
        case .neutral: return Color.secondary
        }
    }

    private var connectionPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader("连接与配对")
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.headline)
                        .font(.system(size: 15, weight: .semibold))
                    if !model.connectionLine.isEmpty {
                        Text(model.connectionLine)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Text("无线调试配对过一次并且开着，或 USB 已经接上，就可以连接。第一次配对仍按项目说明操作。手机页面上的 172.19.0.1 是 VPN 地址，配对要用局域网地址。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if model.serial == nil {
                    Button {
                        model.connect()
                    } label: {
                        Label("连接手机", systemImage: "wifi")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.activity != nil || model.isChecking)
                } else if model.transport == "无线连接" {
                    Button("断开无线连接") { model.disconnect() }
                        .buttonStyle(.bordered)
                        .disabled(model.activity != nil)
                } else {
                    Text("USB 保持连接。这里只断开无线。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button("打开项目说明") { model.openGuide() }
                    .buttonStyle(.bordered)
                if let text = model.feedbackText {
                    feedbackBanner(text)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var morePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader("更多工具与设置")
            VStack(alignment: .leading, spacing: 16) {
                VStack(spacing: 0) {
                    toggleRow("开机自启", symbol: "desktopcomputer",
                              subtitle: model.loginItemSubtitle,
                              isOn: loginBinding, enabled: true)
                    if model.loginItemNeedsApproval {
                        Button("打开系统设置") { model.openLoginItemsSettings() }
                            .buttonStyle(.borderless)
                            .font(.subheadline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.leading, 28)
                            .padding(.bottom, 8)
                    }
                    groupDivider.padding(.leading, 28)
                    navigationRow("关于", symbol: "info.circle", detail: "版本 \(appVersion)") {
                        model.page = .about
                    }
                }
                .elevatedGroup()

                VStack(alignment: .leading, spacing: 6) {
                    sectionTitle("最近文件")
                    if model.recentFiles.isEmpty {
                        Text("截图和录屏完成后，会显示在这里。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 2)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(model.recentFiles.prefix(3)), id: \.path) { file in
                                if file.path != model.recentFiles.first?.path {
                                    groupDivider.padding(.leading, 28)
                                }
                                Button {
                                    model.reveal(file)
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: file.pathExtension.lowercased() == "png" ? "photo" : "film")
                                            .frame(width: 18)
                                            .foregroundStyle(.secondary)
                                            .accessibilityHidden(true)
                                        Text(file.lastPathComponent)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                        Spacer(minLength: 8)
                                        Image(systemName: "arrow.up.forward.square")
                                            .font(.caption)
                                            .foregroundStyle(.tertiary)
                                            .accessibilityHidden(true)
                                    }
                                    .contentShape(Rectangle())
                                    .padding(.vertical, 8)
                                }
                                .buttonStyle(PressFadeStyle())
                                .background { HoverWash(radius: 8) }
                                .help("在 Finder 中显示 \(file.lastPathComponent)")
                            }
                        }
                        .elevatedGroup()
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    sectionTitle("本机工具")
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(model.dependencyDetails, id: \.0) { item in
                            HStack(spacing: 8) {
                                Image(systemName: item.1 ? "checkmark.circle.fill" : "exclamationmark.circle")
                                    .foregroundStyle(item.1 ? StationPalette.connected : StationPalette.caution)
                                    .accessibilityHidden(true)
                                Text(item.0)
                                Spacer(minLength: 8)
                                Text(item.1 ? "已找到" : "未找到")
                                    .foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                        }
                    }
                    .padding(.horizontal, 2)
                    Text("会话提醒、文件和 APK 仍按项目说明操作。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 2)
                }

                Button {
                    model.quit()
                } label: {
                    Text("退出手机工位")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .padding(.vertical, 4)
                }
                .buttonStyle(PressFadeStyle())
                .background { HoverWash(radius: 8) }
                .font(.subheadline)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { model.refreshLoginItem() }
    }

    private var aboutPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader("关于", back: .more)
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .center, spacing: 12) {
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 44, height: 44)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("手机工位")
                            .font(.system(size: 16, weight: .semibold))
                        Text("版本 \(appVersion)")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }
                Text("从菜单栏连接这台手机，投屏、截取画面、录屏，并控制亮屏和闪光灯。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("打开项目说明") { model.openGuide() }
                    .buttonStyle(.bordered)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var appVersion: String {
        let short = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if short.isEmpty { return "未知" }
        if build.isEmpty || build == short { return short }
        return "\(short) (\(build))"
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 2)
    }

    private func subpageHeader(_ title: String, back: StationModel.Page = .main) -> some View {
        Button {
            model.page = back
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "chevron.left")
                    .font(.caption.weight(.semibold))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .accessibilityLabel("返回")
    }

    private func navigationRow(_ title: String, symbol: String, detail: String? = nil,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .frame(width: 18)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                    if let detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
            .padding(.vertical, detail == nil ? 9 : 7)
        }
        .buttonStyle(PressFadeStyle())
        .background { HoverWash(radius: 8) }
        .font(.body)
    }

    private func toggleRow(_ title: String, symbol: String, subtitle: String? = nil,
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
        .padding(.vertical, 7)
        .opacity(enabled ? 1 : 0.4)
    }

    /// `.bordered` uses a fixed bezel height on macOS 26 and clips a stacked caption.
    private func actionTile(_ title: String, symbol: String, active: Bool = false,
                            tint: Color = StationPalette.connected, enabled: Bool,
                            action: @escaping () -> Void) -> some View {
        ActionTile(title: title, symbol: symbol, active: active, tint: tint, enabled: enabled, action: action)
    }

    private func feedbackBanner(_ text: String) -> some View {
        HStack(alignment: .center, spacing: 8) {
            if model.activity != nil {
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

    private var groupDivider: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.08))
            .frame(height: 1)
    }

    private var stayAwakeBinding: Binding<Bool> {
        Binding(get: { model.stayAwake }, set: { model.setStayAwake($0) })
    }

    private var torchBinding: Binding<Bool> {
        Binding(get: { model.torchOn }, set: { model.setTorch($0) })
    }

    private var beatBinding: Binding<Bool> {
        Binding(get: { model.torchBeat }, set: { model.setTorchBeat($0) })
    }

    private var loginBinding: Binding<Bool> {
        Binding(get: { model.opensAtLogin }, set: { model.setOpensAtLogin($0) })
    }
}

/// Icon-above-title tile. Drawn here because the system bordered button clips the caption.
private struct ActionTile: View {
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

private struct PressFadeStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.62 : 1)
    }
}

/// Hover wash without `@State`. Command Line Tools ship SwiftUI property wrappers, not the State macro plugin.
private struct HoverWash: NSViewRepresentable {
    var radius: CGFloat = 8
    var enabled: Bool = true

    func makeNSView(context: Context) -> HoverWashView {
        let view = HoverWashView()
        view.radius = radius
        view.tracksHover = enabled
        return view
    }

    func updateNSView(_ nsView: HoverWashView, context: Context) {
        nsView.radius = radius
        nsView.tracksHover = enabled
        if !enabled { nsView.clearWash() }
    }
}

private final class HoverWashView: NSView {
    var radius: CGFloat = 8 {
        didSet { layer?.cornerRadius = radius }
    }
    var tracksHover = true
    private var tracking: NSTrackingArea?

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        wantsLayer = true
        layer?.cornerRadius = radius
        layer?.masksToBounds = true
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
    }

    override func mouseExited(with event: NSEvent) {
        clearWash()
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

private extension View {
    func elevatedGroup() -> some View {
        padding(.horizontal, 10)
            .padding(.vertical, 2)
            .background {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(StationPalette.tile)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
                    }
            }
    }
}

private enum StationPalette {
    static let background = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 37 / 255, green: 37 / 255, blue: 39 / 255, alpha: 1),
        light: NSColor(srgbRed: 0.95, green: 0.95, blue: 0.96, alpha: 1)))
    static let tile = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 50 / 255, green: 50 / 255, blue: 52 / 255, alpha: 1),
        light: NSColor.white))
    static let connected = Color(nsColor: dynamic(
        dark: NSColor(srgbRed: 0.463, green: 0.694, blue: 0.537, alpha: 1),
        light: NSColor(srgbRed: 0.18, green: 0.52, blue: 0.32, alpha: 1)))
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
