import AppKit
import Combine
import Foundation

enum StationPage {
    case main
    case connection
    case commands
    case editCommand
    case more
    case files
    case about
    case mcp
    case remoteRelay
    case clipboard
}

@MainActor
final class StationFeedback: ObservableObject {
    @Published var activity: String?
    @Published var notice: String?
}

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
    }

    private func watch<Object: ObservableObject>(_ object: Object) {
        object.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &subscriptions)
    }

    var canOperate: Bool { link.serial != nil && feedback.activity == nil }
    var canStartScreen: Bool { canOperate && !screen.isMirroring && !screen.isRecording }

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
        clipboard.route = { [weak self] in
            guard let self, self.mcp.listening else { return nil }
            return (self.mcp.endpoint, self.mcp.token)
        }
        link.allow = { [weak self] in self?.canOperate ?? false }
        screen.allow = { [weak self] in self?.canOperate ?? false }
        screen.allowStart = { [weak self] in self?.canStartScreen ?? false }
        torch.allow = { [weak self] in self?.canOperate ?? false }
        commands.allow = { [weak self] in self?.canOperate ?? false }
        commands.serial = { [weak self] in self?.link.serial }
        commands.openPage = { [weak self] page in self?.page = page }
        screen.onFinishedCapture = { [weak self] in self?.files.refresh() }
        link.onRefreshTorch = { [weak self] in self?.torch.refreshStatus() }
        link.onSerial = { [weak self] serial in
            self?.torch.noteSerial(serial)
            self?.commands.noteSerial(serial)
            self?.mcp.noteSerial(serial)
        }
        mcp.onRemoteConnection = { [weak self] connected in
            self?.link.noteRemoteConnection(connected)
        }
        mcp.allowRemotePair = { [weak self] in self?.canOperate ?? false }
        mcp.onLocalRouteUnavailable = { [weak self] in
            self?.link.noteLocalRouteUnavailable()
        }
        torch.onQuit = {
            NSApplication.shared.terminate(nil)
        }
        setup.onFinished = { [weak self] in
            Task { await self?.link.probe(readingControls: true) }
        }
    }
}
