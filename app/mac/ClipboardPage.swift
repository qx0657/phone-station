import SwiftUI

struct ClipboardPage: View {
    @ObservedObject var station: Station
    @ObservedObject var clipboard: ClipboardSession
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("共享剪贴板") { station.page = .main }
            VStack(alignment: .leading, spacing: 14) {
                VStack(spacing: 0) {
                    StationRows.toggleRow("共享剪贴板", symbol: "doc.on.clipboard",
                        isOn: Binding(get: { clipboard.shared }, set: { clipboard.configure(shared: $0) }),
                        enabled: station.mcp.listening && !clipboard.busy)
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.toggleRow("自动双向同步", symbol: "arrow.triangle.2.circlepath",
                        subtitle: "复制文字后，自动同步到另一端",
                        isOn: Binding(get: { clipboard.automatic }, set: { clipboard.configure(automatic: $0) }),
                        enabled: clipboard.shared && !clipboard.busy)
                }.elevatedGroup()
                if !station.mcp.listening {
                    Text("连接手机工具后即可共享，支持本地 adb 和远程中继。")
                        .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Button("连接 MCP 服务") { station.mcp.start() }
                        .buttonStyle(.borderedProminent).disabled(station.feedback.activity != nil)
                } else if clipboard.shared {
                    preview("手机剪贴板", clip: clipboard.phone, available: clipboard.connected,
                            button: "复制到 Mac", action: clipboard.copyPhoneToMac)
                    preview("Mac 剪贴板", clip: clipboard.mac, available: true,
                            button: "复制到手机", action: clipboard.copyMacToPhone)
                } else {
                    Text("开启后，两端可预览对方的剪贴板。关闭自动同步时，仍可手动复制。")
                        .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                if let message = clipboard.message {
                    StationRows.feedbackBanner(message, busy: clipboard.busy)
                }
                Text("支持文字和链接。手机需启动并授权 Shizuku；锁屏及敏感内容会暂停同步。内容只保留在内存中，退出 Mac 后停止同步。")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }.padding(16)
        }
        .onAppear { clipboard.show() }
        .onDisappear { clipboard.hide() }
    }
    private func preview(_ title: String, clip: ClipboardClip?, available: Bool,
                         button: String, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 13, weight: .semibold))
            ScrollView {
                Text(available ? (clip?.preview ?? "等待剪贴板…") : "等待手机连接…")
                    .font(.system(size: 13)).frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }.frame(height: 76)
            Button(button, action: action).buttonStyle(.borderless)
                .disabled(!clipboard.connected || clipboard.busy || clip?.kind != "text")
        }.padding(12).elevatedGroup()
    }
}
