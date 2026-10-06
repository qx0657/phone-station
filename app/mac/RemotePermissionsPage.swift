import SwiftUI

struct RemotePermissionHint: View {
    @ObservedObject var station: Station
    let blocker: RemotePermissionBlocker
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(StationL10n.text(blocker.title)).font(.subheadline)
            Text(StationL10n.text(blocker.detail)).font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if blocker.canResolve {
                Button(StationL10n.text("去授权")) { station.openRemotePermissions(blocker.scopes) }
                    .buttonStyle(.bordered)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct RemotePermissionsPage: View {
    @ObservedObject var station: Station
    private var state: RemoteAccessState { station.remoteControls.accessState }
    private var modernPage: Bool { station.remoteControls.reading?.remotePermissionPageVersion == 1 }
    private var legacyPage: Bool { station.remoteControls.reading != nil && !modernPage }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader(StationL10n.text("远程访问范围")) { station.goBack(fallback: station.permissionReturnPage) }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(StationL10n.text(summary)).font(.subheadline)
                    Text(StationL10n.text("由手机端分别授权。Mac 可查看状态和处理步骤；未开启的能力保持关闭，不影响本地 adb 的原有权限。"))
                        .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    if !station.permissionScopes.isEmpty {
                        Text(StationL10n.text("本次需要：") + station.permissionScopes.map { StationL10n.text(RemoteAccessState.title($0)) }.joined(separator: StationL10n.english() ? ", " : "、"))
                            .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(RemoteAccessState.scopes, id: \.self) { scope in
                            if scope != RemoteAccessState.scopes.first { StationRows.groupDivider }
                            HStack(alignment: .top, spacing: 8) {
                                Text(StationL10n.text(RemoteAccessState.title(scope))).frame(maxWidth: .infinity, alignment: .leading)
                                Text(state.permissions?[scope] == true ? StationL10n.text("已允许") : state.permissions == nil ? StationL10n.text("未确认") : StationL10n.text("未允许"))
                                    .foregroundStyle(state.permissions?[scope] == true ? StationPalette.connected : .secondary)
                            }.font(.system(size: 13)).padding(12)
                        }
                    }.elevatedGroup()
                    Text(legacyPage ? StationL10n.text("当前手机版本：在首页「MCP 服务 → 远程通道 → 远程访问范围」中按需开启对应项。新版入口位于「权限与检查」。")
                         : StationL10n.text("在手机「设置」打开「远程访问范围」，按需开启对应项。"))
                        .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                    if station.link.serial != nil && modernPage {
                        Button(StationL10n.text("打开手机远程访问范围页")) {
                            station.setup.openRemotePermissions(serial: station.link.serial, scope: station.permissionScopes.first, modernPage: modernPage)
                        }.buttonStyle(.bordered).disabled(station.feedback.activity != nil)
                    }
                    Button(StationL10n.text("重新检查授权")) { station.remoteControls.refresh() }.buttonStyle(.borderless)
                    if !station.mcp.requestAvailable {
                        Button(StationL10n.text("查看 MCP 服务")) { station.page = .mcp }.buttonStyle(.bordered)
                    }
                    Text(StationL10n.text("授权后会重新检查状态。安装、命令、截屏等操作需重新点击，不会自动补执行。"))
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    if let text = station.feedbackText { StationRows.feedbackBanner(text, busy: station.feedback.activity != nil) }
                }.padding(16)
            }.frame(height: min(560, max(280, (NSScreen.main?.visibleFrame.height ?? 900) - 96)))
        }.onAppear { station.remoteControls.refresh() }
    }
    private var summary: String {
        if !station.mcp.requestAvailable { return StationL10n.text("连接 MCP 服务后查看手机授权") }
        if let failure = state.failure { return failure }
        guard state.checked else { return StationL10n.text("正在检查手机授权…") }
        guard let permissions = state.permissions else { return StationL10n.text("手机版本尚未提供分项权限状态") }
        let count = RemoteAccessState.scopes.filter { permissions[$0] == true }.count
        return count == 0 ? StationL10n.text("仅允许状态查询") : StationL10n.format("已允许 {0}/6 项", "\(count)")
    }
}
