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
    let health: DeviceHealthSession
    let remoteControls: RemoteControlsSession
    @Published var page: StationPage = .main
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

    var canOperate: Bool { link.serial != nil && feedback.activity == nil }
    var canStartScreen: Bool { canOperate && !screen.isMirroring && !screen.isRecording }
    var canScreenshot: Bool { canOperate || remoteControls.canCapture }
    var canStayAwake: Bool { canOperate || remoteControls.canStayAwake }
    var canTorch: Bool { canOperate || remoteControls.canTorch }

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
        remoteControls.route = { [weak self] in
            guard let self, self.link.serial == nil, self.mcp.requestAvailable else { return nil }
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
        clipboard.remote = { [weak self] in self?.mcp.channel == "remote" }
        notifications.remote = { [weak self] in self?.mcp.channel == "remote" }
        health.remote = { [weak self] in self?.mcp.channel == "remote" }
        link.allow = { [weak self] in self?.canOperate ?? false }
        screen.allow = { [weak self] in self?.canOperate ?? false }
        screen.allowStart = { [weak self] in self?.canStartScreen ?? false }
        torch.allow = { [weak self] in self?.canOperate ?? false }
        commands.allow = { [weak self] in self?.canOperate ?? false }
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
            if !self.mcp.requestAvailable || (changed && self.remoteControls.monitoring) {
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
