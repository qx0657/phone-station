import SwiftUI

struct MorePage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("更多工具与设置") { station.page = .main }
            VStack(alignment: .leading, spacing: 16) {
                VStack(spacing: 0) {
                    StationRows.toggleRow("开机自启", symbol: "desktopcomputer",
                                          subtitle: station.login.subtitle,
                                          isOn: loginBinding, enabled: true)
                    if station.login.needsApproval {
                        Button("打开系统设置") { station.login.openSettings() }
                            .buttonStyle(.borderless)
                            .font(.subheadline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.leading, 38)
                            .padding(.trailing, 10)
                            .padding(.bottom, 8)
                    }
                    StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
                    Button {
                        station.setup.install()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "arrow.down.app")
                                .frame(width: 18)
                                .foregroundStyle(.secondary)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("安装或更新手机应用")
                                Text("已有远程通道时直接更新，首次安装走 adb")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 8)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressFadeStyle())
                    .disabled(station.feedback.activity != nil)
                    .opacity(station.feedback.activity == nil ? 1 : 0.4)
                    StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
                    if let id = station.setup.installJobID {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("上次安装任务").font(.caption).foregroundStyle(.secondary)
                            Text(id).font(.caption.monospaced()).textSelection(.enabled)
                            HStack {
                                Button("查询原任务") { station.setup.queryInstall() }
                                    .disabled(station.feedback.activity != nil)
                                Button("复制编号") { station.setup.copyInstallJobID() }
                            }.buttonStyle(.borderless)
                        }.padding(10)
                        StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
                    }
                    StationRows.navigationRow("手机权限", symbol: "checkmark.shield",
                                              detail: "查看手机上的权限设置") {
                        station.setup.openPermissions(serial: station.link.serial)
                    }
                    StationRows.groupDivider.padding(.leading, 38).padding(.trailing, 10)
                    StationRows.navigationRow("关于", symbol: "info.circle", detail: "版本 \(StationRows.appVersion())") {
                        station.page = .about
                    }
                }
                .elevatedGroup()

                phoneStatus

                VStack(alignment: .leading, spacing: 6) {
                    StationRows.sectionTitle("本机工具")
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(station.login.dependencyDetails, id: \.0) { item in
                            HStack(spacing: 8) {
                                Image(systemName: item.1 ? "checkmark.circle.fill" : "exclamationmark.circle")
                                    .foregroundStyle(item.1 ? StationPalette.connected : StationPalette.caution)
                                    .accessibilityHidden(true)
                                Text(item.0)
                                Spacer(minLength: 8)
                                Text(item.1 ? "已找到" : "未找到")
                                    .foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                        }
                    }
                    .padding(.horizontal, 2)
                    Text("会话提醒仍按项目说明安装一次。文件走 MCP服务。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 2)
                }
                if let text = station.feedbackText {
                    StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                }
                Button {
                    station.quit()
                } label: {
                    Text("退出手机工位")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .padding(.vertical, 4)
                }
                .buttonStyle(PressFadeStyle())
                .background { HoverWash(radius: 8) }
                .font(.subheadline)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { station.login.refresh() }
    }

    @ViewBuilder private var phoneStatus: some View {
        if !station.health.issues.isEmpty || station.health.failure != nil || station.remoteControls.reason != nil {
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
                if station.link.serial == nil, let reason = station.remoteControls.reason {
                    Text(reason).font(.caption).foregroundStyle(.secondary)
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
            StationRows.subpageHeader("关于") { station.page = .more }
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
