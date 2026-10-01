import AppKit
import Foundation
import ServiceManagement

private struct CommandResult {
    let code: Int32
    let output: String
    let timedOut: Bool

    var succeeded: Bool { code == 0 && !timedOut }
}

private enum StationRunner {
    static var environment: [String: String] {
        var values = ProcessInfo.processInfo.environment
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let extra = [
            "\(home)/Library/Android/sdk/platform-tools",
            "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"
        ]
        values["PATH"] = (extra + [values["PATH"] ?? ""]).joined(separator: ":")
        return values
    }

    static func executable(_ name: String) -> URL? {
        for directory in (environment["PATH"] ?? "").split(separator: ":") {
            let candidate = URL(fileURLWithPath: String(directory)).appendingPathComponent(name)
            if FileManager.default.isExecutableFile(atPath: candidate.path) { return candidate }
        }
        return nil
    }

    static func script(_ name: String) -> URL {
        Bundle.main.resourceURL!
            .appendingPathComponent("phone-station/scripts")
            .appendingPathComponent(name)
    }

    static func capture(_ executable: URL, _ arguments: [String], timeout: Int = 15) -> CommandResult {
        let log = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: log.path, contents: nil)
        guard let writer = try? FileHandle(forWritingTo: log) else {
            return CommandResult(code: -1, output: "无法创建任务日志。", timedOut: false)
        }
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        process.environment = environment
        process.standardOutput = writer
        process.standardError = writer
        let finished = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in finished.signal() }
        do {
            try process.run()
        } catch {
            try? writer.close()
            try? FileManager.default.removeItem(at: log)
            return CommandResult(code: -1, output: error.localizedDescription, timedOut: false)
        }
        let timedOut = finished.wait(timeout: .now() + .seconds(timeout)) == .timedOut
        if timedOut {
            process.terminate()
            if finished.wait(timeout: .now() + .seconds(2)) == .timedOut {
                kill(process.processIdentifier, SIGKILL)
                _ = finished.wait(timeout: .now() + .seconds(2))
            }
        }
        try? writer.close()
        let output = (try? String(contentsOf: log, encoding: .utf8)) ?? ""
        try? FileManager.default.removeItem(at: log)
        return CommandResult(code: timedOut ? -1 : process.terminationStatus,
                             output: output.trimmingCharacters(in: .whitespacesAndNewlines),
                             timedOut: timedOut)
    }

    static func scriptResult(_ name: String, _ arguments: [String] = [], timeout: Int = 30) -> CommandResult {
        capture(URL(fileURLWithPath: "/bin/zsh"), [script(name).path] + arguments, timeout: timeout)
    }

    static func startScript(_ name: String, _ arguments: [String], log: URL,
                            onExit: @escaping (Process) -> Void) throws -> Process {
        FileManager.default.createFile(atPath: log.path, contents: nil)
        let writer = try FileHandle(forWritingTo: log)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/zsh")
        process.arguments = [script(name).path] + arguments
        process.environment = environment
        process.standardOutput = writer
        process.standardError = writer
        process.terminationHandler = onExit
        do {
            try process.run()
            try? writer.close()
            return process
        } catch {
            try? writer.close()
            throw error
        }
    }

    static func logText(_ url: URL?) -> String {
        guard let url else { return "" }
        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        try? FileManager.default.removeItem(at: url)
        return String(text.suffix(1800)).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private enum Link: Sendable {
    case checking
    case connected
    case unverified
    case offline
}

private struct LinkReading: Sendable {
    var name: String
    var detail: String
    var transport: String = ""
    var link: Link
    var serial: String? = nil
}

struct SavedAdbCommand: Codable, Equatable, Identifiable, Sendable {
    var id: UUID
    var name: String
    var arguments: String
}

struct CommandOutput: Equatable {
    var commandID: UUID
    var name: String
    var text: String
}

struct CommandDraft: Equatable {
    var id: UUID?
    var name: String
    var arguments: String
}

private enum CommandStore {
    static let key = "adbCommands.v1"
    static let foregroundID = UUID(uuidString: "C3A1E5B0-7C4A-4E2A-9B1F-6D0A8E2F1001")!
    static let wakeID = UUID(uuidString: "C3A1E5B0-7C4A-4E2A-9B1F-6D0A8E2F1002")!

    static func load() -> [SavedAdbCommand] {
        if UserDefaults.standard.object(forKey: key) == nil {
            let seeded = defaults
            save(seeded)
            return seeded
        }
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([SavedAdbCommand].self, from: data) else {
            return []
        }
        return decoded
    }

    static func save(_ commands: [SavedAdbCommand]) {
        guard let data = try? JSONEncoder().encode(commands) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private static let defaults = [
        SavedAdbCommand(id: foregroundID, name: "前台应用",
                        arguments: "shell \"dumpsys window | grep mCurrentFocus\""),
        SavedAdbCommand(id: wakeID, name: "唤醒",
                        arguments: "shell input keyevent KEYCODE_WAKEUP"),
    ]
}

@MainActor
final class StationModel: ObservableObject {
    enum Page {
        case main
        case connection
        case commands
        case editCommand
        case more
        case files
        case about
    }

    enum StatusTone {
        case ready
        case caution
        case neutral
    }

    @Published private(set) var deviceName = "正在检查设备…"
    @Published private(set) var deviceDetail = ""
    @Published private(set) var transport = ""
    @Published private(set) var serial: String?
    @Published private(set) var activity: String?
    @Published private(set) var notice: String?
    @Published private(set) var isMirroring = false
    @Published private(set) var isRecording = false
    @Published private(set) var isStoppingRecording = false
    @Published private(set) var stayAwake = false
    @Published private(set) var torchOn = false
    @Published private(set) var torchBeat = false
    /// 采集进程还在，但几秒内音量一直是 0。这时系统多半没把「系统录音」给这个 App。
    @Published private(set) var beatNeedsAudioPermission = false
    @Published private(set) var recentFiles: [URL] = []
    @Published private(set) var recentModified: [String: Date] = [:]
    @Published private(set) var recentThumbnails: [String: NSImage] = [:]
    @Published private(set) var hoveredRecentPath: String?
    @Published private(set) var copiedRecentPath: String?
    @Published private(set) var opensAtLogin = false
    @Published private(set) var loginItemNeedsApproval = false
    @Published private(set) var loginItemNote: String?
    @Published private(set) var battery: BatteryReport.Reading?
    @Published private(set) var commands: [SavedAdbCommand] = []
    @Published private(set) var commandOutput: CommandOutput?
    /// Writable from the editor fields. Command Line Tools cannot use `@State`.
    @Published var commandDraft = CommandDraft(id: nil, name: "", arguments: "")
    @Published private(set) var commandEditError: String?
    /// Bumps whenever the editor should reset its fields.
    @Published private(set) var commandDraftToken = 0
    @Published var page: Page = .main

    init() {
        commands = CommandStore.load()
        refreshLoginItem()
    }

    private var link: Link = .checking
    /// 已经确认是 PGT-AN20 的序列号。菜单栏复查时序列号没变就不再读型号。
    private var trustedSerial: String?
    var onLinkChange: (@MainActor (Bool) -> Void)?
    private var linkWatch: Task<Void, Never>?
    private static let linkWatchInterval: UInt64 = 5_000_000_000
    private var statusRevision = 0
    private var controlEpoch = 0
    private var mirrorProcess: Process?
    private var recordProcess: Process?
    private var beatProcess: Process?
    private var mirrorLog: URL?
    private var recordLog: URL?
    private var beatLog: URL?
    private var recordFile: URL?
    private var quitAfterRecording = false
    private var quitAfterBeat = false
    private let recentPreview = RecentPreviewPanel()
    private var recentHoverAnchor: NSRect?
    private weak var recentHost: NSWindow?
    private var thumbnailLoad: Task<Void, Never>?
    private var thumbnailToken = 0
    private var copyFeedback: Task<Void, Never>?
    private var beatStopRequested = false
    private var beatGeneration = 0
    private var torchAfterBeat = false

    var canOperate: Bool { serial != nil && activity == nil }
    var canStartScreen: Bool { canOperate && !isMirroring && !isRecording }
    var isChecking: Bool { link == .checking }
    var statusReady: Bool { link == .connected }
    var statusLabel: String {
        switch link {
        case .checking: return "检查中"
        case .connected: return "已连接"
        case .unverified: return "未验证"
        case .offline: return "未连接"
        }
    }
    var headline: String {
        switch link {
        case .checking:
            return "正在检查设备…"
        case .offline:
            return deviceName
        case .connected, .unverified:
            return deviceName == "PGT-AN20" ? "荣耀 PGT-AN20" : deviceName
        }
    }

    /// Transport when a phone is online. Offline, this is the reason instead.
    var connectionLine: String {
        switch link {
        case .checking:
            return ""
        case .connected, .unverified:
            return transport
        case .offline:
            return deviceDetail
        }
    }

    var statusTone: StatusTone {
        switch link {
        case .connected: return .ready
        case .unverified: return .caution
        case .checking, .offline: return .neutral
        }
    }

    var loginItemSubtitle: String {
        if let loginItemNote, !loginItemNote.isEmpty { return loginItemNote }
        return "登录这台 Mac 后自动打开"
    }

    /// Idle and connected stays quiet. Action results and unverified phones still explain themselves.
    var feedbackText: String? {
        if let activity, !activity.isEmpty { return activity }
        if let notice, !notice.isEmpty { return notice }
        if link == .unverified, !deviceDetail.isEmpty { return deviceDetail }
        return nil
    }

    func refresh() {
        refreshLoginItem()
        if !opensAtLogin && !loginItemNeedsApproval {
            loginItemNote = nil
        }
        refreshRecentFiles()
        Task { await probeLink(readingControls: true) }
    }

    /// 面板关着时菜单栏图标也要跟着变。上一次查完隔 5 秒再查。
    /// 这次检查不读息屏、闪光灯和电量，那几项仍在打开面板时读。
    func startLinkWatch() {
        guard linkWatch == nil else { return }
        linkWatch = Task { @MainActor in
            while !Task.isCancelled {
                await self.probeLink(readingControls: false)
                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: Self.linkWatchInterval)
            }
        }
    }

    private func probeLink(readingControls: Bool) async {
        statusRevision += 1
        let revision = statusRevision
        let trusted = trustedSerial
        let reading = await Task.detached(priority: .utility) {
            Self.readLink(trustedSerial: trusted)
        }.value
        acceptStatus(revision, name: reading.name, detail: reading.detail, transport: reading.transport,
                     link: reading.link, serial: reading.serial, readingControls: readingControls)
    }

    private nonisolated static func readLink(trustedSerial: String?) -> LinkReading {
        guard let adb = StationRunner.executable("adb") else {
            return LinkReading(name: "未找到 adb", detail: "请安装 Android platform-tools。", link: .offline)
        }
        let devices = StationRunner.capture(adb, ["devices", "-l"], timeout: 10)
        if !devices.succeeded {
            let detail = devices.timedOut ? "adb 检查超时，请重试。" : reason(devices.output, fallback: "无法读取 adb 设备列表。")
            return LinkReading(name: "设备检查失败", detail: detail, link: .offline)
        }
        let lines = devices.output.split(separator: "\n").dropFirst().map(String.init)
        let online = lines.compactMap { line -> String? in
            let parts = line.split(whereSeparator: \.isWhitespace)
            return parts.count >= 2 && parts[1] == "device" ? String(parts[0]) : nil
        }
        if online.count > 1 {
            return LinkReading(name: "检测到多台设备", detail: "请只保留一台在线设备；多余的无线连接可以在连接页断开。", link: .offline)
        }
        guard let only = online.first else {
            let unauthorized = lines.contains { $0.contains("unauthorized") }
            let detail = unauthorized ? "请在手机上允许 USB 调试。" : "接入 USB，或在手机上开启无线调试后再连接。"
            return LinkReading(name: unauthorized ? "等待手机授权" : "手机未连接", detail: detail, link: .offline)
        }
        if let trustedSerial, only == trustedSerial {
            return LinkReading(name: "PGT-AN20", detail: "", transport: transportLabel(only), link: .connected, serial: only)
        }
        let model = StationRunner.capture(adb, ["-s", only, "shell", "getprop", "ro.product.model"], timeout: 8)
        let rawName = model.output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard model.succeeded, !rawName.isEmpty else {
            return LinkReading(name: "无法识别手机型号", detail: "请检查连接，然后重新打开面板。", link: .offline)
        }
        if rawName == "PGT-AN20" {
            return LinkReading(name: rawName, detail: "", transport: transportLabel(only), link: .connected, serial: only)
        }
        return LinkReading(name: rawName, detail: "该型号尚未验证。请先查看项目说明中的设备支持范围。",
                           transport: transportLabel(only), link: .unverified)
    }

    private nonisolated static func transportLabel(_ serial: String) -> String {
        let wireless = serial.contains(":") || serial.contains("._adb-tls-connect._tcp")
        return wireless ? "无线连接" : "USB 连接"
    }

    private func acceptStatus(_ revision: Int, name: String, detail: String, transport: String = "",
                              link newLink: Link, serial newSerial: String? = nil, readingControls: Bool) {
        guard revision == statusRevision else { return }
        if deviceName != name { deviceName = name }
        if deviceDetail != detail { deviceDetail = detail }
        if self.transport != transport { self.transport = transport }
        if link != newLink { link = newLink }
        if serial != newSerial {
            serial = newSerial
            battery = nil
            commandOutput = nil
        }
        if newLink == .connected, let newSerial {
            trustedSerial = newSerial
        } else {
            trustedSerial = nil
        }
        if let newSerial {
            if readingControls, activity == nil {
                refreshDeviceControls(serial: newSerial)
            }
        } else {
            if stayAwake { stayAwake = false }
            if beatProcess == nil, torchOn { torchOn = false }
        }
        onLinkChange?(newLink == .connected)
    }

    private func refreshDeviceControls(serial: String) {
        let revision = statusRevision
        let epoch = controlEpoch
        Task.detached(priority: .utility) {
            let awake = Self.stayAwakeEnabled(serial)
            let lamp = Self.torchEnabled()
            await MainActor.run {
                guard revision == self.statusRevision, epoch == self.controlEpoch,
                      self.activity == nil, self.serial == serial else { return }
                self.stayAwake = awake
                if self.beatProcess == nil { self.torchOn = lamp }
            }
        }
        Task.detached(priority: .utility) {
            let reading = Self.readBattery(serial)
            await MainActor.run {
                guard revision == self.statusRevision, epoch == self.controlEpoch,
                      self.serial == serial else { return }
                self.battery = reading
            }
        }
    }

    private nonisolated static func readBattery(_ serial: String) -> BatteryReport.Reading? {
        guard let adb = StationRunner.executable("adb") else { return nil }
        let result = StationRunner.capture(adb, ["-s", serial, "shell", "dumpsys", "battery"], timeout: 8)
        guard result.succeeded else { return nil }
        return BatteryReport.parse(result.output)
    }

    private nonisolated static func stayAwakeEnabled(_ serial: String) -> Bool {
        guard let adb = StationRunner.executable("adb") else { return false }
        let result = StationRunner.capture(adb, ["-s", serial, "shell", "settings", "get", "system", "screen_off_timeout"], timeout: 8)
        let value = Int(result.output.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
        return result.succeeded && value >= 3_600_000
    }

    private nonisolated static func torchEnabled() -> Bool {
        let result = StationRunner.scriptResult("torch.sh", ["status"], timeout: 20)
        return result.succeeded && result.output.contains("开着")
    }

    private func bumpControls() -> Int {
        controlEpoch += 1
        return controlEpoch
    }

    func connect() {
        guard activity == nil else { return }
        activity = "正在连接手机…"
        notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("connect.sh", timeout: 30)
            await MainActor.run {
                self.activity = nil
                if !result.succeeded {
                    self.notice = result.timedOut ? "连接超时。请检查手机的无线调试和 Wi-Fi。" : Self.reason(result.output, fallback: "连接失败。请检查无线调试或 USB 授权。")
                }
                self.refresh()
            }
        }
    }

    func disconnect() {
        guard activity == nil else { return }
        activity = "正在断开无线连接…"
        notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("disconnect.sh", timeout: 20)
            await MainActor.run {
                self.activity = nil
                self.notice = result.succeeded ? "无线连接已断开。" : Self.reason(result.output, fallback: "断开无线连接失败。")
                self.refresh()
            }
        }
    }

    func setStayAwake(_ on: Bool) {
        guard canOperate, on != stayAwake else { return }
        let previous = stayAwake
        stayAwake = on
        activity = on ? "正在开启保持亮屏…" : "正在恢复息屏…"
        notice = nil
        let epoch = bumpControls()
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("stay-awake.sh", [on ? "on" : "off"], timeout: 20)
            await MainActor.run {
                guard epoch == self.controlEpoch else { return }
                self.controlEpoch += 1
                self.activity = nil
                if result.succeeded {
                    self.stayAwake = on
                    self.notice = on ? "已保持亮屏。" : "已恢复息屏。"
                } else {
                    self.stayAwake = previous
                    self.notice = result.timedOut ? "保持亮屏没有响应。" : Self.reason(result.output, fallback: "无法更改息屏设置。")
                }
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
        guard canOperate, on != torchOn else { return }
        let previous = torchOn
        torchOn = on
        activity = on ? "正在打开闪光灯…" : "正在关闭闪光灯…"
        notice = nil
        let epoch = bumpControls()
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("torch.sh", [on ? "on" : "off"], timeout: 25)
            await MainActor.run {
                guard epoch == self.controlEpoch else { return }
                self.controlEpoch += 1
                self.activity = nil
                if result.succeeded {
                    self.torchOn = on
                    self.notice = on ? "闪光灯已打开。" : "闪光灯已关闭。"
                } else {
                    self.torchOn = previous
                    self.notice = result.timedOut ? "闪光灯没有响应。" : Self.reason(result.output, fallback: on ? "无法打开闪光灯。" : "无法关闭闪光灯。")
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
        guard canOperate, beatProcess == nil else { return }
        notice = nil
        beatNeedsAudioPermission = false
        beatStopRequested = false
        beatGeneration += 1
        let generation = beatGeneration
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("phone-beat-\(UUID().uuidString).log")
        do {
            // 权限窗口挂在这个 App 上。先把它带到前面，避免窗口被别的应用盖住。
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
            notice = "无法跟随声音：\(error.localizedDescription)"
            try? FileManager.default.removeItem(at: log)
        }
    }

    /// 采集失败时进程会马上退出；没拿到「系统录音」时进程不退出，样本全是 0，原来的提示只留在日志里。
    private func scheduleBeatPermissionWatch(_ generation: Int) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard generation == self.beatGeneration, self.beatProcess != nil, let url = self.beatLog else { return }
            let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
            guard text.contains("还没有收到系统声音") || text.contains("系统录音") else { return }
            self.beatNeedsAudioPermission = true
            self.notice = "还没有收到系统声音。若弹出权限窗口，允许「手机工位」。也可以到「系统设置 → 隐私与安全性 → 系统录音」打开它，然后关掉再打开一次。"
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
            mirrorProcess?.interrupt()
            NSApplication.shared.terminate(nil)
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
            notice = "已停止跟随声音。"
        } else if text.contains("系统声音") || text.contains("系统录音") || text.contains("权限") || text.contains("录制") {
            beatNeedsAudioPermission = true
            notice = String((text.split(separator: "\n").last.map(String.init) ?? "请到「系统录音」允许「手机工位」。").prefix(220))
        } else {
            notice = Self.reason(text, fallback: "跟随声音已结束。")
        }
    }

    func screenshot() {
        guard canOperate else { return }
        activity = "正在截取画面…"
        notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("screenshot.sh", timeout: 35)
            await MainActor.run {
                self.activity = nil
                self.refreshRecentFiles()
                self.notice = result.succeeded ? "截图已保存，并在预览中打开。" :
                    (result.timedOut ? "截图超时，请检查手机连接。" : Self.reason(result.output, fallback: "截图失败。"))
            }
        }
    }

    func startMirror() {
        guard canStartScreen else { return }
        notice = nil
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
            notice = "无法打开投屏：\(error.localizedDescription)"
            try? FileManager.default.removeItem(at: log)
        }
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

    func stopMirror() {
        mirrorProcess?.interrupt()
    }

    private func mirrorEnded(_ process: Process) {
        guard mirrorProcess === process else { return }
        mirrorProcess = nil
        isMirroring = false
        let text = StationRunner.logText(mirrorLog)
        mirrorLog = nil
        notice = process.terminationStatus == 0 ? "投屏窗口已关闭。" : Self.reason(text, fallback: "投屏已结束；请检查 scrcpy 和手机连接。")
    }

    func startRecording() {
        guard canStartScreen else { return }
        let folder = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Movies/scrcpy")
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            notice = "无法创建录屏目录：\(error.localizedDescription)"
            return
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let filename = "\(formatter.string(from: Date()))-\(UUID().uuidString.prefix(6)).mp4"
        let destination = folder.appendingPathComponent(filename)
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("phone-record-\(UUID().uuidString).log")
        notice = nil
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
            notice = "无法开始录屏：\(error.localizedDescription)"
            try? FileManager.default.removeItem(at: log)
        }
    }

    func stopRecording() {
        guard isRecording && !isStoppingRecording else { return }
        isStoppingRecording = true
        notice = "正在结束录屏并保存文件…"
        recordProcess?.interrupt()
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
            notice = "录屏已保存：\(file.lastPathComponent)"
        } else {
            notice = Self.reason(text, fallback: "录屏未能正常保存；请检查 scrcpy 和手机连接。")
        }
        refreshRecentFiles()
        if quitAfterRecording {
            quitAfterRecording = false
            finishQuit()
        }
    }

    // Login items stick for a bundle Launch Services can see. The hidden build
    // folder is rejected; /Applications and ~/Applications are the locations that register.
    func refreshLoginItem() {
        switch SMAppService.mainApp.status {
        case .enabled:
            opensAtLogin = true
            loginItemNeedsApproval = false
            loginItemNote = nil
        case .requiresApproval:
            opensAtLogin = true
            loginItemNeedsApproval = true
            loginItemNote = "登录后自动打开。请在「系统设置 › 通用 › 登录项与扩展」里允许。"
        case .notRegistered, .notFound:
            opensAtLogin = false
            loginItemNeedsApproval = false
        @unknown default:
            opensAtLogin = false
            loginItemNeedsApproval = false
        }
    }

    func setOpensAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            refreshLoginItem()
            if !loginItemNeedsApproval {
                loginItemNote = Self.loginItemFailure(error)
            }
            return
        }
        loginItemNote = nil
        refreshLoginItem()
        if enabled && !opensAtLogin && !loginItemNeedsApproval {
            loginItemNote = Self.loginItemFailure(nil)
        }
    }

    func openLoginItemsSettings() {
        let candidates = [
            "x-apple.systempreferences:com.apple.LoginItems-Settings.extension",
            "x-apple.systempreferences:com.apple.preference.users"
        ]
        for raw in candidates {
            guard let url = URL(string: raw) else { continue }
            if NSWorkspace.shared.open(url) { return }
        }
    }

    private static func loginItemFailure(_ error: Error?) -> String {
        let location = Bundle.main.bundlePath
        let homeApps = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications").path
        let installed = location.hasPrefix("/Applications/") || location.hasPrefix(homeApps + "/")
        if !installed {
            return "请先把「手机工位.app」放进「应用程序」文件夹，再打开那个副本。"
        }
        if let error {
            let text = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return String(text.prefix(140)) }
        }
        return "系统没有接受这项设置。"
    }

    func quit() {
        if isRecording {
            quitAfterRecording = true
            stopRecording()
            return
        }
        finishQuit()
    }

    private func finishQuit() {
        mirrorProcess?.interrupt()
        if beatProcess != nil {
            quitAfterBeat = true
            stopBeat()
            return
        }
        NSApplication.shared.terminate(nil)
    }

    func refreshRecentFiles() {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let folders = [home.appendingPathComponent("Pictures/scrcpy"), home.appendingPathComponent("Movies/scrcpy")]
        let dated: [(URL, Date)] = folders.flatMap { folder in
            (try? FileManager.default.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey],
                options: [.skipsHiddenFiles])) ?? []
        }
        .filter { ["png", "mp4", "mkv"].contains($0.pathExtension.lowercased()) }
        .map { url in
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return (url, modified)
        }
        .sorted { $0.1 > $1.1 }
        .prefix(8)
        .map { $0 }
        recentFiles = dated.map(\.0)
        recentModified = Dictionary(uniqueKeysWithValues: dated.map { ($0.0.path, $0.1) })
        let keep = Set(recentFiles.map(\.path))
        recentThumbnails = recentThumbnails.filter { keep.contains($0.key) }
        if let hoveredRecentPath, !keep.contains(hoveredRecentPath) {
            dismissRecentPreview()
        }
        loadRecentThumbnails()
    }

    func setRecentHover(_ url: URL, inside: Bool) {
        if inside {
            hoveredRecentPath = url.path
        } else if hoveredRecentPath == url.path {
            dismissRecentPreview()
        }
    }

    func setRecentHoverAnchor(_ url: URL, row: NSRect, host: NSWindow) {
        guard hoveredRecentPath == url.path else { return }
        let sameRow = recentHoverAnchor?.equalTo(row) ?? false
        let sameHost = recentHost === host
        recentHoverAnchor = row
        recentHost = host
        if sameRow, sameHost, recentPreview.isVisible { return }
        layoutRecentPreview()
    }

    func dismissRecentPreview() {
        hoveredRecentPath = nil
        recentHoverAnchor = nil
        recentHost = nil
        recentPreview.hide()
    }

    func copyRecentFile(_ url: URL) {
        let ext = url.pathExtension.lowercased()
        Task.detached(priority: .userInitiated) { [weak self] in
            let png = ext == "png" ? try? Data(contentsOf: url) : nil
            let path = ext == "png" ? nil : url.path
            let fileURL = url.absoluteString
            let owner = self
            await MainActor.run {
                owner?.writeRecentCopy(url: url, png: png, path: path, fileURL: fileURL)
            }
        }
    }

    private func writeRecentCopy(url: URL, png: Data?, path: String?, fileURL: String) {
        let board = NSPasteboard.general
        board.clearContents()
        let item = NSPasteboardItem()
        if let png {
            item.setData(png, forType: .png)
        }
        if let path {
            item.setString(path, forType: .string)
        }
        item.setString(fileURL, forType: .fileURL)
        guard board.writeObjects([item]) else { return }
        noteRecentCopied(url)
    }

    private func noteRecentCopied(_ url: URL) {
        copiedRecentPath = url.path
        copyFeedback?.cancel()
        copyFeedback = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled, self.copiedRecentPath == url.path else { return }
            self.copiedRecentPath = nil
        }
    }

    private func loadRecentThumbnails() {
        thumbnailLoad?.cancel()
        thumbnailToken += 1
        let token = thumbnailToken
        let jobs = recentFiles.filter { recentThumbnails[$0.path] == nil }
        guard !jobs.isEmpty else { return }
        thumbnailLoad = Task.detached(priority: .utility) { [weak self] in
            for url in jobs {
                if Task.isCancelled { return }
                let cg = RecentFileThumbnail.cgImage(for: url, maxPixel: 720)
                let owner = self
                await MainActor.run {
                    owner?.storeRecentThumbnail(cg, for: url, token: token)
                }
            }
        }
    }

    private func storeRecentThumbnail(_ cg: CGImage?, for url: URL, token: Int) {
        guard thumbnailToken == token else { return }
        guard recentFiles.contains(where: { $0.path == url.path }) else { return }
        guard let cg else { return }
        let scale: CGFloat = 2
        recentThumbnails[url.path] = NSImage(
            cgImage: cg,
            size: NSSize(width: CGFloat(cg.width) / scale, height: CGFloat(cg.height) / scale))
        if hoveredRecentPath == url.path {
            layoutRecentPreview()
        }
    }

    private func layoutRecentPreview() {
        guard let path = hoveredRecentPath,
              let url = recentFiles.first(where: { $0.path == path }),
              let row = recentHoverAnchor,
              let host = recentHost else {
            recentPreview.hide()
            return
        }
        recentPreview.present(
            name: url.lastPathComponent,
            image: recentThumbnails[path],
            row: row,
            host: host)
    }

    func reveal(_ url: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func openGuide() {
        guard let file = Bundle.main.resourceURL?.appendingPathComponent("phone-station/README.md") else { return }
        NSWorkspace.shared.open(file)
    }

    func beginNewCommand() {
        commandDraftToken += 1
        commandEditError = nil
        commandDraft = CommandDraft(id: nil, name: "", arguments: "")
        page = .editCommand
    }

    func beginEditCommand(_ command: SavedAdbCommand) {
        commandDraftToken += 1
        commandEditError = nil
        commandDraft = CommandDraft(id: command.id, name: command.name, arguments: command.arguments)
        page = .editCommand
    }

    func saveCommand() {
        let trimmedName = commandDraft.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedName.isEmpty {
            commandEditError = "先写名称。"
            return
        }
        let normalized = AdbCommandLine.normalize(commandDraft.arguments)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try AdbCommandLine.validate(normalized)
        } catch let error as AdbCommandLine.ParseError {
            commandEditError = error.message
            return
        } catch {
            commandEditError = "参数无法识别。"
            return
        }
        if let id = commandDraft.id, let index = commands.firstIndex(where: { $0.id == id }) {
            commands[index].name = trimmedName
            commands[index].arguments = normalized
        } else {
            commands.append(SavedAdbCommand(id: UUID(), name: trimmedName, arguments: normalized))
        }
        CommandStore.save(commands)
        commandEditError = nil
        page = .commands
    }

    func deleteCommand(id: UUID) {
        commands.removeAll { $0.id == id }
        CommandStore.save(commands)
        if commandOutput?.commandID == id { commandOutput = nil }
        page = .commands
    }

    func runCommand(_ command: SavedAdbCommand) {
        guard canOperate, let serial else { return }
        let tokens: [String]
        do {
            tokens = try AdbCommandLine.validate(command.arguments)
        } catch let error as AdbCommandLine.ParseError {
            commandOutput = CommandOutput(commandID: command.id, name: command.name, text: error.message)
            return
        } catch {
            commandOutput = CommandOutput(commandID: command.id, name: command.name, text: "参数无法识别。")
            return
        }
        guard let adb = StationRunner.executable("adb") else {
            commandOutput = CommandOutput(commandID: command.id, name: command.name, text: "未找到 adb。")
            return
        }
        activity = "正在执行「\(command.name)」…"
        notice = nil
        let name = command.name
        let id = command.id
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.capture(adb, ["-s", serial] + tokens, timeout: 15)
            let text = Self.commandResultText(result)
            await MainActor.run {
                self.activity = nil
                self.commandOutput = CommandOutput(commandID: id, name: name, text: text)
            }
        }
    }

    func copyCommandOutput() {
        guard let text = commandOutput?.text, !text.isEmpty else { return }
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(text, forType: .string)
    }

    func openDeviceShell() {
        guard canOperate, let serial, let adb = StationRunner.executable("adb") else { return }
        activity = "正在打开终端…"
        notice = nil
        let script = """
        on run argv
            if (count of argv) is less than 2 then error "缺少参数"
            set adbPath to item 1 of argv
            set deviceSerial to item 2 of argv
            tell application "Terminal"
                activate
                do script quoted form of adbPath & " -s " & quoted form of deviceSerial & " shell"
            end tell
        end run
        """
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.capture(
                URL(fileURLWithPath: "/usr/bin/osascript"),
                ["-e", script, adb.path, serial],
                timeout: 20)
            let failure = result.succeeded ? nil : (result.timedOut ? "打开终端超时。" : Self.reason(result.output, fallback: "无法打开终端。"))
            await MainActor.run {
                self.activity = nil
                if let failure {
                    self.notice = failure
                    self.commandOutput = CommandOutput(commandID: UUID(), name: "终端", text: failure)
                }
            }
        }
    }

    private nonisolated static func commandResultText(_ result: CommandResult) -> String {
        let tail = AdbCommandLine.displayedOutput(result.output)
        if result.timedOut {
            return tail.isEmpty ? "超过 15 秒，已停止。" : "超过 15 秒，已停止。\n\(tail)"
        }
        if tail.isEmpty {
            return result.succeeded ? "完成，没有输出。" : "命令没有完成。"
        }
        return tail
    }

    var dependencyDetails: [(String, Bool)] {
        [("adb", StationRunner.executable("adb") != nil),
         ("scrcpy", StationRunner.executable("scrcpy") != nil),
         ("python3", StationRunner.executable("python3") != nil)]
    }

    private nonisolated static func reason(_ output: String, fallback: String) -> String {
        let lines = output.split(separator: "\n").map(String.init)
        let relevant = lines.last { line in
            let normalized = line.lowercased()
            return normalized.contains("error") || normalized.contains("失败") || normalized.contains("找不到") ||
                normalized.contains("没有") || normalized.contains("无法") || normalized.contains("refused") ||
                normalized.contains("permission") || normalized.contains("no route")
        }
        return String((relevant ?? lines.last ?? fallback).prefix(220))
    }
}
