import AppKit
import Combine
import Foundation

/// 各页各自的状态在对应的 session 里。这里只负责把它们接上，并在页面之间跳转。
@MainActor
final class Station: ObservableObject {
    let feedback: StationFeedback
    let appearance = StationAppearance.shared
    let link: LinkSession
    let screen: ScreenSession
    let torch: TorchSession
    let files: RecentFilesSession
    let commands: CommandSession
    let login: LoginSession
    let mcp: McpSession
    let setup: SetupSession
    let clipboard: ClipboardSession
    let notifications: NotificationSession
    let remoteEvents = RemoteEventSession()
    let localEvents = LocalEventSession()
    let health: DeviceHealthSession
    let remoteControls: RemoteControlsSession
    let pairing = WirelessPairingSession()
    private var navigation = StationNavigation()
    @Published var panelVisible = false {
        didSet { surfaceVisibilityChanged(opened: panelVisible) }
    }
    /// 设备、客户端、中继和权限在设置窗口里，不占用菜单栏快捷面板。
    @Published var settingsOpen = false {
        didSet { surfaceVisibilityChanged(opened: settingsOpen) }
    }
    var surfaceVisible: Bool { panelVisible || settingsOpen }
    @Published var dialog: StationDialogKind?
    @Published var page: StationPage = .main {
        didSet {
            bindPage()
            navigation.changed(from: oldValue, to: page)
            if oldValue != page { dialog = nil }
            if oldValue != page && feedback.activity == nil { feedback.notice = nil }
        }
    }
    var backDestination: StationPage {
        navigation.destination(fallback: page == .remotePermissions ? permissionReturnPage : page.backFallback)
    }
    var backPageTitle: String { backDestination == .main ? StationL10n.text("首页") : title(for: backDestination) }
    var pageTitle: String { title(for: page) }
    private func title(for target: StationPage) -> String {
        if target == .features, let featureGroup { return StationL10n.text(featureGroup.title) }
        if target == .editCommand { return StationL10n.text(commands.commandDraft.id == nil ? "新建命令" : "编辑命令") }
        return StationL10n.text(target.title)
    }
    var windowTitle: String { pageTitle + " · " + StationL10n.text("手机工位") }
    func goBack() { page = backDestination }
    @Published private(set) var featureGroup: PhoneFeatureGroup?
    func openFeatures(_ group: PhoneFeatureGroup? = nil) {
        featureGroup = group
        page = .features
    }
    @Published private(set) var permissionScopes: [String] = []
    private(set) var permissionReturnPage: StationPage = .more
    func openRemotePermissions(_ scopes: [String] = []) {
        if page != .remotePermissions { permissionReturnPage = page }
        permissionScopes = scopes.filter { RemoteAccessState.scopes.contains($0) }
        page = .remotePermissions
        remoteControls.refresh()
    }
    func remoteBlocker(_ features: [RemoteFeature]) -> RemotePermissionBlocker? {
        guard mcp.channel == "remote" else { return nil }
        return remoteControls.accessState.blocker(for: features)
    }

    private var pageWatch = Set<AnyCancellable>()

    init() {
        let feedback = StationFeedback()
        self.feedback = feedback
        link = LinkSession(feedback: feedback)
        screen = ScreenSession(feedback: feedback)
        screen.localScreen = NativeScreen.port
        torch = TorchSession(feedback: feedback)
        files = RecentFilesSession()
        commands = CommandSession(feedback: feedback)
        login = LoginSession()
        mcp = McpSession(feedback: feedback)
        setup = SetupSession(feedback: feedback)
        clipboard = ClipboardSession()
        notifications = NotificationSession()
        health = DeviceHealthSession()
        remoteControls = RemoteControlsSession(feedback: feedback)
        wire()
        bindPage()
    }

    func startLocalEvents() { localEvents.start() }
    func stopLocalEvents() { localEvents.stop() }

    // @Published publishes before storage changes. Rebind from didSet so the
    // visibility and page checks below use the new values.
    private func surfaceVisibilityChanged(opened: Bool) {
        bindPage()
        link.notePanel(surfaceVisible)
        if opened { refreshAdbFeaturesIfNeeded() }
    }

    /// 面板关闭时不转发子会话；打开时只转发当前页会读到的会话。
    private func bindPage() {
        pageWatch.removeAll()
        guard surfaceVisible else { return }
        observe(feedback)
        observe(appearance)
        observe(link)
        observe(mcp)
        if panelVisible {
            observe(health); observe(clipboard); observe(notifications); observe(torch); observe(screen); observe(remoteControls); observe(files)
        }
        guard settingsOpen else { return }
        switch page {
        case .main:
            observe(health); observe(clipboard); observe(notifications); observe(torch); observe(screen); observe(remoteControls); observe(files)
        case .connection:
            observe(pairing); observe(setup)
        case .commands, .editCommand:
            observe(commands); observe(remoteControls)
        case .more:
            observe(login); observe(health); observe(remoteControls); observe(setup)
        case .files:
            observe(files); observe(remoteControls); observe(setup)
        case .about:
            observe(login)
        case .mcp, .remoteRelay:
            observe(remoteControls)
        case .stationPair:
            break
        case .remotePermissions:
            observe(remoteControls); observe(setup)
        case .features:
            observe(remoteControls); observe(setup)
        case .clipboard:
            observe(clipboard); observe(remoteControls); observe(setup)
        case .notifications:
            observe(notifications); observe(remoteControls)
        }
    }

    private func observe<Object: ObservableObject>(_ object: Object) {
        object.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &pageWatch)
    }

    var phoneFeatures: PhoneFeatureState? {
        if mcp.requestAvailable, let readiness = remoteControls.reading?.features { return readiness }
        return link.phoneFeatures
    }
    func featureAllows(_ key: String) -> Bool { phoneFeatures?.allows(key) ?? true }
    private var controlPreferences: PhoneConnectionPreferences? {
        guard let reading = remoteControls.reading, reading.localMcp != nil || reading.remoteEnabled != nil else { return nil }
        return PhoneConnectionPreferences(localMcp: reading.localMcp, remote: reading.remoteEnabled)
    }
    var mcpPresentation: McpServicePresentation {
        let preferences = (mcp.requestAvailable ? controlPreferences : nil) ?? link.connectionPreferences
        let local = mcp.localOnline && preferences?.localMcp != false
        let remote = mcp.remoteOnline && preferences?.remote != false
        return McpServicePresentation(paused: phoneFeatures?.master == false, gatewayReady: mcp.gatewayReady,
            listening: mcp.listening && (local || remote), localOnline: local, localExpected: preferences?.localMcp == true,
            remoteOnline: remote, remoteConfigured: preferences?.remote == false ? false : mcp.remoteConfigured,
            remoteChecking: preferences?.remote != false && mcp.remoteChecking,
            closed: preferences?.localMcp == false && preferences?.remote == false)
    }
    var canOperate: Bool { link.serial != nil && feedback.activity == nil && (phoneFeatures?.master ?? true) }
    var canStartScreen: Bool { featureAllows("screen") && (canOperate || remoteControls.canScreen) && (!screen.isMirroring || screen.usingRemote) && !screen.isRecording }
    var canScreenshot: Bool { featureAllows("capture") && (canOperate || remoteControls.canCapture) }
    var canStayAwake: Bool { featureAllows("awake") && (canOperate || remoteControls.canStayAwake) }
    var canTorch: Bool { featureAllows("torch") && (canOperate || remoteControls.canTorch) }

    func screenshot() { link.serial != nil ? screen.screenshot() : remoteControls.screenshot() }
    func setStayAwake(_ on: Bool) { link.serial != nil ? link.setStayAwake(on) : remoteControls.setStayAwake(on) }
    func setTorch(_ on: Bool) { link.serial != nil || torch.isBeating ? torch.setTorch(on) : remoteControls.setTorch(on) }

    var feedbackText: String? {
        if let activity = feedback.activity, !activity.isEmpty { return activity }
        if let notice = feedback.notice, !notice.isEmpty { return notice }
        if link.isUnverified, !link.deviceDetail.isEmpty { return link.deviceDetail }
        return nil
    }

    func refresh() {
        login.refresh()
        if !login.opensAtLogin && !login.needsApproval {
            login.note = nil
        }
        files.refresh()
        health.refresh()
        remoteControls.refresh()
        Task { await link.probe(readingControls: true) }
    }

    func quit() {
        if screen.isRecording {
            screen.stopForQuit { [weak self] in self?.finishQuit() }
            return
        }
        finishQuit()
    }

    private func finishQuit() {
        screen.stopMirror()
        if torch.isBeating {
            torch.stopForQuit()
            return
        }
        NSApplication.shared.terminate(nil)
    }

    private func wire() {
        remoteEvents.route = { [weak self] in
            guard let self, self.mcp.channel == "remote", self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        remoteEvents.clients = { [weak self] in (self?.clipboard.eventClient ?? "", self?.notifications.eventClient ?? "") }
        remoteEvents.onSubscription = { [weak self] clip, notes in
            self?.clipboard.subscribed(clip); self?.notifications.subscribed(notes)
        }
        remoteEvents.onEvent = { [weak self] topic in
            if topic == "clipboard" { self?.clipboard.invalidate() }
            if topic == "notifications" { self?.notifications.invalidate() }
        }
        localEvents.route = { [weak self] in
            guard let self, self.mcp.channel != "remote", self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        localEvents.remote = { [weak self] in self?.mcp.channel == "remote" }
        localEvents.clientID = { [weak self] in self?.clipboard.waitClientID ?? "" }
        localEvents.onClipboard = { [weak self] in self?.clipboard.invalidate() }
        localEvents.onNotifications = { [weak self] in self?.notifications.invalidate() }
        localEvents.onActive = { [weak self] active in
            guard let self, self.mcp.channel != "remote" else { return }
            self.clipboard.subscribed(active)
            self.notifications.subscribed(active)
        }
        ClipboardRPC.onUnauthorized = { [weak self] in self?.mcp.noteUnauthorized() }
        remoteControls.accessMonitoring = { [weak self] in
            guard let self else { return false }
            return self.mcp.requestAvailable
        }
        remoteControls.route = { [weak self] in
            guard let self, self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        remoteControls.onFinishedCapture = { [weak self] in self?.files.refresh() }
        health.route = { [weak self] in
            guard let self, self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        health.states = { [weak self] in
            guard let self, let (endpoint, token) = self.health.route() else { throw CancellationError() }
            return try await ClipboardRPC.call(endpoint: endpoint, token: token, name: "station_event_states", arguments: [:])
        }
        clipboard.route = { [weak self] in
            guard let self, self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        notifications.route = { [weak self] in
            guard let self, self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        clipboard.access = { [weak self] in self?.remoteControls.accessState ?? RemoteAccessState() }
        notifications.access = { [weak self] in self?.remoteControls.accessState ?? RemoteAccessState() }
        setup.prefersLocalInstall = { [weak self] in self?.link.serial != nil }
        setup.installBlocker = { [weak self] in
            guard let self, self.link.serial == nil else { return nil }
            return self.remoteBlocker([.install])
        }
        setup.queryInstallBlocker = { [weak self] in self?.remoteBlocker([.shell]) }
        clipboard.remote = { [weak self] in self?.mcp.channel == "remote" }
        notifications.remote = { [weak self] in self?.mcp.channel == "remote" }
        health.remote = { [weak self] in self?.mcp.channel == "remote" }
        link.allow = { [weak self] in self?.canStayAwake ?? false }
        screen.allow = { [weak self] in self?.canScreenshot ?? false }
        screen.remoteRoute = { [weak self] in
            guard let self, self.link.serial == nil, self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        screen.allowStart = { [weak self] in self?.canStartScreen ?? false }
        torch.allow = { [weak self] in self?.canTorch ?? false }
        commands.allow = { [weak self] in (self?.canOperate ?? false) && (self?.featureAllows("shell") ?? false) }
        commands.serial = { [weak self] in self?.link.serial }
        commands.remoteRoute = { [weak self] in
            guard let self, self.link.serial == nil, self.mcp.channel == "remote", self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        commands.recoveryRoute = { [weak self] in
            guard let self, self.mcp.requestAvailable else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        commands.openPage = { [weak self] page in self?.page = page }
        screen.onFinishedCapture = { [weak self] in self?.files.refresh() }
        link.onRefreshTorch = { [weak self] in self?.torch.refreshStatus() }
        pairing.onPaired = { [weak self] in self?.link.noteWirelessPaired() }
        link.onPairingNeeded = { [weak self] in
            guard let self else { return }
            if !self.surfaceVisible || self.page == .main {
                self.page = .connection
            }
            if self.surfaceVisible && self.page == .connection {
                self.pairing.startIfIdle()
            } else if self.surfaceVisible {
                self.feedback.notice = "这台电脑尚未配对。打开连接设置，用手机扫描二维码。"
            }
        }
        link.onSerial = { [weak self] serial in
            guard let self else { return }
            self.torch.noteSerial(serial)
            self.commands.noteSerial(serial)
            self.mcp.noteSerial(serial)
            self.publishEventIdentity()
            if serial != nil { self.refreshAdbFeaturesIfNeeded() }
        }
        remoteControls.onUpdated = { [weak self] in
            self?.publishEventIdentity()
            self?.syncLocalMcp()
        }
        mcp.onRemoteConnection = { [weak self] connected in
            guard let self else { return }
            let changed = self.link.remoteConnected != connected
            self.link.noteRemoteConnection(connected)
            // RTT/status updates must not start another controls read each time.
            if !self.mcp.requestAvailable || changed {
                self.remoteControls.refresh()
            }
            if !self.mcp.requestAvailable || (changed && self.commands.monitoring) {
                self.commands.refreshRemote()
            }
        }
        mcp.allowRemotePair = { [weak self] in self?.canOperate ?? false }
        mcp.wantsLocal = { [weak self] in
            guard let self else { return false }
            let local = (self.mcp.requestAvailable ? self.controlPreferences?.localMcp : nil) ?? self.link.connectionPreferences?.localMcp
            return local != false
        }
        mcp.onRemoteChecking = { [weak self] checking in
            self?.link.noteRemoteChecking(checking)
        }
        mcp.onLocalOnline = { [weak self] online in
            self?.link.noteGatewayLocal(online)
        }
        mcp.onRoute = { [weak self] in
            self?.localEvents.consider()
        }
        torch.onQuit = {
            NSApplication.shared.terminate(nil)
        }
        setup.onFinished = { [weak self] in
            Task { await self?.link.probe(readingControls: true) }
        }
    }

    private func publishEventIdentity() {
        localEvents.noteIdentity(serial: link.serial, appVersion: remoteControls.reading?.appVersion)
    }

    private func syncLocalMcp() {
        let enabled = (mcp.requestAvailable ? controlPreferences?.localMcp : nil) ?? link.connectionPreferences?.localMcp
        link.noteLocalMcp(enabled)
        mcp.noteLocalMcp(enabled)
    }

    /// MCP 读不到开关、adb 仍在线时，连上和打开面板各读一次，不恢复每秒轮询。
    private func refreshAdbFeaturesIfNeeded() {
        guard FeatureReadPolicy.shouldRead(serialOnline: link.serial != nil, mcpAvailable: mcp.requestAvailable,
                                            mcpHasFeatures: remoteControls.reading?.features != nil) else { return }
        Task { await link.readProjectedFeatures() }
    }
}

enum FeatureReadPolicy {
    static func shouldRead(serialOnline: Bool, mcpAvailable: Bool, mcpHasFeatures: Bool) -> Bool {
        serialOnline && !(mcpAvailable && mcpHasFeatures)
    }
}
