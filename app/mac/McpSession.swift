import AppKit
import Foundation

@MainActor
final class McpSession: ObservableObject {
    @Published private(set) var listening = false
    @Published private(set) var endpoint = ""
    @Published private(set) var token = ""
    @Published private(set) var copied = false

    private let feedback: StationFeedback
    private var serial: String?
    private var generation = 0
    private var copyFeedback: Task<Void, Never>?
    nonisolated private static let localPort = "18765"
    nonisolated private static let remotePort = "8765"

    init(feedback: StationFeedback) {
        self.feedback = feedback
    }

    var summary: String {
        listening ? "开着" : "关着"
    }

    func noteSerial(_ serial: String?) {
        self.serial = serial
        guard let serial else {
            generation += 1
            listening = false
            endpoint = ""
            token = ""
            return
        }
        guard feedback.activity == nil else { return }
        sync(serial)
    }

    func start() {
        guard feedback.activity == nil else { return }
        feedback.activity = "正在打开 MCP服务…"
        feedback.notice = nil
        generation += 1
        let ticket = generation
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("mcp.sh", timeout: 40)
            await MainActor.run {
                guard ticket == self.generation else { return }
                self.feedback.activity = nil
                if result.succeeded {
                    self.apply(result.output)
                    self.feedback.notice = "MCP服务已打开。"
                } else {
                    self.feedback.notice = result.timedOut
                        ? "MCP服务没有在时限内应答。"
                        : StationText.reason(result.output, fallback: "无法打开 MCP服务。")
                }
            }
        }
    }

    func stop() {
        guard feedback.activity == nil else { return }
        feedback.activity = "正在停止 MCP服务…"
        feedback.notice = nil
        generation += 1
        let ticket = generation
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("mcp.sh", ["stop"], timeout: 20)
            await MainActor.run {
                guard ticket == self.generation else { return }
                self.feedback.activity = nil
                self.listening = false
                self.endpoint = ""
                self.token = ""
                self.feedback.notice = result.succeeded
                    ? "MCP服务已停止。"
                    : StationText.reason(result.output, fallback: "无法停止 MCP服务。")
            }
        }
    }

    func copyToken() {
        guard !token.isEmpty else { return }
        let board = NSPasteboard.general
        board.clearContents()
        board.setString("Authorization: Bearer \(token)", forType: .string)
        copied = true
        copyFeedback?.cancel()
        copyFeedback = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            self.copied = false
        }
    }

    private func sync(_ serial: String) {
        generation += 1
        let ticket = generation
        Task.detached(priority: .utility) {
            let reading = Self.read(serial)
            await MainActor.run {
                guard ticket == self.generation, self.serial == serial, self.feedback.activity == nil else { return }
                self.listening = reading.listening
                self.endpoint = reading.endpoint
                if reading.token != self.token { self.token = reading.token }
            }
        }
    }

    private func apply(_ output: String) {
        let lines = output.split(separator: "\n").map(String.init)
        endpoint = lines.first { $0.hasPrefix("http://") } ?? ""
        token = lines.first { $0.hasPrefix("Authorization: Bearer ") }
            .map { String($0.dropFirst("Authorization: Bearer ".count)) } ?? ""
        listening = !endpoint.isEmpty
    }

    private nonisolated static func read(_ serial: String) -> (listening: Bool, endpoint: String, token: String) {
        guard let adb = StationRunner.executable("adb") else {
            return (false, "", "")
        }
        let flag = StationRunner.capture(
            adb,
            ["-s", serial, "shell", "settings", "get", "global", "phonestation_mcp"],
            timeout: 8)
        let value = flag.output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard flag.succeeded, value == "1" else {
            _ = StationRunner.capture(adb, ["-s", serial, "forward", "--remove", "tcp:\(localPort)"], timeout: 8)
            return (false, "", "")
        }
        _ = StationRunner.capture(
            adb,
            ["-s", serial, "forward", "tcp:\(localPort)", "tcp:\(remotePort)"],
            timeout: 8)
        let log = StationRunner.capture(
            adb,
            ["-s", serial, "shell", "logcat", "-d", "-t", "80", "-s", "StationMcp:I"],
            timeout: 8)
        let token = log.output.split(separator: "\n").compactMap { line -> String? in
            guard let range = line.range(of: "mcp token ") else { return nil }
            return String(line[range.upperBound...]).trimmingCharacters(in: .whitespaces)
        }.last ?? ""
        return (true, "http://127.0.0.1:\(localPort)/mcp", token)
    }
}
