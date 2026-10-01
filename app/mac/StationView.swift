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
            case .commands:
                commandsPage
            case .editCommand:
                CommandEditor(model: model)
                    .id(model.commandDraftToken)
            case .more:
                morePage
            case .files:
                filesPage
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
                    groupDivider.padding(.horizontal, 10)
                    navigationRow("adb 命令", symbol: "terminal") { model.page = .commands }
                    groupDivider.padding(.horizontal, 10)
                    navigationRow("最近文件", symbol: "photo.on.rectangle.angled", detail: recentFilesDetail) {
                        model.page = .files
                    }
                    groupDivider.padding(.horizontal, 10)
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
            groupDivider.padding(.leading, 38).padding(.trailing, 10)
            toggleRow("手电筒", symbol: model.torchOn ? "flashlight.on.fill" : "flashlight.off.fill",
                      isOn: torchBinding, enabled: model.canOperate || model.torchBeat)
            groupDivider.padding(.leading, 38).padding(.trailing, 10)
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
            HStack(spacing: 8) {
                if let battery = model.battery {
                    batteryReadout(battery)
                }
                statusChip
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .accessibilityElement(children: .combine)
    }

    private func batteryReadout(_ reading: BatteryReport.Reading) -> some View {
        HStack(spacing: 3) {
            Image(systemName: batterySymbol(reading.percent))
                .font(.system(size: 12))
                .accessibilityHidden(true)
            Text("\(reading.percent)%")
                .font(.system(size: 11, weight: .medium))
            if reading.charging {
                Image(systemName: "bolt.fill")
                    .font(.system(size: 9))
                    .accessibilityHidden(true)
            }
        }
        .foregroundStyle(reading.percent < 20 && !reading.charging ? StationPalette.caution : Color.secondary)
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(reading.charging ? "电量 \(reading.percent)%，充电中" : "电量 \(reading.percent)%")
    }

    /// Partial bolt variants such as `battery.25.bolt` are absent on this Mac. The bolt is separate.
    private func batterySymbol(_ percent: Int) -> String {
        switch percent {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
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

    private var commandsPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader("adb 命令")
            VStack(alignment: .leading, spacing: 12) {
                Text("点一行执行。铅笔用来修改。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !model.commands.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(model.commands.enumerated()), id: \.element.id) { index, command in
                            if index > 0 {
                                groupDivider.padding(.horizontal, 10)
                            }
                            commandRow(command)
                        }
                    }
                    .elevatedGroup()
                }
                if model.activity != nil {
                    feedbackBanner(model.activity ?? "")
                }
                if let output = model.commandOutput {
                    commandOutputCard(output)
                }
                navigationRow("新建命令", symbol: "plus") { model.beginNewCommand() }
                    .elevatedGroup()
                shellRow
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func commandRow(_ command: SavedAdbCommand) -> some View {
        HStack(spacing: 0) {
            Button {
                model.runCommand(command)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(command.name)
                        .font(.body)
                    Text(command.arguments)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .disabled(!model.canOperate)
            .opacity(model.canOperate ? 1 : 0.4)
            Button {
                model.beginEditCommand(command)
            } label: {
                Image(systemName: "pencil")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .padding(.trailing, 6)
            .accessibilityLabel("编辑\(command.name)")
            .help("编辑\(command.name)")
        }
        .background { HoverWash(radius: 0) }
    }

    private func commandOutputCard(_ output: CommandOutput) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(output.name)
                    .font(.body)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button("复制") { model.copyCommandOutput() }
                    .buttonStyle(.borderless)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Text(output.text)
                .font(.system(size: 11, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(StationPalette.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .padding(10)
        .elevatedGroup()
    }

    private var shellRow: some View {
        Button {
            model.openDeviceShell()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "terminal")
                    .frame(width: 18)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text("在终端中打开 shell")
                    Text("在「终端」里打开，序列号已经绑上")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.forward.square")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .background { HoverWash(radius: 0) }
        .font(.body)
        .disabled(!model.canOperate)
        .opacity(model.canOperate ? 1 : 0.4)
        .elevatedGroup()
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
                Text("无线调试配对过一次并且开着，或 USB 已经接上，就可以连接。第一次配对仍按项目说明操作。手机页面上的 172.19.0.1 是 VPN 地址，配对要用局域网地址。没有设备在线时会自动查找并连接。断开成功之后，要再点「连接手机」，这次打开期间才会继续自动连接。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if model.serial == nil {
                    Button {
                        model.connect()
                    } label: {
                        Label(model.isReconnecting || model.activity == "正在连接手机…" ? "正在连接…" : "连接手机",
                              systemImage: "wifi")
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
                            .padding(.leading, 38)
                            .padding(.trailing, 10)
                            .padding(.bottom, 8)
                    }
                    groupDivider.padding(.leading, 38).padding(.trailing, 10)
                    navigationRow("关于", symbol: "info.circle", detail: "版本 \(appVersion)") {
                        model.page = .about
                    }
                }
                .elevatedGroup()

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

    private var recentFilesDetail: String {
        switch model.recentFiles.count {
        case 0: return "截图和录屏"
        case 1: return "1 个文件"
        default: return "\(model.recentFiles.count) 个文件"
        }
    }

    private var filesPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader("最近文件")
            if model.recentFiles.isEmpty {
                Text("截图和录屏完成后，会显示在这里。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(model.recentFiles.enumerated()), id: \.element.path) { index, file in
                        if index > 0 {
                            groupDivider.padding(.leading, 56).padding(.trailing, 10)
                        }
                        recentFileRow(file)
                    }
                }
                .elevatedGroup()
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            }
        }
        .onAppear { model.refreshRecentFiles() }
        .onDisappear { model.dismissRecentPreview() }
    }

    private func recentFileRow(_ file: URL) -> some View {
        let name = file.lastPathComponent
        let copied = model.copiedRecentPath == file.path
        let hovered = model.hoveredRecentPath == file.path
        return HStack(spacing: 0) {
            Button {
                model.copyRecentFile(file)
            } label: {
                HStack(spacing: 10) {
                    recentThumb(file)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(recentMeta(file))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text(copied ? "已复制" : "复制")
                        .font(.system(size: 12))
                        .foregroundStyle(copied ? StationPalette.connected : Color.secondary)
                        .frame(width: 46, alignment: .trailing)
                }
                .padding(.leading, 10)
                .padding(.vertical, 7)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .accessibilityLabel(copied ? "已复制 \(name)" : "复制 \(name)")
            .help(RecentFileStyle.kind(for: file) == "录屏" ? "把文件复制到剪贴板" : "把图片复制到剪贴板")
            Button {
                model.reveal(file)
            } label: {
                Image(systemName: "arrow.up.forward.square")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
                    .frame(width: 28, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .padding(.trailing, 6)
            .accessibilityLabel("在 Finder 中显示 \(name)")
            .help("在 Finder 中显示")
        }
        .background {
            HoverWash(
                radius: 0,
                onInside: { inside in model.setRecentHover(file, inside: inside) },
                onFrame: hovered ? { rect, window in
                    model.setRecentHoverAnchor(file, row: rect, host: window)
                } : nil)
        }
    }

    private func recentThumb(_ file: URL) -> some View {
        let symbol = RecentFileStyle.kind(for: file) == "录屏" ? "film" : "photo"
        return ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(StationPalette.background)
            if let image = model.recentThumbnails[file.path] {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 36, height: 36, alignment: .top)
                    .clipped()
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .accessibilityHidden(true)
    }

    private func recentMeta(_ file: URL) -> String {
        let kind = RecentFileStyle.kind(for: file)
        guard let date = model.recentModified[file.path] else { return kind }
        return "\(kind) · \(RecentFileStyle.date.string(from: date))"
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
                Text("从菜单栏连接这台手机，掉线后自动重连，投屏、截取画面、录屏，控制亮屏和闪光灯，并运行保存的 adb 命令。")
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
            .padding(.horizontal, 10)
            .padding(.vertical, detail == nil ? 9 : 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .background { HoverWash(radius: 0) }
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
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
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

private struct CommandEditor: View {
    @ObservedObject var model: StationModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            editorHeader
            VStack(alignment: .leading, spacing: 14) {
                fieldBlock("名称") {
                    TextField("例如 前台应用", text: $model.commandDraft.name)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13))
                }
                fieldBlock("参数") {
                    ZStack(alignment: .topLeading) {
                        TextEditor(text: $model.commandDraft.arguments)
                            .font(.system(size: 12, design: .monospaced))
                            .scrollContentBackground(.hidden)
                            .frame(height: 68)
                        if model.commandDraft.arguments.isEmpty {
                            Text("shell \"dumpsys window | grep mCurrentFocus\"")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .lineLimit(2)
                                .padding(.top, 8)
                                .padding(.leading, 5)
                                .allowsHitTesting(false)
                        }
                    }
                }
                Text("这些参数接在 adb -s 序列号 后面，不经过本机 shell。管道放在一对引号里，在手机上执行。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let errorText = model.commandEditError {
                    Text(errorText)
                        .font(.subheadline)
                        .foregroundStyle(StationPalette.recording)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    model.saveCommand()
                } label: {
                    Text("保存")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                if let id = model.commandDraft.id {
                    Button("删除这条命令") { model.deleteCommand(id: id) }
                        .buttonStyle(.borderless)
                        .foregroundStyle(StationPalette.recording)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var editorHeader: some View {
        Button {
            model.page = .commands
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "chevron.left")
                    .font(.caption.weight(.semibold))
                    .accessibilityHidden(true)
                Text(model.commandDraft.id == nil ? "新建命令" : "编辑命令")
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

    private func fieldBlock<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.secondary)
            content()
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(StationPalette.tile)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
                }
        }
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

private final class HoverWashView: NSView {
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

private extension View {
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

private enum StationChrome {
    static let cardRadius: CGFloat = 12
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
