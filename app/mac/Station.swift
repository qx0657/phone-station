import AppKit
import Combine
import Foundation

/// 各页各自的状态在对应的 session 里。这里只负责把它们接上，并在页面之间跳转。
@MainActor
final class Station: ObservableObject {
    let feedback: StationFeedback
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
    let health: DeviceHealthSession
    let remoteControls: RemoteControlsSession
    private var navigation = StationNavigation()
    @Published var page: StationPage = .main {
        didSet {
            navigation.changed(from: oldValue, to: page)
            if oldValue != page && feedback.activity == nil { feedback.notice = nil }
        }
    }
    func goBack(fallback: StationPage = .main) { page = navigation.destination(fallback: fallback) }
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

    private var subscriptions = Set<AnyCancellable>()

    init() {
        let feedback = StationFeedback()
        self.feedback = feedback
        link = LinkSession(feedback: feedback)
        screen = ScreenSession(feedback: feedback)
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
        watch(feedback)
        watch(link)
        watch(screen)
        watch(torch)
        watch(files)
        watch(commands)
        watch(login)
        watch(mcp)
        watch(setup)
        watch(clipboard)
        watch(notifications)
        watch(health)
        watch(remoteControls)
    }

    private func watch<Object: ObservableObject>(_ object: Object) {
        object.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &subscriptions)
    }

    var phoneFeatures: PhoneFeatureState? {
        let readiness = mcp.requestAvailable ? remoteControls.reading?.features : nil
        guard var local = link.phoneFeatures else { return readiness }
        if let readiness, local.master == readiness.master && local.selected == readiness.selected {
            local.reasons = readiness.reasons
        }
        return local
    }
    func featureAllows(_ key: String) -> Bool { phoneFeatures?.allows(key) ?? true }
    var mcpPresentation: McpServicePresentation {
        let preferences = link.connectionPreferences
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
        link.onSerial = { [weak self] serial in
            self?.torch.noteSerial(serial)
            self?.commands.noteSerial(serial)
            self?.mcp.noteSerial(serial)
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
        mcp.onRemoteChecking = { [weak self] checking in
            self?.link.noteRemoteChecking(checking)
        }
        torch.onQuit = {
            NSApplication.shared.terminate(nil)
        }
        setup.onFinished = { [weak self] in
            Task { await self?.link.probe(readingControls: true) }
        }
    }
}
