import SwiftUI

struct StationPairPage: View {
    @ObservedObject var station: Station
    @StationViewState private var manual = ""
    @StationViewState private var notice = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader(StationL10n.text("工位配对")) { station.goBack(fallback: .connection) }
            StationPageScroll(maxHeight: 420) {
                VStack(alignment: .leading, spacing: 12) {
                    Text(StationL10n.text("本地链路直接交换公钥。没有 adb 时，手机用系统相机拍照后在本地解码，也可以手动输入 12 位邀请码。应用不申请相机权限。"))
                        .font(.subheadline).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(StationL10n.text("无线调试自己的配对二维码仍在连接设置里。邀请登记和加入中继要等中继 6，这一页不拨公网。"))
                        .font(.subheadline).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    TextField(StationL10n.text("12 位邀请码"), text: $manual)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel(StationL10n.text("12 位邀请码"))
                    Button(StationL10n.text("核对邀请码")) {
                        notice = StationInvite.explain(manual)
                    }.buttonStyle(.bordered)
                    if !notice.isEmpty {
                        Text(StationL10n.text(notice))
                            .font(.subheadline)
                            .foregroundStyle(notice.contains("格式正确") ? StationPalette.connected : StationPalette.recording)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityAddTraits(.updatesFrequently)
                    }
                }
                .padding(16)
            }
        }
    }
}
