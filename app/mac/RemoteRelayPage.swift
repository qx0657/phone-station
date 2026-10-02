import SwiftUI

struct RemoteRelayPage: View {
    @ObservedObject var station: Station
    @ObservedObject var mcp: McpSession

    private var busy: Bool { mcp.remoteProfileLoading || station.feedback.activity != nil }
    private var canSave: Bool { !busy && (!mcp.configurePhone || station.canOperate) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("远程中继") { station.page = .mcp }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text("此 Mac").font(.system(size: 13, weight: .medium))
                            Spacer()
                            Text(mcp.remoteProfileLoading ? "正在读取…" : mcp.remoteOnline ? "已连接" : mcp.remoteProfile.configured ? "已配置 · 未连通" : "未配置")
                                .font(.system(size: 12))
                                .foregroundStyle(mcp.remoteOnline ? StationPalette.connected : Color.secondary)
                        }
                        Text("通过互联网使用手机的 MCP 工具。两端填写相同的地址和指纹，各自使用不同的令牌。")
                            .font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        field("服务器地址") {
                            TextField("https://relay.example.com", text: $mcp.remoteDraft.endpoint)
                        }
                        field("证书指纹 · SPKI SHA-256") {
                            TextField("64 位十六进制指纹", text: $mcp.remoteDraft.pin)
                        }
                        field("电脑令牌") {
                            SecureField("64 位十六进制令牌", text: $mcp.remoteDraft.desktopToken)
                        }
                        Text("资料由中继管理员提供。令牌安全保存，不回填；查看配置时无需重新输入。")
                            .font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Toggle("同时配置手机", isOn: $mcp.configurePhone)
                            .toggleStyle(.checkbox).font(.subheadline).disabled(busy)
                        if mcp.configurePhone {
                            field("手机令牌") {
                                SecureField("与电脑不同的 64 位十六进制令牌", text: $mcp.remoteDraft.phoneToken)
                            }
                            Text(station.link.serial == nil
                                 ? "请先通过 USB 或无线 adb 连接手机，再保存两端配置。"
                                 : "手机已通过 adb 连接，保存时会同时更新手机并开启 MCP 与远程通道。")
                                .font(.caption).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        } else {
                            Text("只保存此 Mac，无需 adb。手机在「远程中继设置」单独填写手机令牌并连接。")
                                .font(.caption).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 16)
            }
            .frame(height: mcp.configurePhone ? 400 : 316)
            VStack(alignment: .leading, spacing: 10) {
                if let error = mcp.remoteError {
                    Text(error).font(.subheadline).foregroundStyle(StationPalette.recording)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                HStack(spacing: 12) {
                    Button(mcp.configurePhone ? "保存两端配置" : "保存 Mac 配置") { mcp.pairRemote() }
                        .buttonStyle(.borderedProminent).disabled(!canSave)
                        .keyboardShortcut(.defaultAction)
                    Spacer(minLength: 0)
                    if mcp.remoteProfile.configured {
                        Button("清除 Mac 配置…") { mcp.confirmingRemoteRemoval = true }
                            .buttonStyle(.borderless).font(.caption)
                            .foregroundStyle(StationPalette.recording)
                            .disabled(busy).opacity(busy ? 0.4 : 1)
                    }
                }
                if let text = station.feedbackText {
                    StationRows.feedbackBanner(text, busy: station.feedback.activity != nil)
                }
            }.padding(16)
        }
        .onAppear { mcp.refreshRemoteProfile() }
        .onDisappear { mcp.clearRemoteSecrets() }
        .confirmationDialog("清除此 Mac 的远程配置？", isPresented: $mcp.confirmingRemoteRemoval,
                            titleVisibility: .visible) {
            Button("清除 Mac 配置", role: .destructive) { mcp.unpairRemote() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("此 Mac 将停止使用远程中继。手机配置保留，本地 adb 仍可使用。")
        }
    }

    private func field<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 12, weight: .medium))
            content().textFieldStyle(.roundedBorder).font(.system(size: 13))
                .disabled(busy).accessibilityLabel(title)
        }
    }
}
