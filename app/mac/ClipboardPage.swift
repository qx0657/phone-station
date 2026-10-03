import SwiftUI

struct ClipboardPage: View {
    @ObservedObject var station: Station
    @ObservedObject var clipboard: ClipboardSession
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("共享剪贴板") { station.page = .main }
            VStack(alignment: .leading, spacing: 14) {
                StationRows.toggleRow("共享剪贴板", symbol: "doc.on.clipboard",
                    subtitle: "复制文字或链接，自动同步到另一端",
                    isOn: Binding(get: { clipboard.shared }, set: { clipboard.configure(shared: $0) }),
                    enabled: station.mcp.listening && !clipboard.busy)
                    .elevatedGroup()
                StationRows.toggleRow("同步 Mac 图片到相册", symbol: "photo",
                    subtitle: "默认关闭；新图片存入手机相册，并显示无声通知",
                    isOn: Binding(get: { clipboard.images }, set: { clipboard.configure(images: $0) }),
                    enabled: station.mcp.listening && clipboard.imagesSupported && !clipboard.busy)
                    .elevatedGroup()
                VStack(alignment: .leading, spacing: 8) {
                    Label(clipboard.summary, systemImage: statusSymbol)
                        .font(.subheadline).foregroundStyle(statusColor)
                        .accessibilityAddTraits(.updatesFrequently)
                    if clipboard.shared, let blocker = clipboard.blocker {
                        Text(blocker.reason).font(.subheadline).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Button(station.link.serial == nil ? "查看手机权限步骤" : "打开手机权限页") {
                            station.setup.openPermissions(serial: station.link.serial)
                        }.buttonStyle(.bordered)
                    }
                    if clipboard.shared, !clipboard.automatic, station.mcp.listening {
                        Button("恢复自动同步") { clipboard.configure(automatic: true) }
                            .buttonStyle(.bordered).disabled(clipboard.busy)
                    }
                    if clipboard.shared, let date = clipboard.lastSyncAt, let direction = clipboard.lastSyncDirection {
                        HStack {
                            Text("最近同步 · \(direction)")
                            Spacer()
                            Text(date, style: .time).monospacedDigit()
                        }.font(.caption).foregroundStyle(.secondary)
                    }
                }.padding(12).frame(maxWidth: .infinity, alignment: .leading).elevatedGroup()
                if !station.mcp.listening {
                    Text("连接 MCP 服务后自动开始；Mac 手机工位需保持运行。")
                        .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Button("连接 MCP 服务") { station.mcp.start() }
                        .buttonStyle(.borderedProminent).disabled(station.feedback.activity != nil)
                }
                if let message = clipboard.message, message != clipboard.blocker?.reason {
                    StationRows.feedbackBanner(message, busy: clipboard.busy)
                }
                Text("在任意应用复制，再到另一端粘贴。首次连接后，请重新复制一次。")
                    .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                Text("文字和链接双向同步；图片开关开启后，Mac 图片存入手机相册的「手机工位」，每张最多 4 MB / 3200 万像素。图片关闭时安静跳过，文件与敏感内容仍跳过。手机需启动并授权 Shizuku，锁屏时暂停。文字仅保留在内存中。")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }.padding(16)
        }
        .onAppear { clipboard.show() }
        .onDisappear { clipboard.hide() }
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
