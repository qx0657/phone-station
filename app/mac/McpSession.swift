import AppKit
import Foundation

@MainActor
final class McpSession: ObservableObject {
    @Published private(set) var listening = false
    @Published private(set) var endpoint = ""
    @Published private(set) var token = ""
    @Published private(set) var channel = ""
    @Published private(set) var copied = false
    @Published private(set) var gatewayReady = false
    @Published private(set) var localOnline = false
    @Published private(set) var remoteOnline = false
    @Published var configurePhone = false
    @Published var confirmingRemoteRemoval = false
    @Published private(set) var remoteProfile = RemoteRelayProfile()
    @Published private(set) var remoteProfileLoading = false
    @Published var remoteDraft = RemoteRelayDraft()
    @Published private(set) var remoteError: String?

    var onRemoteConnection: (Bool) -> Void = { _ in }
    var onLocalRouteUnavailable: () -> Void = {}
    var allowRemotePair: () -> Bool = { false }

    private let feedback: StationFeedback
    private var generation = 0
    private var statusInFlight = false
    private var statusTimer: Timer?
    private var routeInFlight = false
    private var routeDirty = false
    private var lastRouteRefresh = Date.distantPast
    private var lastSerial: String?
    private var copyFeedback: Task<Void, Never>?

    init(feedback: StationFeedback) {
        self.feedback = feedback
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshStatus() }
        }
        RunLoop.main.add(timer, forMode: .common)
        statusTimer = timer
    }

    var summary: String {
        listening ? (channel == "remote" ? "远程中继" : "本地 adb") : (gatewayReady ? "等待手机" : "未启动")
    }

    func noteSerial(_ serial: String?) {
        let changed = serial != lastSerial
        lastSerial = serial
        if routeInFlight && changed { routeDirty = true }
        if !routeInFlight && (changed || Date().timeIntervalSince(lastRouteRefresh) >= 5) {
            routeInFlight = true
            lastRouteRefresh = Date()
            Task.detached(priority: .utility) {
                _ = StationRunner.scriptResult("mcp.sh", ["status"], timeout: 15)
                await MainActor.run {
                    self.routeInFlight = false
                    if self.routeDirty {
                        self.routeDirty = false
                        self.lastRouteRefresh = .distantPast
                        self.noteSerial(self.lastSerial)
                    }
                    self.refreshStatus()
                }
            }
        }
        refreshStatus()
    }

    func start() {
        guard feedback.activity == nil else { return }
        feedback.activity = "正在打开 MCP服务…"
        feedback.notice = nil
        generation += 1
        let ticket = generation
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("mcp.sh", timeout: 40)
            await MainActor.run {
                guard ticket == self.generation else { return }
                self.feedback.activity = nil
                if result.succeeded {
                    self.apply(result.output)
                    self.feedback.notice = "MCP服务已打开。"
                    self.refreshStatus()
                } else {
                    self.feedback.notice = result.timedOut
                        ? "MCP服务没有在时限内应答。"
                        : StationText.reason(result.output, fallback: "无法打开 MCP服务。")
                }
            }
        }
    }

    func stop() {
        guard feedback.activity == nil else { return }
        feedback.activity = "正在停止 MCP服务…"
        feedback.notice = nil
        generation += 1
        let ticket = generation
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("mcp.sh", ["stop"], timeout: 20)
            await MainActor.run {
                guard ticket == self.generation else { return }
                self.feedback.activity = nil
                if result.succeeded {
                    self.apply("off")
                    self.feedback.notice = "MCP服务已停止。"
                } else {
                    self.feedback.notice = StationText.reason(result.output, fallback: "无法停止 MCP服务。")
                }
            }
        }
    }

    func copyToken() {
        guard !token.isEmpty else { return }
        let board = NSPasteboard.general
        board.clearContents()
        board.setString("Authorization: Bearer \(token)", forType: .string)
        board.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        copied = true
        copyFeedback?.cancel()
        copyFeedback = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            self.copied = false
        }
    }

    func copyEndpoint() {
        guard !endpoint.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(endpoint, forType: .string)
    }

    func refreshRemoteProfile(resetDraft: Bool = true) {
        guard !remoteProfileLoading else { return }
        remoteProfileLoading = true
        remoteError = nil
        Task.detached(priority: .utility) {
            let result = StationRunner.scriptResult("mcp.sh", ["profile"], timeout: 3)
            let profile = try? JSONDecoder().decode(RemoteRelayProfile.self, from: Data(result.output.utf8))
            await MainActor.run {
                self.remoteProfileLoading = false
                if result.succeeded, let profile {
                    self.remoteProfile = profile
                    if resetDraft {
                        self.remoteDraft.endpoint = profile.endpoint
                        self.remoteDraft.pin = profile.pin
                    }
                } else {
                    self.remoteError = "无法读取远程配置，请检查本机网关是否已构建。"
                }
            }
        }
    }

    func clearRemoteSecrets() {
        remoteDraft.phoneToken = ""
        remoteDraft.desktopToken = ""
    }

    func pairRemote() {
        let both = configurePhone
        guard feedback.activity == nil, !remoteProfileLoading, !both || allowRemotePair() else { return }
        let draft = remoteDraft.normalized
        if let message = draft.validationMessage(configurePhone: both) {
            remoteError = message
            return
        }
        let input = both ? draft.credentialInput : draft.desktopCredentialInput
        let arguments = [both ? "pair" : "desktop", draft.endpoint, draft.pin, "--stdin"]
        clearRemoteSecrets()
        remoteError = nil
        feedback.activity = both ? "正在配置 Mac 和手机…" : "正在保存 Mac 配置…"
        feedback.notice = nil
        generation += 1
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("mcp.sh", arguments, timeout: 45, input: input)
            await MainActor.run {
                self.feedback.activity = nil
                self.feedback.notice = result.succeeded ? (both ? "两端配置已保存，请查看连接状态。" : "Mac 配置已保存，正在检测远程连接。")
                    : StationText.reason(result.output, fallback: "配对没有完成，请检查中继地址和凭据。")
                self.refreshRemoteProfile(resetDraft: result.succeeded)
                self.refreshStatus()
            }
        }
    }

    func unpairRemote() {
        guard feedback.activity == nil, !remoteProfileLoading else { return }
        clearRemoteSecrets()
        remoteError = nil
        feedback.activity = "正在清除 Mac 配置…"
        feedback.notice = nil
        generation += 1
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("mcp.sh", ["forget-desktop"], timeout: 20)
            await MainActor.run {
                self.feedback.activity = nil
                self.feedback.notice = result.succeeded ? "Mac 的远程配置已清除，手机配置保留。"
                    : StationText.reason(result.output, fallback: "无法清除 Mac 的远程配置。")
                self.refreshRemoteProfile()
                self.refreshStatus()
            }
        }
    }

    private func refreshStatus() {
        guard !statusInFlight else { return }
        statusInFlight = true
        let ticket = generation
        Task.detached(priority: .utility) {
            let result = StationRunner.scriptResult("mcp.sh", ["snapshot"], timeout: 2)
            await MainActor.run {
                self.statusInFlight = false
                guard ticket == self.generation else { return }
                if result.succeeded {
                    self.apply(result.output)
                } else {
                    self.apply("off")
                }
            }
        }
    }

    private func apply(_ output: String) {
        let status = McpStatus(output)
        endpoint = status.endpoint
        token = status.token
        channel = status.channel
        listening = status.listening
        gatewayReady = status.gatewayReady
        localOnline = status.localOnline ?? (status.channel == "local")
        remoteOnline = status.remoteConnected
        // 启动脚本只返回访问地址和令牌，接着读 status 才有通道探测结果。
        if !status.listening || !status.channel.isEmpty {
            onRemoteConnection(status.remoteConnected)
            if status.channel == "remote" && status.localOnline == false {
                onLocalRouteUnavailable()
            }
        }
    }
}
