import SwiftUI

struct NotificationPage: View {
    @ObservedObject var station: Station
    @ObservedObject var notifications: NotificationSession
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("手机通知") { station.goBack(fallback: .main) }
            StationPageScroll(maxHeight: 600) {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(spacing: 0) {
                        StationRows.toggleRow("在这台 Mac 接收", symbol: "bell.badge",
                            subtitle: "只接收手机端勾选应用的新通知",
                            isOn: Binding(get: { notifications.receiving }, set: { notifications.setReceiving($0) }),
                            enabled: !notifications.busy)
                    }.elevatedGroup()
                    Text(station.phoneFeatures?.master == false ? "手机工位已暂停；在手机首页恢复后接收新通知。" : notifications.summary).font(.subheadline).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let blocker = station.remoteBlocker([.notifications]) {
                        RemotePermissionHint(station: station, blocker: blocker)
                    }
                    VStack(spacing: 0) {
                        StationRows.toggleRow("显示应用图标横幅", symbol: "rectangle.topthird.inset.filled",
                            subtitle: "左侧使用手机应用图标，6 秒后收起",
                            isOn: Binding(get: { notifications.customBanners }, set: { notifications.setCustomBanners($0) }), enabled: true)
                    }.elevatedGroup()
                    if notifications.customBanners {
                        Button("预览横幅") { notifications.preview() }.buttonStyle(.bordered)
                            .disabled(notifications.previewing || notifications.permission != .authorized || !notifications.receiving)
                    }
                    if notifications.receiving && notifications.permission != .authorized {
                        Text("Mac 通知权限：\(notifications.permission.label)")
                            .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                        if notifications.permission == .notDetermined {
                            Button("允许 Mac 显示通知") { notifications.requestPermission() }
                                .buttonStyle(.borderedProminent).disabled(notifications.busy)
                        } else {
                            Button("打开 macOS 通知设置") { notifications.openSettings() }.buttonStyle(.bordered)
                        }
                    }
                    if !station.mcp.listening {
                        Button("连接 MCP 服务") { station.mcp.start() }
                            .buttonStyle(.borderedProminent).disabled(station.feedback.activity != nil)
                    } else if let state = notifications.snapshot {
                        if state.enabled && state.accessGranted && state.selectedCount > 0 && state.listenerConnected {
                            DisclosureGroup("手机端设置") { phoneSettings(state) }
                                .font(.subheadline).padding(12).elevatedGroup()
                        } else {
                            phoneSettings(state).padding(12).elevatedGroup()
                        }
                    }
                    if let message = notifications.message {
                        StationRows.feedbackBanner(message, busy: notifications.busy)
                    }
                    StationOperationFeedback(station: station)
                    DisclosureGroup("使用说明与显示方式") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Mac 手机工位运行时接收，支持本地与远程连接。断线后不补发旧通知；常驻通知、分组汇总和手机工位自己的提醒会跳过。")
                            Text("应用图标横幅在鼠标停留时保持，点击打开本页。系统通知中心继续留记录；自定义横幅独立于系统专注模式。关闭此项恢复系统横幅，通知预览与声音由 macOS 通知设置管理。")
                        }.font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
                    }.font(.subheadline)
                }.padding(16)
            }
        }.onAppear { notifications.show() }
    }

    private func phoneSettings(_ state: NotificationSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            detail("通知同步", state.enabled ? "已开启" : "未开启")
            detail("通知使用权", state.accessGranted ? "已授权" : "未授权")
            detail("已选应用", "\(state.selectedCount) 个")
            if !state.enabled {
                Button("开启手机端同步") { notifications.configurePhone(true) }
                    .buttonStyle(.bordered).disabled(notifications.busy || !notifications.connected || station.remoteBlocker([.notifications]) != nil)
            }
            Text("在手机工位首页打开「通知 → 手机通知 → Mac」，授予通知使用权并选择应用。电脑提醒 → 手机由手机上的独立开关控制。")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
    private func detail(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(.secondary); Spacer(); Text(value) }.font(.system(size: 13))
    }
}
