import AppKit
import Foundation

@MainActor
final class McpSession: ObservableObject {
    @Published private(set) var listening = false
    @Published private(set) var requestAvailable = false
    @Published private(set) var endpoint = ""
    @Published private(set) var token = ""
    @Published private(set) var channel = ""
    @Published private(set) var copied = false
    @Published private(set) var gatewayReady = false
    @Published private(set) var localOnline = false
    @Published private(set) var remoteOnline = false
    @Published private(set) var remoteChecking = false
    @Published private(set) var remoteConfigured: Bool?
    @Published var configurePhone = false
    @Published private(set) var remoteProfile = RemoteRelayProfile()
    @Published private(set) var remoteProfileLoading = false
    @Published var remoteDraft = RemoteRelayDraft()
    @Published private(set) var remoteError: String?

    var onRemoteConnection: (Bool) -> Void = { _ in }
    var onRemoteChecking: (Bool) -> Void = { _ in }
    var onLocalOnline: (Bool) -> Void = { _ in }
    var onRoute: () -> Void = {}
    var allowRemotePair: () -> Bool = { false }
    var wantsLocal: () -> Bool = { true }

    private let feedback: StationFeedback
    private var generation = 0
    private var statusInFlight = false
    private var statusTimer: Timer?
    private var statusStream: ConnectionStream?
    private var streamedAt = Date.distantPast
    private var routeInFlight = false
    private var routeDirty = false
    private var lastRouteRefresh = Date.distantPast
    private var lastSerial: String?
    private var localMcpOn: Bool?
    private var routeRetry: Task<Void, Never>?
    private var routeRetryStep = 0
    private var copyFeedback: Task<Void, Never>?

    init(feedback: StationFeedback) {
        self.feedback = feedback
        let timer = Timer(timeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, Date().timeIntervalSince(self.streamedAt) > 5 else { return }
                self.refreshStatus()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        statusTimer = timer
    }

    func startWatch() {
        guard statusStream == nil else { return }
        let stream = ConnectionStream(executable: URL(fileURLWithPath: "/bin/zsh"),
                                      arguments: [StationRunner.script("mcp.sh").path, "watch"])
        stream.onFrame = { [weak self] output in
            guard let self else { return }
            self.streamedAt = Date()
            self.apply(output)
        }
        stream.onDisconnect = { [weak self] in
            self?.streamedAt = .distantPast
            self?.apply("off")
            self?.refreshStatus()
        }
        statusStream = stream
        stream.start()
        refreshStatus()
    }

    var remoteStatusLabel: String {
        if remoteOnline { return "已连接" }
        if remoteChecking { return "确认连接中" }
        if remoteConfigured == false { return "未配置" }
        if !gatewayReady { return "网关未启动" }
        return "未连通 · 自动检测"
    }

    var summary: String {
        remoteChecking && !listening ? "确认连接中" : listening ? (channel == "remote" ? "远程中继" : "本地 adb") : (gatewayReady ? "等待手机" : "未启动")
    }

    func stopWatch() {
        statusStream?.stop(); statusStream = nil
        statusTimer?.invalidate(); statusTimer = nil
    }

    func noteSerial(_ serial: String?) {
        let changed = serial != lastSerial
        lastSerial = serial
        if changed {
            routeRetry?.cancel()
            routeRetryStep = 0
            refreshRoute()
        }
        refreshStatus()
    }

    /// 本机 MCP 从关到开、序列号还在、本地仍不在线时才补一次转发刷新。
    /// 关着的时候退避不会启动，重新打开也不会再走「从在线变成离线」那条边。
    nonisolated static func shouldRefreshAfterLocalMcp(previous: Bool?, enabled: Bool?, serial: String?, localOnline: Bool) -> Bool {
        previous == false && enabled == true && serial?.isEmpty == false && !localOnline
    }

    /// 记下本机 MCP 开关。只有从关到开且本地仍不在线时刷新转发。
    func noteLocalMcp(_ enabled: Bool?) {
        let previous = localMcpOn
        if let enabled {
            localMcpOn = enabled
        }
        guard Self.shouldRefreshAfterLocalMcp(previous: previous, enabled: enabled,
                                               serial: lastSerial, localOnline: localOnline) else { return }
        refreshRoute()
    }

    /// 刷新转发后本地仍不在线时的下一次间隔。手机刚恢复时读令牌可能超过 2 秒上限。
    nonisolated static func routeRetrySeconds(step: Int) -> Double {
        let delays: [Double] = [5, 10, 20, 30]
        return delays[min(max(step, 0), delays.count - 1)]
    }

    /// adb 仍连着同一台手机、本机 MCP 开着、本地却不在线时，退避后再刷新一次转发。已有等待不重置。
    private func scheduleRouteRetry() {
        guard routeRetry == nil else { return }
        guard let serial = lastSerial, !localOnline, wantsLocal() else { return }
        let delay = Self.routeRetrySeconds(step: routeRetryStep)
        routeRetryStep += 1
        routeRetry = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            self.routeRetry = nil
            guard self.lastSerial == serial, !self.localOnline, self.wantsLocal() else { return }
            self.refreshRoute()
        }
    }

    /// 序列号变化，或网关/手机返回 401 时，才刷新 adb 转发和手机令牌。
    func noteUnauthorized() {
        guard Date().timeIntervalSince(lastRouteRefresh) >= 5 else { return }
        refreshRoute()
    }

    private func refreshRoute() {
        if routeInFlight { routeDirty = true; return }
        routeInFlight = true
        lastRouteRefresh = Date()
        Task.detached(priority: .utility) {
            _ = StationRunner.scriptResult("mcp.sh", ["status"], timeout: 15)
            await MainActor.run {
                self.routeInFlight = false
                self.refreshStatus()
                if self.routeDirty {
                    self.routeDirty = false
                    self.lastRouteRefresh = .distantPast
                    self.refreshRoute()
                } else {
                    self.scheduleRouteRetry()
                }
            }
        }
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
        let startedAt = Date()
        Task.detached(priority: .utility) {
            let result = StationRunner.scriptResult("mcp.sh", ["snapshot"], timeout: 2)
            await MainActor.run {
                self.statusInFlight = false
                guard ticket == self.generation, self.streamedAt <= startedAt else { return }
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
        if endpoint != status.endpoint { endpoint = status.endpoint }
        if token != status.token { token = status.token }
        if channel != status.channel { channel = status.channel }
        if listening != status.listening { listening = status.listening }
        if requestAvailable != status.requestAvailable { requestAvailable = status.requestAvailable }
        if gatewayReady != status.gatewayReady { gatewayReady = status.gatewayReady }
        let local = status.localOnline ?? (status.channel == "local")
        if localOnline != local {
            localOnline = local
            if local {
                routeRetry?.cancel()
                routeRetry = nil
                routeRetryStep = 0
            } else {
                scheduleRouteRetry()
            }
            onLocalOnline(local)
        }
        if remoteOnline != status.remoteConnected { remoteOnline = status.remoteConnected }
        if remoteChecking != status.remoteChecking { remoteChecking = status.remoteChecking }
        if remoteConfigured != status.remoteConfigured { remoteConfigured = status.remoteConfigured }
        // 启动脚本只返回访问地址和令牌，接着读 status 才有通道探测结果。
        if !status.listening || !status.channel.isEmpty {
            onRemoteConnection(status.remoteConnected)
            onRemoteChecking(status.remoteChecking)
        }
        onRoute()
    }
}
