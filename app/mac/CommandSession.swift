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
final class CommandSession: ObservableObject {
    @Published private(set) var commands: [SavedAdbCommand] = []
    @Published private(set) var commandOutput: CommandOutput?
    @Published var commandDraft = CommandDraft(id: nil, name: "", arguments: "")
    @Published private(set) var commandEditError: String?
    @Published private(set) var commandDraftToken = 0

    var allow: () -> Bool = { false }
    var serial: () -> String? = { nil }
    var openPage: (StationPage) -> Void = { _ in }

    private let feedback: StationFeedback
    private var seenSerial: String?

    init(feedback: StationFeedback) {
        self.feedback = feedback
        commands = CommandStore.load()
    }

    func noteSerial(_ serial: String?) {
        if seenSerial != serial {
            seenSerial = serial
            commandOutput = nil
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
        CommandStore.save(commands)
        commandEditError = nil
        openPage(.commands)
    }

    func delete(id: UUID) {
        commands.removeAll { $0.id == id }
        CommandStore.save(commands)
        if commandOutput?.commandID == id { commandOutput = nil }
        openPage(.commands)
    }

    func run(_ command: SavedAdbCommand) {
        guard allow(), let serial = serial() else { return }
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

    func copyOutput() {
        guard let text = commandOutput?.text, !text.isEmpty else { return }
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(text, forType: .string)
    }

    func openDeviceShell() {
        guard allow(), let serial = serial(), let adb = StationRunner.executable("adb") else { return }
        feedback.activity = "正在打开终端…"
        feedback.notice = nil
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
