import AppKit
import Foundation

@MainActor
final class McpSession: ObservableObject {
    @Published private(set) var listening = false
    @Published private(set) var endpoint = ""
    @Published private(set) var token = ""
    @Published private(set) var channel = ""
    @Published private(set) var copied = false

    private let feedback: StationFeedback
    private var generation = 0
    private var statusInFlight = false
    private var statusTimer: Timer?
    private var copyFeedback: Task<Void, Never>?

    init(feedback: StationFeedback) {
        self.feedback = feedback
        statusTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshStatus() }
        }
    }

    var summary: String {
        listening ? "开着" : "关着"
    }

    func noteSerial(_ serial: String?) {
        refreshStatus()
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
                if result.succeeded {
                    self.listening = false
                    self.endpoint = ""
                    self.token = ""
                    self.channel = ""
                    self.feedback.notice = "MCP服务已停止。"
                } else {
                    self.feedback.notice = StationText.reason(result.output, fallback: "无法停止 MCP服务。")
                }
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

    private func refreshStatus() {
        guard feedback.activity == nil, !statusInFlight else { return }
        statusInFlight = true
        generation += 1
        let ticket = generation
        Task.detached(priority: .utility) {
            let result = StationRunner.scriptResult("mcp.sh", ["status"], timeout: 20)
            await MainActor.run {
                self.statusInFlight = false
                guard ticket == self.generation else { return }
                guard self.feedback.activity == nil else { return }
                if result.succeeded {
                    self.applyStatus(result.output)
                } else {
                    self.listening = false
                    self.endpoint = ""
                    self.token = ""
                    self.channel = ""
                }
            }
        }
    }

    private func apply(_ output: String) {
        let lines = output.split(separator: "\n").map(String.init)
        endpoint = lines.first { $0.hasPrefix("http://") } ?? ""
        token = lines.first { $0.hasPrefix("Authorization: Bearer ") }
            .map { String($0.dropFirst("Authorization: Bearer ".count)) } ?? ""
        channel = lines.first { $0.hasPrefix("channel=") }
            .map { String($0.dropFirst("channel=".count).split(separator: " ").first ?? "") } ?? ""
        listening = !endpoint.isEmpty
    }

    private func applyStatus(_ output: String) {
        let lines = output.split(separator: "\n").map(String.init)
        guard lines.first?.hasPrefix("http://") == true else {
            listening = false
            endpoint = ""
            token = ""
            channel = ""
            return
        }
        endpoint = lines[0]
        token = lines.first { $0.hasPrefix("Authorization: Bearer ") }
            .map { String($0.dropFirst("Authorization: Bearer ".count)) } ?? ""
        channel = lines.first { $0.hasPrefix("channel=") }
            .map { String($0.dropFirst("channel=".count).split(separator: " ").first ?? "") } ?? ""
        listening = true
    }
}
