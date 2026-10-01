import SwiftUI

struct MainPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            VStack(alignment: .leading, spacing: 12) {
                actionRow
                controlGroup
                if let text = station.feedbackText {
                    StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                }
                if station.torch.beatNeedsAudioPermission {
                    Button("打开「系统录音」") { station.torch.openAudioCaptureSettings() }
                        .buttonStyle(.bordered)
                }
                VStack(spacing: 0) {
                    StationRows.navigationRow("连接与配对", symbol: "wifi") { station.page = .connection }
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.navigationRow("MCP服务", symbol: "point.3.connected.trianglepath.dotted",
                                              detail: station.mcp.summary) { station.page = .mcp }
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.navigationRow("adb 命令", symbol: "terminal") { station.page = .commands }
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.navigationRow("最近文件", symbol: "photo.on.rectangle.angled", detail: recentFilesDetail) {
                        station.page = .files
                    }
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.navigationRow("更多工具与设置", symbol: "slider.horizontal.3") {
                        station.page = .more
                    }
                }
                .elevatedGroup()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
        }
    }

    private var recentFilesDetail: String {
        switch station.files.recentFiles.count {
        case 0: return "截图和录屏"
        case 1: return "1 个文件"
        default: return "\(station.files.recentFiles.count) 个文件"
        }
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            ActionTile(title: station.screen.isMirroring ? "关闭投屏" : "打开投屏",
                       symbol: "display",
                       active: station.screen.isMirroring,
                       tint: StationPalette.connected,
                       enabled: station.screen.isMirroring || station.canStartScreen) {
                station.screen.isMirroring ? station.screen.stopMirror() : station.screen.startMirror()
            }
            ActionTile(title: "截取画面", symbol: "camera", active: false,
                       tint: StationPalette.connected, enabled: station.canOperate) {
                station.screen.screenshot()
            }
            ActionTile(
                title: station.screen.isRecording
                    ? (station.screen.isStoppingRecording ? "保存中…" : "停止录屏")
                    : "开始录屏",
                symbol: station.screen.isRecording ? "stop.circle.fill" : "video",
                active: station.screen.isRecording,
                tint: StationPalette.recording,
                enabled: (station.screen.isRecording && !station.screen.isStoppingRecording) || station.canStartScreen) {
                station.screen.isRecording ? station.screen.stopRecording() : station.screen.startRecording()
            }
        }
    }

    private var controlGroup: some View {
        VStack(spacing: 0) {
            StationRows.toggleRow("保持亮屏", symbol: "sun.max", isOn: stayAwakeBinding, enabled: station.canOperate)
            StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
            StationRows.toggleRow("手电筒", symbol: station.torch.torchOn ? "flashlight.on.fill" : "flashlight.off.fill",
                                  isOn: torchBinding, enabled: station.canOperate || station.torch.torchBeat)
            StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
            StationRows.toggleRow("灯光跟随声音", symbol: "waveform",
                                  subtitle: "跟随这台 Mac 正在播放的声音",
                                  isOn: beatBinding, enabled: station.canOperate || station.torch.torchBeat)
        }
        .elevatedGroup()
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(station.link.headline)
                    .font(.system(size: 16, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if !station.link.connectionLine.isEmpty {
                    Text(station.link.connectionLine)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) {
                if let battery = station.link.battery {
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
        Text(station.link.statusLabel)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(statusTint)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(statusTint.opacity(0.14), in: Capsule())
    }

    private var statusTint: Color {
        switch station.link.statusTone {
        case .ready: return StationPalette.connected
        case .caution: return StationPalette.caution
        case .neutral: return Color.secondary
        }
    }

    private var stayAwakeBinding: Binding<Bool> {
        Binding(get: { station.link.stayAwake }, set: { station.link.setStayAwake($0) })
    }

    private var torchBinding: Binding<Bool> {
        Binding(get: { station.torch.torchOn }, set: { station.torch.setTorch($0) })
    }

    private var beatBinding: Binding<Bool> {
        Binding(get: { station.torch.torchBeat }, set: { station.torch.setTorchBeat($0) })
    }
}
