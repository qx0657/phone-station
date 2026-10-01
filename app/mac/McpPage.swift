import SwiftUI

struct McpPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("MCP服务") { station.page = .main }
            VStack(alignment: .leading, spacing: 12) {
                Text(station.mcp.listening ? "开着。电脑上的地址和令牌在下面。" : "关着。打开之后，手机会记住，重启后还会再打开。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if station.mcp.listening {
                    VStack(alignment: .leading, spacing: 6) {
                        if !station.mcp.channel.isEmpty {
                            Text(station.mcp.channel == "remote" ? "当前通道：深圳远程中继" : "当前通道：adb")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Text(station.mcp.endpoint)
                            .font(.system(size: 12, design: .monospaced))
                            .textSelection(.enabled)
                        if station.mcp.token.isEmpty {
                            Text("正在读取令牌…")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else {
                            Text(station.mcp.token)
                                .font(.system(size: 12, design: .monospaced))
                                .textSelection(.enabled)
                                .lineLimit(2)
                            Button(station.mcp.copied ? "已复制" : "复制 Authorization") {
                                station.mcp.copyToken()
                            }
                            .buttonStyle(.borderless)
                            .font(.subheadline)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .elevatedGroup()
                }
                if station.mcp.listening {
                    Button("停止") { station.mcp.stop() }
                        .buttonStyle(.bordered)
                        .disabled(station.feedback.activity != nil || station.link.serial == nil)
                } else {
                    Button("打开") { station.mcp.start() }
                        .buttonStyle(.borderedProminent)
                        .disabled(station.feedback.activity != nil)
                }
                Text("手机上的 MCP 开关与本机服务相同。深圳远程中继需先配对；完整 adb、投屏与录屏仍走本地 adb。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let text = station.feedbackText {
                    StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
