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

    static func capture(_ executable: URL, _ arguments: [String], timeout: Int = 15,
                        input: Data? = nil) -> CommandResult {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                    attributes: [.posixPermissions: 0o700])
        } catch {
            return CommandResult(code: -1, output: "无法创建任务目录。", timedOut: false)
        }
        defer { try? FileManager.default.removeItem(at: directory) }
        let log = directory.appendingPathComponent("output")
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
        // Keep credential input in memory. Nonblocking writes share the command
        // deadline, so a child refusing stdin cannot stall before the timeout.
        let inputPipe = input.map { _ in Pipe() }
        if let inputPipe {
            let fd = inputPipe.fileHandleForWriting.fileDescriptor
            let flags = fcntl(fd, F_GETFL)
            guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0,
                  fcntl(fd, F_SETNOSIGPIPE, 1) == 0 else {
                try? writer.close()
                return CommandResult(code: -1, output: "无法准备任务输入。", timedOut: false)
            }
        }
        defer { try? inputPipe?.fileHandleForReading.close(); try? inputPipe?.fileHandleForWriting.close() }
        process.standardInput = inputPipe?.fileHandleForReading ?? FileHandle.nullDevice
        let finished = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in finished.signal() }
        do {
            try process.run()
        } catch {
            try? writer.close()
            try? FileManager.default.removeItem(at: log)
            return CommandResult(code: -1, output: error.localizedDescription, timedOut: false)
        }
        // macOS Foundation gives a launched Process its own group. Verify this
        // before sending a group signal; never signal the app's own group.
        let pid = process.processIdentifier
        let isolatedGroup = getpgid(pid) == pid && pid != getpgrp()
        let deadline = DispatchTime.now() + .seconds(timeout)
        if let input, let inputPipe {
            try? inputPipe.fileHandleForReading.close()
            let fd = inputPipe.fileHandleForWriting.fileDescriptor
            input.withUnsafeBytes { buffer in
                var sent = 0
                while sent < buffer.count && process.isRunning && DispatchTime.now() < deadline {
                    let count = write(fd, buffer.baseAddress!.advanced(by: sent), min(65536, buffer.count - sent))
                    if count > 0 { sent += count }
                    else if count < 0 && errno == EINTR { continue }
                    else if count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) { usleep(10_000) }
                    else { break }
                }
            }
            try? inputPipe.fileHandleForWriting.close()
        }
        let timedOut = finished.wait(timeout: deadline) == .timedOut
        if timedOut {
            if isolatedGroup {
                kill(-pid, SIGTERM)
                let deadline = DispatchTime.now() + .seconds(2)
                _ = finished.wait(timeout: deadline)
                // The shell may exit before a TERM-resistant child. Continue
                // checking the group even after the leader has been reaped.
                while kill(-pid, 0) == 0 && DispatchTime.now() < deadline { usleep(20_000) }
                if kill(-pid, 0) == 0 { kill(-pid, SIGKILL) }
            } else {
                process.terminate()
                if finished.wait(timeout: .now() + .seconds(2)) == .timedOut { kill(pid, SIGKILL) }
            }
            if process.isRunning {
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

    static func scriptResult(_ name: String, _ arguments: [String] = [], timeout: Int = 30,
                             input: Data? = nil) -> CommandResult {
        capture(URL(fileURLWithPath: "/bin/zsh"), [script(name).path] + arguments, timeout: timeout, input: input)
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
