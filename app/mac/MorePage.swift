import SwiftUI

struct MorePage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("设置") { station.goBack(fallback: .main) }
            StationPageScroll(maxHeight: 560) {
                VStack(alignment: .leading, spacing: 16) {
                    phoneStatus
                    VStack(alignment: .leading, spacing: 6) {
                        StationRows.sectionTitle("手机管理")
                        VStack(spacing: 0) {
                            StationRows.navigationRow("连接设置", symbol: "network") { station.page = .connection }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("MCP 服务", symbol: "point.3.connected.trianglepath.dotted", detail: station.mcpPresentation.summary,
                                                      needsAttention: station.mcpPresentation.needsAttention) { station.page = .mcp }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("手机功能管理", symbol: "switch.2", detail: station.phoneFeatures?.master == false ? "手机工位已暂停" : nil) { station.openFeatures() }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("手机权限", symbol: "checkmark.shield") { station.setup.openPermissions(serial: station.link.serial) }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("远程访问范围", symbol: "lock.shield") { station.openRemotePermissions() }
                        }.elevatedGroup()
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        StationRows.sectionTitle("高级工具")
                        VStack(spacing: 0) {
                            StationRows.navigationRow("命令与终端", symbol: "terminal") { station.page = .commands }
                            StationRows.groupDivider.padding(.leading, 38)
                            PhoneAppInstallControls(station: station)
                        }.elevatedGroup()
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        StationRows.sectionTitle("这台 Mac")
                        VStack(spacing: 0) {
                            StationRows.toggleRow("开机自启", symbol: "desktopcomputer", subtitle: station.login.subtitle, isOn: loginBinding, enabled: true)
                            if station.login.needsApproval {
                                Button("打开系统设置") { station.login.openSettings() }.buttonStyle(.borderless)
                                    .padding(.leading, 38).padding(.bottom, 8).frame(maxWidth: .infinity, alignment: .leading)
                            }
                            StationRows.groupDivider.padding(.leading, 38)
                            StationRows.navigationRow("关于", symbol: "info.circle", detail: "版本 \(StationRows.appVersion())") { station.page = .about }
                        }.elevatedGroup()
                    }
                    DisclosureGroup("本机工具检查") {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(station.login.dependencyDetails, id: \.0) { item in
                                HStack(spacing: 8) {
                                    Image(systemName: item.1 ? "checkmark.circle.fill" : "exclamationmark.circle")
                                        .foregroundStyle(item.1 ? StationPalette.connected : StationPalette.caution).accessibilityHidden(true)
                                    Text(item.0)
                                    Spacer(minLength: 8)
                                    Text(item.1 ? "已找到" : "未找到").foregroundStyle(.secondary)
                                }.font(.subheadline)
                            }
                            Text("会话提醒按项目说明安装一次。手机文件通过 MCP 服务访问。")
                                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }.padding(.top, 8)
                    }.font(.subheadline)
                    StationOperationFeedback(station: station)
                    Button("退出手机工位") { station.quit() }.buttonStyle(.borderless)
                }.padding(.horizontal, 16).padding(.bottom, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }.onAppear { station.login.refresh() }
    }

    @ViewBuilder private var phoneStatus: some View {
        if station.phoneFeatures?.master != false && (!station.health.issues.isEmpty || station.health.failure != nil) {
            VStack(alignment: .leading, spacing: 8) {
                StationRows.sectionTitle("手机权限与状态")
                if !station.health.issues.isEmpty {
                    Group {
                        if station.health.issues.count > 1 {
                            ScrollView { issueRows }.frame(height: 190)
                        } else { issueRows }
                    }.padding(12).elevatedGroup()
                }
                if let failure = station.health.failure {
                    Text(failure).font(.caption).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var issueRows: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(station.health.issues) { issue in
                Button { openIssue(issue) } label: {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "exclamationmark.triangle")
                            .foregroundStyle(StationPalette.caution).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(issue.title).font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(StationPalette.caution)
                            Text(issue.detail).font(.caption).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            Text(issue.destination == "permissions" && station.link.serial != nil
                                 ? "打开手机权限页" : "查看处理方法")
                                .font(.caption).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.right").font(.caption)
                            .foregroundStyle(.secondary).accessibilityHidden(true)
                    }.contentShape(Rectangle())
                }.buttonStyle(PressFadeStyle())
            }
        }
    }

    private func openIssue(_ issue: DeviceIssue) {
        switch issue.destination {
        case "permissions": station.setup.openPermissions(serial: station.link.serial)
        case "clipboard": station.page = .clipboard
        case "notifications": station.page = .notifications
        case "remoteRelay": station.page = .remoteRelay
        case "remotePermissions": station.openRemotePermissions(["personal"])
        default: station.page = .mcp
        }
    }

    private var loginBinding: Binding<Bool> {
        Binding(get: { station.login.opensAtLogin }, set: { station.login.setOpensAtLogin($0) })
    }
}

struct AboutPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("关于") { station.goBack(fallback: .more) }
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .center, spacing: 12) {
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 44, height: 44)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("手机工位")
                            .font(.system(size: 16, weight: .semibold))
                        Text("版本 \(StationRows.appVersion())")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }
                Text("从菜单栏连接这台手机，掉线后自动重连，配对、安装手机上的应用、打开 MCP，投屏、截取画面、录屏，控制亮屏和闪光灯，并运行保存的 adb 命令。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("打开项目说明") { station.login.openGuide() }
                    .buttonStyle(.bordered)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
