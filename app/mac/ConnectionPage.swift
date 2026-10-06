import SwiftUI

struct ConnectionPage: View {
    @ObservedObject var station: Station
    @StationViewState private var showsPairing = false
    @StationViewState private var showsDiagnostics = false

    var body: some View {
        StationPageScroll(maxHeight: 640) {
            VStack(alignment: .leading, spacing: 0) {
                StationRows.subpageHeader(StationL10n.text("连接设置")) { station.goBack(fallback: .main) }
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        StationRows.sectionTitle(StationL10n.text("本地连接"))
                        VStack(alignment: .leading, spacing: 0) {
                            StationRows.connectionRow(station.link.transport == "USB 连接" ? "USB" : StationL10n.text("无线调试"),
                                symbol: station.link.transport == "USB 连接" ? "cable.connector" : "wifi",
                                status: station.link.adbStatusLabel, online: station.link.serial != nil,
                                checking: station.link.serial == nil && (station.link.isChecking || station.link.isReconnecting))
                            VStack(alignment: .leading, spacing: 10) {
                                if station.link.isUnverified {
                                    Text(StationL10n.text(station.link.deviceDetail)).font(.subheadline).foregroundStyle(StationPalette.caution)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                if station.link.serial == nil {
                                    Button(station.link.isReconnecting ? StationL10n.text("正在连接…") : StationL10n.text("连接本地手机")) { station.link.connect() }
                                        .buttonStyle(.borderedProminent)
                                        .disabled(station.feedback.activity != nil || station.link.isChecking || station.link.isReconnecting)
                                } else if station.link.transport == "无线连接" {
                                    Button(StationL10n.text("断开无线连接")) { station.link.disconnect() }
                                        .buttonStyle(.bordered).disabled(station.feedback.activity != nil)
                                }
                                DisclosureGroup(StationL10n.text("配对与连接说明"), isExpanded: $showsPairing) {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(StationL10n.text("已配对的无线调试或 USB 可直接连接。无线断开成功后，本次运行暂停自动重连；再次连接即可恢复。"))
                                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                        Text(StationL10n.text("首次配对：手机打开「无线调试 → 使用配对码配对」，将 6 位码填在下方。地址会自动查找。"))
                                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                        TextField(StationL10n.text("6 位配对码"), text: Binding(get: { station.setup.pairCode }, set: { station.setup.pairCode = $0 }))
                                            .textFieldStyle(.roundedBorder).accessibilityLabel(StationL10n.text("6 位配对码"))
                                        Button(StationL10n.text("使用配对码配对")) { station.setup.pair() }
                                            .buttonStyle(.bordered).disabled(station.feedback.activity != nil)
                                    }.padding(.top, 6)
                                }.font(.subheadline)
                            }.padding(.horizontal, 12).padding(.bottom, 12)
                        }.elevatedGroup()
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        StationRows.sectionTitle(StationL10n.text("远程连接"))
                        StationRows.navigationRow(StationL10n.text("远程接入"), symbol: "network", detail: station.mcp.remoteStatusLabel) {
                            station.page = .remoteRelay
                        }.elevatedGroup()
                    }
                    StationOperationFeedback(station: station)
                    DisclosureGroup(StationL10n.text("连接检查"), isExpanded: $showsDiagnostics) {
                        VStack(spacing: 0) {
                            StationRows.connectionRow(StationL10n.text("本地 MCP"), symbol: "cable.connector",
                                status: station.mcp.localOnline ? StationL10n.text("可用") : StationL10n.text("未连通"), online: station.mcp.localOnline)
                            StationRows.groupDivider.padding(.horizontal, 10)
                            StationRows.connectionRow(StationL10n.text("远程 MCP"), symbol: "network", status: station.mcp.remoteStatusLabel,
                                online: station.mcp.remoteOnline, checking: station.mcp.remoteChecking)
                            StationRows.groupDivider.padding(.horizontal, 10)
                            StationRows.navigationRow(StationL10n.text("MCP 服务"), symbol: "point.3.connected.trianglepath.dotted",
                                detail: station.mcpPresentation.summary, needsAttention: station.mcpPresentation.needsAttention) {
                                station.page = .mcp
                            }
                            StationRows.groupDivider.padding(.horizontal, 10)
                            StationRows.navigationRow(StationL10n.text("手机权限与状态"), symbol: "checkmark.shield") { station.openFeatures() }
                        }.elevatedGroup().padding(.top, 8)
                    }.font(.subheadline)
                }.padding(.horizontal, 16).padding(.bottom, 16)
            }
        }
    }
}
