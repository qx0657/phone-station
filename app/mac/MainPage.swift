import SwiftUI

struct MainPage: View {
    @ObservedObject var station: Station
    @StationViewState private var showsControls: Bool = false

    var body: some View {
        StationPageScroll(maxHeight: 640) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(StationL10n.text("手机工位")).font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                    Spacer()
                    Button { station.page = .more } label: {
                        HStack(spacing: 5) {
                            if station.phoneFeatures?.master != false && !station.health.issues.isEmpty {
                                Image(systemName: "exclamationmark.circle.fill").foregroundStyle(StationPalette.caution)
                                    .accessibilityHidden(true)
                            }
                            Image(systemName: "gearshape").font(.system(size: 15))
                        }
                        .frame(minWidth: 28, minHeight: 28).contentShape(Rectangle())
                    }.buttonStyle(.borderless).help(StationL10n.text("设置 · ") + StationL10n.text(settingsDetail))
                        .accessibilityLabel(StationL10n.format("设置，{0}", StationL10n.text("\(settingsDetail)")))
                }.padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 8)
                header
                VStack(alignment: .leading, spacing: 12) {
                    actionRow
                    if station.phoneFeatures?.master == false {
                        Button(StationL10n.text("已暂停 · 查看恢复方法")) { station.openFeatures() }
                            .buttonStyle(.borderless).font(.subheadline).foregroundStyle(StationPalette.caution)
                    } else if station.link.serial == nil && station.link.remoteConnected {
                        if let blocker = station.remoteBlocker([.screen, .capture, .controls]) {
                            RemotePermissionHint(station: station, blocker: blocker)
                        }
                    }
                    if let pending = station.remoteControls.pendingCapture {
                        HStack {
                            Button(StationL10n.text(station.remoteControls.captureRecoveryTitle)) { station.remoteControls.recoverCapture() }
                            Button(StationL10n.text("放弃并清理")) { station.remoteControls.discardCapture() }
                        }.buttonStyle(.borderless).disabled(!station.remoteControls.canRecoverCapture)
                        Text(StationL10n.format("截图编号：{0}", "\(pending.id)")).font(.caption.monospaced()).textSelection(.enabled)
                    }
                    if station.phoneFeatures?.master != false && (!station.featureAllows("screen") || !station.featureAllows("capture")) {
                        Button(StationL10n.text("管理屏幕功能")) { station.openFeatures(.screen) }
                            .buttonStyle(.borderless).font(.subheadline)
                    }
                    controlGroup
                    StationOperationFeedback(station: station)
                    if station.torch.beatNeedsAudioPermission {
                        Button(StationL10n.text("打开「系统录音」")) { station.torch.openAudioCaptureSettings() }
                            .buttonStyle(.bordered)
                    }
                    VStack(spacing: 0) {
                        StationRows.navigationRow(StationL10n.text("MCP 服务"), symbol: "point.3.connected.trianglepath.dotted",
                            detail: StationL10n.text(station.mcpPresentation.summary) + StationL10n.text(" · 文件、命令与屏幕"),
                            needsAttention: station.mcpPresentation.needsAttention) { station.page = .mcp }
                        StationRows.groupDivider.padding(.horizontal, 10)
                        StationRows.navigationRow(StationL10n.text("共享剪贴板"), symbol: "doc.on.clipboard", detail: station.phoneFeatures?.master == false ? StationL10n.text("手机工位已暂停") : station.clipboard.summary,
                                                  needsAttention: station.phoneFeatures?.master != false && station.clipboard.shared && station.clipboard.blocker != nil) {
                            station.page = .clipboard
                        }
                        StationRows.groupDivider.padding(.horizontal, 10)
                        StationRows.navigationRow(StationL10n.text("手机通知"), symbol: "bell.badge", detail: station.phoneFeatures?.master == false ? StationL10n.text("手机工位已暂停") : station.notifications.summary,
                                                  needsAttention: notificationsNeedAttention) {
                            station.page = .notifications
                        }
                        StationRows.groupDivider.padding(.horizontal, 10)
                        StationRows.navigationRow(StationL10n.text("最近文件"), symbol: "photo.on.rectangle.angled", detail: recentFilesDetail, detailLineLimit: 1) {
                            station.page = .files
                        }
                    }
                    .elevatedGroup()
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            }
            .onAppear {
                showsControls = stayAwakeBinding.wrappedValue || torchBinding.wrappedValue || beatBinding.wrappedValue
            }
        }
    }

    private var notificationsNeedAttention: Bool {
        if station.phoneFeatures?.master == false { return false }
        if station.notifications.receiving, station.notifications.snapshot?.enabled == true,
           station.remoteBlocker([.notifications])?.canResolve == true { return true }
        if station.health.issues.contains(where: { $0.destination == "notifications" }) { return true }
        guard station.notifications.connected, let snapshot = station.notifications.snapshot, snapshot.enabled else { return false }
        return !snapshot.accessGranted || snapshot.selectedCount == 0 || !snapshot.listenerConnected
            || (station.notifications.receiving && station.notifications.permission != .authorized)
    }

    private var settingsDetail: String {
        if station.phoneFeatures?.master != false && !station.health.issues.isEmpty { return StationL10n.format("{0} 项需要处理", "\(station.health.issues.count)") }
        if station.health.failure != nil { return StationL10n.text("手机状态暂未确认") }
        if station.remoteControls.failure != nil || (station.remoteControls.reading != nil && station.remoteControls.reason != nil) {
            return StationL10n.text("查看远程控制状态")
        }
        return StationL10n.text("权限、应用更新与设置")
    }

    private var recentFilesDetail: String {
        switch station.files.recentFiles.count {
        case 0: return StationL10n.text("截图和录屏")
        case 1: return StationL10n.text("1 个文件")
        default: return StationL10n.format("{0} 个文件", "\(station.files.recentFiles.count)")
        }
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            ActionTile(title: station.screen.isMirroring ? StationL10n.text("关闭投屏") : StationL10n.text("打开投屏"),
                       symbol: "display",
                       active: station.screen.isMirroring,
                       tint: StationPalette.connected,
                       enabled: station.screen.isMirroring || station.canStartScreen) {
                station.screen.isMirroring ? station.screen.stopMirror() : station.screen.startMirror()
            }
            ActionTile(title: StationL10n.text("截取画面"), symbol: "camera", active: false,
                       tint: StationPalette.connected, enabled: station.canScreenshot) {
                station.screenshot()
            }
            ActionTile(
                title: station.screen.isRecording
                    ? (station.screen.isStoppingRecording ? StationL10n.text("保存中…") : StationL10n.text("停止录屏"))
                    : StationL10n.text("开始录屏"),
                symbol: station.screen.isRecording ? "stop.circle.fill" : "video",
                active: station.screen.isRecording,
                tint: StationPalette.recording,
                enabled: (station.screen.isRecording && !station.screen.isStoppingRecording) || station.canStartScreen) {
                station.screen.isRecording ? station.screen.stopRecording() : station.screen.startRecording()
            }
        }
    }

    private var controlGroup: some View {
        DisclosureGroup(isExpanded: $showsControls) {
            VStack(spacing: 0) {
                StationRows.toggleRow(StationL10n.text("保持亮屏"), symbol: "sun.max", isOn: stayAwakeBinding, enabled: station.canStayAwake)
                StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
                StationRows.toggleRow(StationL10n.text("手电筒"), symbol: torchBinding.wrappedValue ? "flashlight.on.fill" : "flashlight.off.fill",
                                      isOn: torchBinding, enabled: station.canTorch || station.torch.torchBeat)
                StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
                StationRows.toggleRow(StationL10n.text("灯光跟随声音"), symbol: "waveform",
                                      subtitle: StationL10n.text("跟随 Mac 播放的声音，需要本地连接"),
                                      isOn: beatBinding, enabled: (station.link.serial != nil && station.canTorch) || station.torch.torchBeat)
            }
        } label: {
            HStack {
                Label(StationL10n.text("手机控制"), systemImage: "slider.horizontal.3")
                Spacer(minLength: 6)
                Text(StationL10n.text(controlSummary)).font(.caption).foregroundStyle(.secondary)
            }.font(.subheadline)
        }
        .padding(10)
        .elevatedGroup()
    }

    private var controlSummary: String {
        var active: [String] = []
        if stayAwakeBinding.wrappedValue { active.append(StationL10n.text("亮屏")) }
        if torchBinding.wrappedValue { active.append(StationL10n.text("手电筒")) }
        if beatBinding.wrappedValue { active.append(StationL10n.text("声音灯光")) }
        return active.isEmpty ? StationL10n.text("亮屏与灯光") : active.joined(separator: " · ")
    }

    private var header: some View {
        Button { station.page = .connection } label: {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: station.link.isConnected ? "iphone" : "iphone.slash")
                    .font(.system(size: 23)).foregroundStyle(statusTint).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(StationL10n.text(station.link.headline))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 10) {
                        connectionIndicator(station.link.serial != nil ? (station.link.transport == "USB 连接" ? "USB" : StationL10n.text("无线")) : StationL10n.text("本地未连接"),
                            symbol: station.link.transport == "USB 连接" ? "cable.connector" : station.link.serial != nil ? "wifi" : "wifi.slash",
                            online: station.link.serial != nil, checking: station.link.serial == nil && (station.link.isChecking || station.link.isReconnecting))
                        connectionIndicator(station.mcp.remoteOnline ? StationL10n.text("远程") : station.mcp.remoteChecking ? StationL10n.text("远程确认中") : StationL10n.text("远程未连接"),
                            symbol: "network",
                            online: station.mcp.remoteOnline, checking: station.mcp.remoteChecking)
                    }
                    if !station.link.isConnected && !station.link.connectionLine.isEmpty {
                        Text(StationL10n.text(station.link.connectionLine)).font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                if let battery = station.link.battery { batteryReadout(battery) }
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary).accessibilityHidden(true)
            }
            .padding(12).contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .background { HoverWash(radius: 12) }
        .elevatedGroup()
        .padding(.horizontal, 16).padding(.bottom, 12)
        .help(StationL10n.text("连接设置与配对"))
    }

    private func connectionIndicator(_ title: String, symbol: String, online: Bool, checking: Bool) -> some View {
        Label(title, systemImage: symbol).font(.system(size: 11))
            .foregroundStyle(online ? StationPalette.connected : checking ? StationPalette.caution : Color.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityLabel(title + (online ? StationL10n.text("已连接") : checking ? StationL10n.text("，检查中") : ""))
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
        .accessibilityLabel(reading.charging ? StationL10n.format("电量 {0}%，充电中", "\(reading.percent)") : StationL10n.format("电量 {0}%", "\(reading.percent)"))
    }

    private func batterySymbol(_ percent: Int) -> String {
        switch percent {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }

    private var statusTint: Color {
        switch station.link.statusTone {
        case .ready: return StationPalette.connected
        case .caution: return StationPalette.caution
        case .neutral: return Color.secondary
        }
    }

    private var stayAwakeBinding: Binding<Bool> {
        Binding(get: { station.link.serial != nil ? station.link.stayAwake : station.remoteControls.stayAwake }, set: { station.setStayAwake($0) })
    }

    private var torchBinding: Binding<Bool> {
        Binding(get: { station.link.serial != nil || station.torch.isBeating ? station.torch.torchOn : station.remoteControls.torchOn }, set: { station.setTorch($0) })
    }

    private var beatBinding: Binding<Bool> {
        Binding(get: { station.torch.torchBeat }, set: { station.torch.setTorchBeat($0) })
    }
}
