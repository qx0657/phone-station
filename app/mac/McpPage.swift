import SwiftUI

struct McpPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("电脑接入") { station.goBack(fallback: .more) }
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(station.mcp.listening ? "手机工具已可用" : station.mcp.remoteChecking ? "正在确认手机连接" : station.mcp.gatewayReady ? "正在等待手机" : "连接手机的 MCP 工具")
                        .font(.system(size: 15, weight: .semibold))
                    Text("读改普通文件、查看状态、使用剪贴板。本地和远程通道提供同一套手机工具。")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                VStack(spacing: 0) {
                    channelRow("本地 adb", symbol: "cable.connector", online: station.mcp.localOnline,
                               selected: station.mcp.channel == "local")
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.connectionRow("远程中继", symbol: "network",
                        status: station.mcp.remoteOnline && station.mcp.channel == "remote" ? "正在使用" : station.mcp.remoteStatusLabel,
                        online: station.mcp.remoteOnline, checking: station.mcp.remoteChecking)
                }.elevatedGroup()
                if station.mcp.listening {
                    DisclosureGroup("MCP 接入信息") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(station.mcp.endpoint).font(.system(size: 12, design: .monospaced))
                                .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                            HStack(spacing: 12) {
                                Button("复制地址") { station.mcp.copyEndpoint() }
                                Button(station.mcp.copied ? "已复制" : "复制 Authorization") { station.mcp.copyToken() }
                                    .disabled(station.mcp.token.isEmpty)
                            }.buttonStyle(.borderless).font(.subheadline)
                            Text("地址保持不变，网关自动选择可用通道。授权令牌通过复制使用。")
                                .font(.caption).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }.padding(.top, 8)
                    }.font(.subheadline).padding(12).elevatedGroup()
                }
                if station.mcp.listening {
                    Button("停止手机 MCP 服务") { station.mcp.stop() }
                        .buttonStyle(.bordered).disabled(!station.canOperate)
                    if station.link.serial == nil {
                        Text("停止手机服务需本地连接，也可在手机「连接设置」中关闭本地 MCP 接入与远程通道。")
                            .font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    Button("连接 MCP 服务") { station.mcp.start() }
                        .buttonStyle(.borderedProminent).disabled(station.feedback.activity != nil || station.mcp.remoteChecking)
                }
                StationRows.navigationRow("远程中继", symbol: "network",
                    detail: station.mcp.remoteOnline ? "已连接 · 查看或修改配置" : "设置服务器地址、指纹和令牌") {
                        station.page = .remoteRelay
                    }.elevatedGroup()
                Text("本地和远程都支持投屏与录屏。安装或更新优先使用远程连接。")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let text = station.feedbackText {
                    StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                }
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func channelRow(_ title: String, symbol: String, online: Bool, selected: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).frame(width: 18).foregroundStyle(.secondary).accessibilityHidden(true)
            Text(title).font(.subheadline)
            Spacer()
            Text(selected ? "正在使用" : online ? "可用" : "未连通")
                .font(.system(size: 12, weight: selected ? .medium : .regular))
                .foregroundStyle(online ? StationPalette.connected : Color.secondary)
        }.padding(.horizontal, 12).padding(.vertical, 11)
    }
}
