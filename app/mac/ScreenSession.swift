import AppKit
import Foundation

@MainActor
final class ScreenSession: ObservableObject {
    @Published private(set) var isMirroring = false
    @Published private(set) var isRecording = false
    @Published private(set) var isStoppingRecording = false

    var allow: () -> Bool = { false }
    var allowStart: () -> Bool = { false }
    var onFinishedCapture: () -> Void = {}

    private let feedback: StationFeedback
    private var mirrorProcess: Process?
    private var recordProcess: Process?
    private var mirrorLog: URL?
    private var recordLog: URL?
    private var recordFile: URL?
    private var quitAfterRecording = false
    private var onQuitReady: (() -> Void)?

    init(feedback: StationFeedback) {
        self.feedback = feedback
    }

    func screenshot() {
        guard allow() else { return }
        feedback.activity = "正在截取画面…"
        feedback.notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("screenshot.sh", timeout: 35)
            await MainActor.run {
                self.feedback.activity = nil
                self.onFinishedCapture()
                self.feedback.notice = result.succeeded ? "截图已保存，并在预览中打开。" :
                    (result.timedOut ? "截图超时，请检查手机连接。" : StationText.reason(result.output, fallback: "截图失败。"))
            }
        }
    }

    func startMirror() {
        guard allowStart() else { return }
        feedback.notice = nil
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("phone-mirror-\(UUID().uuidString).log")
        do {
            let process = try StationRunner.startScript("mirror.sh", [], log: log) { [weak self] finished in
                Task { @MainActor in self?.mirrorEnded(finished) }
            }
            mirrorProcess = process
            mirrorLog = log
            isMirroring = true
            focusScrcpy(process)
        } catch {
            feedback.notice = "无法打开投屏：\(error.localizedDescription)"
            try? FileManager.default.removeItem(at: log)
        }
    }

    func stopMirror() {
        mirrorProcess?.interrupt()
    }

    func startRecording() {
        guard allowStart() else { return }
        let folder = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Movies/scrcpy")
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            feedback.notice = "无法创建录屏目录：\(error.localizedDescription)"
            return
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let filename = "\(formatter.string(from: Date()))-\(UUID().uuidString.prefix(6)).mp4"
        let destination = folder.appendingPathComponent(filename)
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("phone-record-\(UUID().uuidString).log")
        feedback.notice = nil
        do {
            let process = try StationRunner.startScript("record.sh", [destination.path], log: log) { [weak self] finished in
                Task { @MainActor in self?.recordEnded(finished) }
            }
            recordProcess = process
            recordLog = log
            recordFile = destination
            isRecording = true
            isStoppingRecording = false
        } catch {
            feedback.notice = "无法开始录屏：\(error.localizedDescription)"
            try? FileManager.default.removeItem(at: log)
        }
    }

    func stopRecording() {
        guard isRecording && !isStoppingRecording else { return }
        isStoppingRecording = true
        feedback.notice = "正在结束录屏并保存文件…"
        recordProcess?.interrupt()
    }

    func stopForQuit(_ done: @escaping () -> Void) {
        onQuitReady = done
        quitAfterRecording = true
        stopRecording()
    }

    /// 菜单栏 App 拉起的 scrcpy 不会自己到前台。窗口出现后，把这次点击的激活让给它。
    private func focusScrcpy(_ process: Process) {
        let pid = process.processIdentifier
        Self.yieldActivation(to: pid)
        Task { @MainActor in
            for _ in 0..<120 {
                guard self.mirrorProcess === process, process.isRunning else { return }
                guard Self.processOwnsWindow(pid),
                      let app = NSRunningApplication(processIdentifier: pid) else {
                    try? await Task.sleep(nanoseconds: 250_000_000)
                    continue
                }
                if app.isActive { return }
                Self.yieldActivation(to: pid)
                _ = Self.activateRunningApp(app)
                if app.isActive { return }
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
    }

    private static func yieldActivation(to pid: pid_t) {
        guard let app = NSRunningApplication(processIdentifier: pid) else { return }
        if #available(macOS 14.0, *) {
            NSApp.yieldActivation(to: app)
        }
    }

    @discardableResult
    private static func activateRunningApp(_ app: NSRunningApplication) -> Bool {
        if #available(macOS 14.0, *) {
            return app.activate(from: .current, options: [.activateAllWindows])
        }
        return activateIgnoringOtherApps(app)
    }

    @available(macOS, introduced: 13.0, obsoleted: 14.0)
    private static func activateIgnoringOtherApps(_ app: NSRunningApplication) -> Bool {
        app.activate(options: [.activateIgnoringOtherApps, .activateAllWindows])
    }

    private static func processOwnsWindow(_ pid: pid_t) -> Bool {
        guard let windows = CGWindowListCopyWindowInfo([.excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else {
            return false
        }
        for window in windows {
            guard (window[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == pid else { continue }
            let layer = (window[kCGWindowLayer as String] as? NSNumber)?.intValue ?? 0
            guard layer == 0 else { continue }
            let bounds = window[kCGWindowBounds as String] as? NSDictionary
            let width = (bounds?["Width"] as? NSNumber)?.doubleValue ?? 0
            let height = (bounds?["Height"] as? NSNumber)?.doubleValue ?? 0
            if width >= 40, height >= 40 { return true }
        }
        return false
    }

    private func mirrorEnded(_ process: Process) {
        guard mirrorProcess === process else { return }
        mirrorProcess = nil
        isMirroring = false
        let text = StationRunner.logText(mirrorLog)
        mirrorLog = nil
        feedback.notice = process.terminationStatus == 0
            ? "投屏窗口已关闭。"
            : StationText.reason(text, fallback: "投屏已结束；请检查 scrcpy 和手机连接。")
    }

    private func recordEnded(_ process: Process) {
        guard recordProcess === process else { return }
        let wasStopping = isStoppingRecording
        recordProcess = nil
        isRecording = false
        isStoppingRecording = false
        let text = StationRunner.logText(recordLog)
        recordLog = nil
        let file = recordFile
        recordFile = nil
        let size = file.flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize } ?? 0
        if let file, size > 0 && (process.terminationStatus == 0 || wasStopping) {
            feedback.notice = "录屏已保存：\(file.lastPathComponent)"
        } else {
            feedback.notice = StationText.reason(text, fallback: "录屏未能正常保存；请检查 scrcpy 和手机连接。")
        }
        onFinishedCapture()
        if quitAfterRecording {
            quitAfterRecording = false
            onQuitReady?()
            onQuitReady = nil
        }
    }
}
