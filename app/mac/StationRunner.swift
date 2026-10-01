import Foundation

struct CommandResult {
    let code: Int32
    let output: String
    let timedOut: Bool

    var succeeded: Bool { code == 0 && !timedOut }
}

enum StationRunner {
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

enum StationText {
    static func reason(_ output: String, fallback: String) -> String {
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
