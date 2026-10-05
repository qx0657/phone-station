import SwiftUI

struct McpPage: View {
    @ObservedObject var station: Station

    var body: some View {
        StationPageScroll(maxHeight: 640) {
            VStack(alignment: .leading, spacing: 0) {
                StationRows.subpageHeader("MCP 服务") { station.goBack(fallback: .main) }
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Label(station.mcpPresentation.summary, systemImage: station.mcpPresentation.available ? "checkmark.circle" : "pause.circle")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(station.mcpPresentation.needsAttention ? StationPalette.caution : station.mcpPresentation.available ? StationPalette.connected : .secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(station.mcpPresentation.detail).font(.subheadline).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        if !station.mcp.listening && station.phoneFeatures?.master != false {
                            Button("连接 MCP 服务") { station.mcp.start() }
                                .buttonStyle(.borderedProminent).disabled(station.feedback.activity != nil || station.mcp.remoteChecking)
                        } else if station.phoneFeatures?.master == false {
                            Button("查看恢复方法") { station.openFeatures() }.buttonStyle(.bordered)
                        }
                        StationOperationFeedback(station: station)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        StationRows.sectionTitle("手机能力")
                        VStack(spacing: 0) {
                            StationRows.navigationRow("文件访问", symbol: "folder", detail: "读取、修改与打开普通文件") { station.openFeatures(.files) }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("命令与安装", symbol: "terminal", detail: "Shell、交互终端与 APK 安装") { station.openFeatures(.commands) }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("屏幕与手机控制", symbol: "display", detail: "投屏、截屏、录屏、亮屏与灯光") { station.openFeatures(.screen) }
                        }.elevatedGroup()
                    }
                    StationRows.navigationRow("连接设置", symbol: "network", detail: "管理本地与远程接入，查看连接检查") {
                        station.page = .connection
                    }.elevatedGroup()
                    DisclosureGroup("MCP 接入信息与服务操作") {
                        VStack(alignment: .leading, spacing: 10) {
                            if station.mcp.requestAvailable {
                                Text(station.mcp.endpoint).font(.system(size: 12, design: .monospaced))
                                    .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                                HStack(spacing: 12) {
                                    Button("复制地址") { station.mcp.copyEndpoint() }
                                    Button(station.mcp.copied ? "已复制" : "复制 Authorization") { station.mcp.copyToken() }
                                        .disabled(station.mcp.token.isEmpty)
                                }.buttonStyle(.borderless)
                            }
                            Text("电脑使用固定网关地址，自动选择已连通的本地或远程通道。令牌通过复制使用。")
                                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            if station.mcp.gatewayReady {
                                Button("停止手机 MCP 服务") { station.mcp.stop() }
                                    .buttonStyle(.bordered).disabled(!station.canOperate)
                                Text("停止手机服务需要本地连接。仅远程时，请在手机「MCP 服务」中管理本地接入与远程连接。")
                                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            }
                        }.padding(.top, 8)
                    }.font(.subheadline)
                }.padding(.horizontal, 16).padding(.bottom, 16)
            }
        }
    }
}
