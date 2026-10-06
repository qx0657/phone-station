import SwiftUI

struct ClipboardPage: View {
    @ObservedObject var station: Station
    @ObservedObject var clipboard: ClipboardSession
    var body: some View {
        StationPageScroll(maxHeight: 640) {
            VStack(alignment: .leading, spacing: 0) {
                StationRows.subpageHeader(StationL10n.text("共享剪贴板")) { station.goBack(fallback: .main) }
                VStack(alignment: .leading, spacing: 14) {
                    VStack(spacing: 0) {
                    StationRows.toggleRow(StationL10n.text("共享剪贴板"), symbol: "doc.on.clipboard",
                        subtitle: StationL10n.text("复制文字或链接，自动同步到另一端"),
                        isOn: Binding(get: { clipboard.shared }, set: { clipboard.configure(shared: $0) }),
                        enabled: station.mcp.listening && !clipboard.busy && station.remoteBlocker([.clipboard]) == nil)
                    StationRows.groupDivider.padding(.leading, 38)
                    StationRows.toggleRow(StationL10n.text("同步 Mac 图片到相册"), symbol: "photo",
                        subtitle: StationL10n.text("默认关闭；新图片存入手机相册，并显示无声通知"),
                        isOn: Binding(get: { clipboard.images }, set: { clipboard.configure(images: $0) }),
                        enabled: station.mcp.listening && clipboard.imagesSupported && !clipboard.busy && station.remoteBlocker([.clipboard]) == nil)
                    }.elevatedGroup()
                    if let blocker = station.remoteBlocker([.clipboard]) {
                        RemotePermissionHint(station: station, blocker: blocker)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Label(StationL10n.text(station.phoneFeatures?.master == false ? "手机工位已暂停" : clipboard.summary), systemImage: statusSymbol)
                            .font(.subheadline).foregroundStyle(statusColor)
                            .accessibilityAddTraits(.updatesFrequently)
                        if clipboard.shared, let blocker = clipboard.blocker {
                            Text(StationL10n.text(blocker.reason)).font(.subheadline).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            Button(station.link.serial == nil ? StationL10n.text("查看手机权限步骤") : StationL10n.text("打开手机权限页")) {
                                station.setup.openPermissions(serial: station.link.serial)
                            }.buttonStyle(.bordered)
                        }
                        if clipboard.shared, !clipboard.automatic, station.mcp.listening {
                            Button(StationL10n.text("恢复自动同步")) { clipboard.configure(automatic: true) }
                                .buttonStyle(.bordered).disabled(clipboard.busy || station.remoteBlocker([.clipboard]) != nil)
                        }
                        if clipboard.shared, let date = clipboard.lastSyncAt, let direction = clipboard.lastSyncDirection {
                            HStack {
                                Text(StationL10n.format("最近同步 · {0}", StationL10n.text("\(direction)")))
                                Spacer()
                                Text(date, style: .time).monospacedDigit()
                            }.font(.caption).foregroundStyle(.secondary)
                        }
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading).elevatedGroup()
                    if !station.mcp.listening {
                        Text(StationL10n.text("连接 MCP 服务后自动开始；Mac 手机工位需保持运行。"))
                            .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Button(StationL10n.text("连接 MCP 服务")) { station.mcp.start() }
                            .buttonStyle(.borderedProminent).disabled(station.feedback.activity != nil)
                    }
                    if let message = clipboard.message, message != clipboard.blocker?.reason {
                        StationRows.feedbackBanner(message, busy: clipboard.busy)
                    }
                    DisclosureGroup(StationL10n.text("使用说明与隐私")) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(StationL10n.text("在任意应用复制，再到另一端粘贴。首次连接后，请重新复制一次。"))
                            Text(StationL10n.text("文字和链接双向同步；图片开关开启后，Mac 图片存入手机相册的「手机工位」，每张最多 4 MB / 3200 万像素。图片关闭时安静跳过，文件与敏感内容仍跳过。手机需启动并授权 Shizuku，锁屏时暂停。文字仅保留在内存中。"))
                        }.font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
                    }.font(.subheadline)
                }.padding(16)
            }
            .onAppear { clipboard.show() }
            .onDisappear { clipboard.hide() }
        }
    }
    private var statusSymbol: String {
        if !clipboard.shared { return "pause.circle" }
        return clipboard.connected && clipboard.automatic && clipboard.summary == "自动双向同步"
            ? "checkmark.circle" : "arrow.triangle.2.circlepath"
    }
    private var statusColor: Color {
        if clipboard.blocker != nil { return StationPalette.caution }
        return clipboard.connected && clipboard.automatic && clipboard.summary == "自动双向同步"
            ? StationPalette.connected : .secondary
    }
}
