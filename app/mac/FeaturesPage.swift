import SwiftUI

struct FeaturesPage: View {
    @ObservedObject var station: Station
    private var groups: [PhoneFeatureGroup] { station.featureGroup.map { [$0] } ?? PhoneFeatureGroup.allCases }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader(station.featureGroup?.title ?? "手机功能管理") { station.goBack(fallback: .more) }
            StationPageScroll(maxHeight: 560) {
                VStack(alignment: .leading, spacing: 14) {
                    Text(station.phoneFeatures.map { $0.master ? "手机工位已开启" : "手机工位已暂停" } ?? "手机功能状态尚未确认")
                        .font(.headline)
                    Text(station.phoneFeatures?.master == false
                         ? "在手机首页点「恢复使用」。功能选择与配对会保留，暂停期间可调整选择。"
                         : "在手机「设置 → 功能管理」中选择功能。服务可用后，仍需对应功能与权限就绪。")
                        .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    if let state = station.phoneFeatures {
                        ForEach(groups, id: \.self) { group in
                            VStack(alignment: .leading, spacing: 6) {
                                if station.featureGroup == nil { StationRows.sectionTitle(group.title) }
                                VStack(alignment: .leading, spacing: 0) {
                                    ForEach(group.keys, id: \.self) { key in
                                        if key != group.keys.first { StationRows.groupDivider }
                                        featureRow(key, state: state)
                                    }
                                }.elevatedGroup()
                            }
                        }
                    }
                    if let group = station.featureGroup {
                        if group == .commands {
                            StationRows.navigationRow("命令与终端", symbol: "terminal", detail: "运行已保存命令，打开交互终端") { station.page = .commands }.elevatedGroup()
                            PhoneAppInstallControls(station: station).elevatedGroup()
                        }
                        if group == .screen {
                            Button("返回首页使用屏幕与控制") { station.page = .main }.buttonStyle(.bordered)
                        }
                        if group != .commands, let blocker = station.remoteBlocker(remoteFeatures(group)) {
                            RemotePermissionHint(station: station, blocker: blocker)
                        }
                    }
                    if station.link.serial != nil {
                        Button("打开手机功能管理") { station.setup.openFeatures(serial: station.link.serial) }
                            .buttonStyle(.bordered).disabled(station.feedback.activity != nil)
                    }
                    Button("重新检查状态") { station.refresh() }.buttonStyle(.borderless)
                    Button("查看远程访问范围") { station.openRemotePermissions() }.buttonStyle(.borderless)
                    StationOperationFeedback(station: station)
                }.padding(.horizontal, 16).padding(.bottom, 16)
            }
        }.onAppear { station.remoteControls.refresh() }
    }

    private func featureRow(_ key: String, state: PhoneFeatureState) -> some View {
        let selected = state.selected[key] == true
        let reason = state.reasons?[key]
        return VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .top) {
                Text(PhoneFeatureState.title(key)).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text(!selected ? "未选择" : state.master ? "已选择" : "已暂停")
                    .foregroundStyle(selected && state.master ? StationPalette.connected : .secondary).fixedSize()
            }.font(.system(size: 13))
            if selected && state.master, let reason, reason != "已开启", reason != "已关闭" {
                Text(reason).font(.caption).foregroundStyle(StationPalette.caution).fixedSize(horizontal: false, vertical: true)
                Button(station.link.serial != nil ? "打开手机权限页" : "查看权限处理方法") {
                    station.setup.openPermissions(serial: station.link.serial)
                }.buttonStyle(.borderless).font(.caption)
            }
        }.padding(12)
    }

    private func remoteFeatures(_ group: PhoneFeatureGroup) -> [RemoteFeature] {
        switch group {
        case .sharing: return [.clipboard, .notifications]
        case .commands: return [.install]
        case .screen: return [.screen, .capture, .controls]
        case .files: return [.fileAccess]
        }
    }
}
