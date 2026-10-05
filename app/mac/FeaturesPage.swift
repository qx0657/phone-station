import SwiftUI

struct FeaturesPage: View {
    @ObservedObject var station: Station
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("手机功能管理") { station.goBack(fallback: .more) }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(station.phoneFeatures.map { $0.master ? "手机工位已开启" : "手机工位已暂停" } ?? "手机开关状态尚未确认")
                        .font(.headline)
                    Text("在手机「设置 → 功能管理」中选择功能。在手机首页暂停或恢复使用，功能选择与配对都会保留。")
                        .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    if let state = station.phoneFeatures {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(PhoneFeatureState.keys, id: \.self) { key in
                                if key != PhoneFeatureState.keys.first { StationRows.groupDivider }
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(alignment: .top) {
                                        Text(PhoneFeatureState.title(key))
                                        Spacer(minLength: 8)
                                        Text(state.selected[key] == true ? "已开启" : "已关闭")
                                            .foregroundStyle(state.selected[key] == true ? StationPalette.connected : .secondary)
                                    }.font(.system(size: 13))
                                    if let reason = state.reasons?[key], reason != "已开启", reason != "已关闭" {
                                        Text(reason).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                    }
                                }.padding(12)
                            }
                        }.elevatedGroup()
                    }
                    if station.link.serial != nil {
                        Button("打开手机功能管理") { station.setup.openFeatures(serial: station.link.serial) }
                            .buttonStyle(.bordered).disabled(station.feedback.activity != nil)
                    }
                    Button("重新检查状态") { station.refresh() }.buttonStyle(.borderless)
                    Button("查看远程访问范围") { station.openRemotePermissions() }.buttonStyle(.borderless)
                    Text("开关表示选择，权限表示是否就绪。缺少权限会显示处理入口；恢复后只接收新事件，安装、命令和截屏需重新操作。")
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    if let text = station.feedbackText { StationRows.feedbackBanner(text, busy: station.feedback.activity != nil) }
                }.padding(16)
            }.frame(height: min(560, max(280, (NSScreen.main?.visibleFrame.height ?? 900) - 96)))
        }.onAppear { station.remoteControls.refresh() }
    }
}
