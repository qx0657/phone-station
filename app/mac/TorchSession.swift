import AppKit
import Foundation

@MainActor
final class TorchSession: ObservableObject {
    @Published private(set) var torchOn = false
    @Published private(set) var torchBeat = false
    /// 采集进程还在，但几秒内音量一直是 0。这时系统多半没把「系统录音」给这个 App。
    @Published private(set) var beatNeedsAudioPermission = false

    var allow: () -> Bool = { false }
    var onQuit: () -> Void = {}

    private let feedback: StationFeedback
    private var beatProcess: Process?
    private var beatLog: URL?
    private var beatStopRequested = false
    private var beatGeneration = 0
    private var torchAfterBeat = false
    private var quitAfterBeat = false
    private var controlEpoch = 0
    private var serial: String?

    var isBeating: Bool { beatProcess != nil }

    init(feedback: StationFeedback) {
        self.feedback = feedback
    }

    func noteSerial(_ serial: String?) {
        self.serial = serial
        if serial == nil, beatProcess == nil, torchOn { torchOn = false }
    }

    func refreshStatus() {
        guard serial != nil, feedback.activity == nil, beatProcess == nil else { return }
        let epoch = controlEpoch
        let current = serial
        Task.detached(priority: .utility) {
            let lamp = Self.torchEnabled()
            await MainActor.run {
                guard epoch == self.controlEpoch, self.feedback.activity == nil,
                      self.beatProcess == nil, self.serial == current else { return }
                self.torchOn = lamp
            }
        }
    }

    func setTorch(_ on: Bool) {
        if beatProcess != nil {
            torchAfterBeat = on
            torchOn = on
            stopBeat()
            return
        }
        guard allow(), on != torchOn else { return }
        let previous = torchOn
        torchOn = on
        feedback.activity = on ? "正在打开闪光灯…" : "正在关闭闪光灯…"
        feedback.notice = nil
        controlEpoch += 1
        let epoch = controlEpoch
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("torch.sh", [on ? "on" : "off"], timeout: 25)
            await MainActor.run {
                guard epoch == self.controlEpoch else { return }
                self.controlEpoch += 1
                self.feedback.activity = nil
                if result.succeeded {
                    self.torchOn = on
                    self.feedback.notice = on ? "闪光灯已打开。" : "闪光灯已关闭。"
                } else {
                    self.torchOn = previous
                    self.feedback.notice = result.timedOut
                        ? "闪光灯没有响应。"
                        : StationText.reason(result.output, fallback: on ? "无法打开闪光灯。" : "无法关闭闪光灯。")
                }
            }
        }
    }

    func setTorchBeat(_ on: Bool) {
        if !on {
            guard beatProcess != nil else { return }
            stopBeat()
            return
        }
        guard allow(), beatProcess == nil else { return }
        feedback.notice = nil
        beatNeedsAudioPermission = false
        beatStopRequested = false
        beatGeneration += 1
        let generation = beatGeneration
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("phone-beat-\(UUID().uuidString).log")
        do {
            NSApp.activate(ignoringOtherApps: true)
            let process = try StationRunner.startScript("torch.sh", ["beat"], log: log) { [weak self] finished in
                Task { @MainActor in self?.beatEnded(finished) }
            }
            beatProcess = process
            beatLog = log
            torchBeat = true
            torchOn = false
            scheduleBeatPermissionWatch(generation)
        } catch {
            feedback.notice = "无法跟随声音：\(error.localizedDescription)"
            try? FileManager.default.removeItem(at: log)
        }
    }

    func openAudioCaptureSettings() {
        let candidates = [
            "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_AudioCapture",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_AudioCapture"
        ]
        for raw in candidates {
            guard let url = URL(string: raw) else { continue }
            if NSWorkspace.shared.open(url) { return }
        }
    }

    func stopForQuit() {
        quitAfterBeat = true
        stopBeat()
    }

    private func scheduleBeatPermissionWatch(_ generation: Int) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard generation == self.beatGeneration, self.beatProcess != nil, let url = self.beatLog else { return }
            let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
            guard text.contains("还没有收到系统声音") || text.contains("系统录音") else { return }
            self.beatNeedsAudioPermission = true
            self.feedback.notice = "还没有收到系统声音。若弹出权限窗口，允许「手机工位」。也可以到「系统设置 → 隐私与安全性 → 系统录音」打开它，然后关掉再打开一次。"
        }
    }

    private func stopBeat() {
        guard beatProcess != nil else { return }
        beatGeneration += 1
        beatNeedsAudioPermission = false
        beatStopRequested = true
        beatProcess?.interrupt()
    }

    private func beatEnded(_ process: Process) {
        guard beatProcess === process else { return }
        beatProcess = nil
        torchBeat = false
        let text = StationRunner.logText(beatLog)
        beatLog = nil
        let stopped = beatStopRequested
        let turnOn = torchAfterBeat
        beatStopRequested = false
        torchAfterBeat = false
        if quitAfterBeat {
            quitAfterBeat = false
            onQuit()
            return
        }
        if turnOn {
            torchOn = false
            setTorch(true)
            return
        }
        torchOn = false
        beatNeedsAudioPermission = false
        if stopped {
            feedback.notice = "已停止跟随声音。"
        } else if text.contains("系统声音") || text.contains("系统录音") || text.contains("权限") || text.contains("录制") {
            beatNeedsAudioPermission = true
            feedback.notice = String((text.split(separator: "\n").last.map(String.init) ?? "请到「系统录音」允许「手机工位」。").prefix(220))
        } else {
            feedback.notice = StationText.reason(text, fallback: "跟随声音已结束。")
        }
    }

    private nonisolated static func torchEnabled() -> Bool {
        let result = StationRunner.scriptResult("torch.sh", ["status"], timeout: 20)
        return result.succeeded && result.output.contains("开着")
    }
}
