import AppKit
import Foundation

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

    static func load(_ defaults: UserDefaults) -> [SavedAdbCommand] {
        if defaults.object(forKey: key) == nil {
            let seeded = Self.defaults
            save(seeded, to: defaults)
            return seeded
        }
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([SavedAdbCommand].self, from: data) else {
            return []
        }
        return decoded
    }

    static func save(_ commands: [SavedAdbCommand], to defaults: UserDefaults) {
        guard let data = try? JSONEncoder().encode(commands) else { return }
        defaults.set(data, forKey: key)
    }

    private static let defaults = [
        SavedAdbCommand(id: foregroundID, name: "前台应用",
                        arguments: "shell \"dumpsys window | grep mCurrentFocus\""),
        SavedAdbCommand(id: wakeID, name: "唤醒",
                        arguments: "shell input keyevent KEYCODE_WAKEUP"),
    ]
}

@MainActor
final class CommandSession: ObservableObject {
    @Published private(set) var commands: [SavedAdbCommand] = []
    @Published private(set) var commandOutput: CommandOutput?
    @Published var commandDraft = CommandDraft(id: nil, name: "", arguments: "")
    @Published private(set) var commandEditError: String?
    @Published private(set) var commandDraftToken = 0
    struct PendingCommand: Codable {
        var jobID: String
        var commandID: UUID
        var name: String
        var endpoint: String
    }
    @Published private(set) var pendingCommand: PendingCommand?
    @Published private(set) var remoteFailure: String?
    @Published private var remoteShell: ShellStatus?

    var allow: () -> Bool = { false }
    var serial: () -> String? = { nil }
    var remoteRoute: () -> (String, String)? = { nil }
    var recoveryRoute: () -> (String, String)? = { nil }
    var openPage: (StationPage) -> Void = { _ in }
    var monitoring = false

    private let feedback: StationFeedback
    private let defaults: UserDefaults
    private static let pendingKey = "phoneStationPendingCommand"
    private let rpc: (String, String, String, [String: Any]) async throws -> Data
    private var seenSerial: String?
    private var timer: Timer?
    private var nextPoll = Date.distantPast
    private var remoteReadAt = Date.distantPast
    private var remoteReadRoute: (String, String)?
    private var readInFlight = false
    private var readEpoch = 0
    private var running = false

    private struct ModelStatus: Decodable {
        var model: String
        var verified: Bool
        var supported: Bool { verified && model.replacingOccurrences(of: "_", with: "-") == "PGT-AN20" }
    }
    private struct ShellStatus: Decodable {
        var available: Bool
        var reason: String
        var terminalSupported: Bool?
        var terminalProtocol: Int?
    }
    private struct ShellResult: Decodable {
        var stdout: String
        var stderr: String
        var exitCode: Int?
        var timedOut: Bool
        var outputTruncated: Bool
        var outputIncomplete: Bool?
    }
    private struct Receipt: Decodable {
        var jobId: String
        var kind: String?
        var state: String
        var result: ShellResult?
        var error: String?
    }

    init(feedback: StationFeedback, startTimer: Bool = true, defaults: UserDefaults = .standard,
         rpc: ((String, String, String, [String: Any]) async throws -> Data)? = nil) {
        self.feedback = feedback
        self.defaults = defaults
        self.rpc = rpc ?? { endpoint, token, name, arguments in
            try await ClipboardRPC.call(endpoint: endpoint, token: token, name: name, arguments: arguments, timeout: 25)
        }
        commands = CommandStore.load(defaults)
        if let data = defaults.data(forKey: Self.pendingKey),
           let pending = try? JSONDecoder().decode(PendingCommand.self, from: data),
           pending.jobID.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil {
            pendingCommand = pending
        }
        if startTimer {
            let timer = Timer(timeInterval: 4, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.monitoring, Date() >= self.nextPoll else { return }
                    self.refreshRemote()
                }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
    }

    func noteSerial(_ serial: String?) {
        if seenSerial != serial {
            seenSerial = serial
            commandOutput = nil
            refreshRemote()
        }
    }

    func beginNew() {
        commandDraftToken += 1
        commandEditError = nil
        commandDraft = CommandDraft(id: nil, name: "", arguments: "")
        openPage(.editCommand)
    }

    func beginEdit(_ command: SavedAdbCommand) {
        commandDraftToken += 1
        commandEditError = nil
        commandDraft = CommandDraft(id: command.id, name: command.name, arguments: command.arguments)
        openPage(.editCommand)
    }

    func save() {
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
        CommandStore.save(commands, to: defaults)
        commandEditError = nil
        openPage(.commands)
    }

    func delete(id: UUID) {
        commands.removeAll { $0.id == id }
        CommandStore.save(commands, to: defaults)
        if commandOutput?.commandID == id { commandOutput = nil }
        openPage(.commands)
    }

    func run(_ command: SavedAdbCommand) {
        guard disabledReason(for: command) == nil else { return }
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
        guard let serial = serial() else {
            guard let (endpoint, token) = remoteRoute(), let shell = try? AdbCommandLine.remoteShell(tokens) else { return }
            let pending = PendingCommand(jobID: UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
                                         commandID: command.id, name: command.name, endpoint: endpoint)
            rememberPending(pending)
            performRemote(pending, token: token, command: shell)
            return
        }
        guard let adb = StationRunner.executable("adb") else {
            commandOutput = CommandOutput(commandID: command.id, name: command.name, text: "未找到 adb。")
            return
        }
        feedback.activity = "正在执行「\(command.name)」…"
        feedback.notice = nil
        let name = command.name
        let id = command.id
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.capture(adb, ["-s", serial] + tokens, timeout: 15)
            let text = Self.commandResultText(result)
            await MainActor.run {
                self.feedback.activity = nil
                self.commandOutput = CommandOutput(commandID: id, name: name, text: text)
            }
        }
    }

    func disabledReason(for command: SavedAdbCommand) -> String? {
        let tokens: [String]
        do { tokens = try AdbCommandLine.validate(command.arguments) }
        catch let error as AdbCommandLine.ParseError { return error.message }
        catch { return "参数无法识别。" }
        if feedback.activity != nil || running { return "正在执行其他操作…" }
        if serial() != nil { return allow() ? nil : "暂时无法使用本地 adb。" }
        do { _ = try AdbCommandLine.remoteShell(tokens) }
        catch let error as AdbCommandLine.ParseError { return error.message }
        catch { return "参数无法识别。" }
        return remoteCommandReason
    }

    private var remoteCommandReason: String? {
        if feedback.activity != nil || running { return "正在执行其他操作…" }
        if pendingCommand != nil { return "上次远程命令结果未确认，请先查询原任务。" }
        guard let route = remoteRoute() else { return "请先连接手机。" }
        if let remoteFailure { return remoteFailure }
        guard let readRoute = remoteReadRoute, route.0 == readRoute.0, route.1 == readRoute.1,
              Date().timeIntervalSince(remoteReadAt) < 30, let remoteShell else {
            return "正在检查远程 shell 状态…"
        }
        return remoteShell.available ? nil : (remoteShell.reason.isEmpty ? "Shizuku 尚不可用。" : remoteShell.reason)
    }

    var canOpenShell: Bool {
        if serial() != nil { return allow() && feedback.activity == nil }
        guard remoteCommandReason == nil else { return false }
        return remoteShell?.terminalSupported == true && remoteShell?.terminalProtocol == 1
    }
    var shellDetail: String {
        if serial() != nil { return "在「终端」里打开，序列号已经绑上" }
        if let reason = remoteCommandReason { return reason }
        if remoteShell?.terminalSupported != true || remoteShell?.terminalProtocol != 1 { return "远程终端需要手机工位 61 或更新版本" }
        return "远程 Shizuku shell，支持连续输入与 Ctrl-C"
    }
    var shellTitle: String { serial() == nil && remoteRoute() != nil ? "在终端中打开远程 shell" : "在终端中打开 shell" }

    /// Only capability reads; opening this page never submits a shell command.
    func refreshRemote() {
        guard let (endpoint, token) = remoteRoute(), serial() == nil else {
            remoteShell = nil; remoteReadRoute = nil; remoteFailure = nil; readEpoch += 1
            return
        }
        guard !readInFlight, !running, feedback.activity == nil else { return }
        readInFlight = true
        let epoch = readEpoch
        Task {
            defer { readInFlight = false; nextPoll = Date().addingTimeInterval(10) }
            do {
                let modelData = try await rpc(endpoint, token, "station_controls_status", [:])
                guard matchesRoute(endpoint, token, epoch: epoch) else { return }
                let model = try JSONDecoder().decode(ModelStatus.self, from: modelData)
                guard model.supported else {
                    remoteShell = nil
                    remoteFailure = "当前机型 \(model.model) 尚未验证，远程命令未启用。"
                    return
                }
                let data = try await rpc(endpoint, token, "station_shell_status", [:])
                guard matchesRoute(endpoint, token, epoch: epoch) else { return }
                remoteShell = try JSONDecoder().decode(ShellStatus.self, from: data)
                remoteReadAt = Date(); remoteReadRoute = (endpoint, token); remoteFailure = nil
            } catch {
                guard matchesRoute(endpoint, token, epoch: epoch) else { return }
                remoteShell = nil
                remoteFailure = "无法核对远程 shell 状态，请检查连接或更新手机工位。"
            }
        }
    }

    private func matchesRoute(_ endpoint: String, _ token: String, epoch: Int) -> Bool {
        guard epoch == readEpoch, serial() == nil, let route = remoteRoute() else { return false }
        return route.0 == endpoint && route.1 == token
    }

    var canRecoverCommand: Bool {
        guard !running, feedback.activity == nil, let pendingCommand,
              let route = recoveryRoute() ?? remoteRoute() else { return false }
        return pendingCommand.endpoint == route.0
    }

    func recoverCommand() {
        guard canRecoverCommand, let pendingCommand, let (_, token) = recoveryRoute() ?? remoteRoute() else { return }
        performRemote(pendingCommand, token: token, command: nil)
    }

    func finishFollowingCommand() {
        guard !running, feedback.activity == nil, let pendingCommand else { return }
        commandOutput = CommandOutput(commandID: pendingCommand.commandID, name: pendingCommand.name,
            text: "已结束跟进，原任务可能已执行。\n任务编号：\(pendingCommand.jobID)")
        rememberPending(nil)
    }

    private func rememberPending(_ value: PendingCommand?) {
        pendingCommand = value
        if let value, let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: Self.pendingKey) }
        else { defaults.removeObject(forKey: Self.pendingKey) }
    }

    private func receipt(_ data: Data, for pending: PendingCommand) throws -> Receipt {
        let receipt = try JSONDecoder().decode(Receipt.self, from: data)
        guard receipt.jobId == pending.jobID, receipt.kind == nil || receipt.kind == "shell" else {
            throw ClipboardRPC.Failure(message: "任务回执不匹配")
        }
        return receipt
    }

    /// Save before submitting. A lost start reply is followed only by status queries of this ID.
    private func performRemote(_ pending: PendingCommand, token: String, command: String?) {
        running = true; readEpoch += 1
        feedback.activity = command == nil ? "正在查询原远程任务…" : "正在远程执行「\(pending.name)」…"
        feedback.notice = nil
        Task {
            defer { feedback.activity = nil; running = false; refreshRemote() }
            do {
                let deadline = ProcessInfo.processInfo.systemUptime + 60
                var current: Receipt?
                if let command {
                    do {
                        let data = try await rpc(pending.endpoint, token, "station_shell_start", [
                            "jobId": pending.jobID, "command": command, "timeoutMs": 15000, "maxOutputBytes": 32768
                        ])
                        current = try receipt(data, for: pending)
                    } catch {
                        // The phone may already have accepted it; never submit or fall back to adb again.
                    }
                }
                while current == nil || current?.state == "queued" || current?.state == "running" {
                    guard ProcessInfo.processInfo.systemUptime < deadline else {
                        throw ClipboardRPC.Failure(message: "等待任务结果超时")
                    }
                    if current != nil { try await Task.sleep(nanoseconds: 500_000_000) }
                    let data = try await rpc(pending.endpoint, token, "station_operation_status", ["jobId": pending.jobID])
                    current = try receipt(data, for: pending)
                }
                guard let current else { throw ClipboardRPC.Failure(message: "任务回执无效") }
                let text: String
                if current.state == "completed", let result = current.result {
                    text = Self.remoteResultText(result)
                    rememberPending(nil)
                } else if current.state == "rejected" || current.state == "expired" {
                    text = "\(current.error ?? "任务未执行。")\n任务编号：\(pending.jobID)"
                    rememberPending(nil)
                } else {
                    throw ClipboardRPC.Failure(message: current.error ?? "执行结果未知")
                }
                commandOutput = CommandOutput(commandID: pending.commandID, name: pending.name, text: text)
            } catch {
                commandOutput = CommandOutput(commandID: pending.commandID, name: pending.name,
                    text: "\(error.localizedDescription)；保留原任务编号，查询结果后再决定下一步。\n任务编号：\(pending.jobID)")
            }
        }
    }

    private static func remoteResultText(_ result: ShellResult) -> String {
        var notes: [String] = []
        if result.timedOut { notes.append("超过 15 秒，手机已停止命令。") }
        if let code = result.exitCode, code != 0, !result.timedOut { notes.append("命令退出码：\(code)") }
        if result.exitCode == nil, !result.timedOut { notes.append("命令退出状态未确认。") }
        if result.outputTruncated || result.outputIncomplete == true { notes.append("输出不完整。") }
        let tail = AdbCommandLine.displayedOutput([result.stdout, result.stderr].filter { !$0.isEmpty }.joined(separator: "\n"),
                                                 maxLines: 12 - notes.count)
        if !tail.isEmpty { notes.append(tail) }
        return notes.isEmpty ? "完成，没有输出。" : notes.joined(separator: "\n")
    }

    func copyOutput() {
        guard let text = commandOutput?.text, !text.isEmpty else { return }
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(text, forType: .string)
    }

    func openDeviceShell() {
        guard canOpenShell else { return }
        let arguments: [String]
        let body: String
        if let serial = serial() {
            guard let adb = StationRunner.executable("adb") else { return }
            arguments = [adb.path, serial]
            body = "do script quoted form of item 1 of argv & \" -s \" & quoted form of item 2 of argv & \" shell\""
        } else {
            arguments = [StationRunner.script("terminal.sh").path]
            body = "do script quoted form of item 1 of argv"
        }
        feedback.activity = "正在打开终端…"
        feedback.notice = nil
        let script = """
        on run argv
            tell application "Terminal"
                activate
                \(body)
            end tell
        end run
        """
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.capture(
                URL(fileURLWithPath: "/usr/bin/osascript"),
                ["-e", script] + arguments,
                timeout: 20)
            let failure = result.succeeded ? nil : (result.timedOut ? "打开终端超时。" : StationText.reason(result.output, fallback: "无法打开终端。"))
            await MainActor.run {
                self.feedback.activity = nil
                if let failure {
                    self.feedback.notice = failure
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
}
