import SwiftUI

struct RemoteRelayPage: View {
    @ObservedObject var station: Station
    @ObservedObject var mcp: McpSession
    @StationViewState private var editingConfiguration: Bool = false

    private var showsConfiguration: Bool { editingConfiguration || (!mcp.remoteProfileLoading && !mcp.remoteProfile.configured) || mcp.remoteError != nil }

    private var busy: Bool { mcp.remoteProfileLoading || station.feedback.activity != nil }
    private var canSave: Bool { !busy && (!mcp.configurePhone || station.canOperate) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader(StationL10n.text("远程接入")) { station.goBack(fallback: .mcp) }
            StationPageScroll(maxHeight: showsConfiguration ? (mcp.configurePhone ? 400 : 316) : 160) {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(StationL10n.text("此 Mac")).font(.system(size: 13, weight: .medium))
                            Spacer()
                            Text(StationL10n.text(mcp.remoteProfileLoading ? "正在读取…" : mcp.remoteConfigured == nil && !mcp.remoteProfile.configured ? "未配置" : mcp.remoteStatusLabel))
                                .font(.system(size: 12))
                                .foregroundStyle(mcp.remoteOnline ? StationPalette.connected : mcp.remoteChecking ? StationPalette.caution : Color.secondary)
                        }
                        Text(StationL10n.text("通过互联网使用手机工具；两端使用相同的地址和指纹，以及各自的令牌。"))
                            .font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                                                Button(StationL10n.text("查看远程访问范围与处理步骤")) { station.openRemotePermissions() }
                            .buttonStyle(.borderless)
                    }
                    if showsConfiguration {
                        VStack(alignment: .leading, spacing: 12) {
                            field(StationL10n.text("服务器地址")) {
                                TextField("https://relay.example.com", text: $mcp.remoteDraft.endpoint)
                            }
                            field(StationL10n.text("证书指纹 · SPKI SHA-256")) {
                                TextField(StationL10n.text("64 位十六进制指纹"), text: $mcp.remoteDraft.pin)
                            }
                            field(StationL10n.text("电脑令牌")) {
                                SecureField(StationL10n.text("64 位十六进制令牌"), text: $mcp.remoteDraft.desktopToken)
                            }
                            Text(StationL10n.text("资料由中继管理员提供。令牌安全保存，不回填；查看配置时无需重新输入。"))
                                .font(.caption).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Toggle(StationL10n.text("同时配置手机"), isOn: $mcp.configurePhone)
                                .toggleStyle(.checkbox).font(.subheadline).disabled(busy)
                            if mcp.configurePhone {
                                field(StationL10n.text("手机令牌")) {
                                    SecureField(StationL10n.text("与电脑不同的 64 位十六进制令牌"), text: $mcp.remoteDraft.phoneToken)
                                }
                                Text(station.link.serial == nil
                                     ? StationL10n.text("请先通过 USB 或无线 adb 连接手机，再保存两端配置。")
                                     : StationL10n.text("手机已通过 adb 连接，保存时会同时更新手机并开启 MCP 与远程通道。"))
                                    .font(.caption).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                        } else {
                            Text(StationL10n.text("只保存此 Mac，无需 adb。手机在「远程中继设置」单独填写手机令牌并连接。"))
                                .font(.caption).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    } else {
                        Button(StationL10n.text("修改连接配置")) { editingConfiguration = true }.buttonStyle(.bordered)
                    }
                }
                .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 16)
            }
            VStack(alignment: .leading, spacing: 10) {
                if let error = mcp.remoteError {
                    Text(StationL10n.text(error)).font(.subheadline).foregroundStyle(StationPalette.recording)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                HStack(spacing: 12) {
                    if showsConfiguration {
                        Button(mcp.configurePhone ? StationL10n.text("保存两端配置") : StationL10n.text("保存 Mac 配置")) { mcp.pairRemote() }
                            .buttonStyle(.borderedProminent).disabled(!canSave)
                            .keyboardShortcut(.defaultAction)
                    }
                    if editingConfiguration && mcp.remoteProfile.configured && mcp.remoteError == nil {
                        Button(StationL10n.text("收起")) { editingConfiguration = false; mcp.clearRemoteSecrets() }
                            .buttonStyle(.borderless).disabled(busy)
                    }
                    Spacer(minLength: 0)
                    if mcp.remoteProfile.configured {
                        Button(StationL10n.text("清除 Mac 配置…")) { station.dialog = .removeRemote }
                            .buttonStyle(.borderless).font(.caption)
                            .foregroundStyle(StationPalette.recording)
                            .disabled(busy).opacity(busy ? 0.4 : 1)
                    }
                }
                StationOperationFeedback(station: station)
            }.padding(16)
        }
        .onAppear { mcp.refreshRemoteProfile() }
        .onDisappear { mcp.clearRemoteSecrets() }
    }

    private func field<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(StationL10n.text(title)).font(.system(size: 12, weight: .medium))
            content().textFieldStyle(.roundedBorder).font(.system(size: 13))
                .disabled(busy).accessibilityLabel(title)
        }
    }
}
