import SwiftUI

struct ConnectionPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("连接与配对") { station.page = .main }
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(station.link.headline)
                        .font(.system(size: 15, weight: .semibold))
                    if !station.link.connectionLine.isEmpty {
                        Text(station.link.connectionLine)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                VStack(spacing: 0) {
                    StationRows.connectionRow(station.link.transport == "USB 连接" ? "USB 调试" : "adb 无线调试",
                        symbol: station.link.transport == "USB 连接" ? "cable.connector" : "wifi",
                        status: station.link.adbStatusLabel, online: station.link.serial != nil,
                        checking: station.link.serial == nil && (station.link.isChecking || station.link.isReconnecting))
                    StationRows.groupDivider.padding(.horizontal, 10)
                    StationRows.connectionRow("远程连接", symbol: "network", status: station.mcp.remoteStatusLabel,
                        online: station.mcp.remoteOnline, checking: station.mcp.remoteChecking)
                }.elevatedGroup()
                Text(station.link.remoteConnected && station.link.serial == nil
                     ? "远程连接已可用，可使用文件、截屏、保持亮屏和手电筒。投屏、录屏与灯光跟随声音需要接入 USB，或回到同一 Wi-Fi。"
                     : "无线调试配对过一次并且开着，或 USB 已经接上，就可以连接。没有设备在线时会自动查找并连接。断开成功之后，要再点「连接手机」，这次打开期间才会继续自动连接。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if station.link.serial == nil {
                    Button {
                        station.link.connect()
                    } label: {
                        Label(station.link.isReconnecting || station.feedback.activity == "正在连接手机…" ? "正在连接…" : "连接手机",
                              systemImage: "wifi")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(station.feedback.activity != nil || station.link.isChecking)
                } else if station.link.transport == "无线连接" {
                    Button("断开无线连接") { station.link.disconnect() }
                        .buttonStyle(.bordered)
                        .disabled(station.feedback.activity != nil)
                } else {
                    Text("USB 保持连接。这里只断开无线。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("第一次配对")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                    Text("手机打开「无线调试 → 使用配对码配对」，停在那个页面，把 6 位码填在这里。不要用页面上的 172.19 地址。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    TextField("6 位配对码", text: Binding(
                        get: { station.setup.pairCode },
                        set: { station.setup.pairCode = $0 }
                    ))
                        .textFieldStyle(.roundedBorder)
                    Button("使用配对码配对") { station.setup.pair() }
                        .buttonStyle(.bordered)
                        .disabled(station.feedback.activity != nil)
                }
                Button("打开项目说明") { station.login.openGuide() }
                    .buttonStyle(.bordered)
                StationRows.navigationRow("远程 MCP 连接", symbol: "network", detail: "在不同网络下使用手机工具") {
                    station.page = .mcp
                }.elevatedGroup()
                if let text = station.feedbackText {
                    StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
