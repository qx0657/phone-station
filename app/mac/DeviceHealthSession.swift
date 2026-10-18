import Foundation

struct DeviceIssue: Decodable, Identifiable, Equatable {
    var id: String
    var title: String
    var detail: String
    var destination: String
}

/// Only reads device health; never reads clipboard text, polls notifications, or requests permission.
@MainActor
final class DeviceHealthSession: ObservableObject {
    @Published private(set) var issues: [DeviceIssue] = []
    @Published private(set) var failure: String?
    var route: () -> (String, String)? = { nil }
    var remote: () -> Bool = { false }
    /// 链路状态快照。抛错或正文没有 issues 时，空闲轮询仍走 device_status。
    var states: () async throws -> Data = { throw CancellationError() }
    private var inFlight = false
    private var carriesIssues = false
    private var seenIssues: [DeviceIssue]?
    private var timer: Timer?
    private var nextPoll = Date.distantPast
    private let rpc: (String, String, String, [String: Any]) async throws -> Data
    private struct Report: Decodable {
        struct Health: Decodable { var issues: [DeviceIssue] }
        var health: Health?
    }
    init(startTimer: Bool = true,
         rpc: ((String, String, String, [String: Any]) async throws -> Data)? = nil) {
        self.rpc = rpc ?? ClipboardRPC.call
        if startTimer {
            let timer = Timer(timeInterval: 3, repeats: true) { [weak self] _ in
                Task { @MainActor in await self?.idle() }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
    }
    /// 定时器的真实入口。有 issues 的链路快照在，就不再向手机要 device_status。
    func idle() async {
        await pullStates()
        refresh(background: true)
    }

    func pullStates() async {
        do {
            let data = try await states()
            if let issues = Self.carriedIssues(data) {
                carriesIssues = true
                if issues != seenIssues {
                    seenIssues = issues
                    self.issues = issues
                    failure = nil
                }
                return
            }
        } catch {}
        carriesIssues = false
        seenIssues = nil
    }

    static func carriedIssues(_ data: Data) -> [DeviceIssue]? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let device = object["device"] as? [String: Any],
              let raw = device["issues"] else { return nil }
        guard let rows = raw as? [[String: Any]] else { return nil }
        return rows.compactMap { item in
            guard let id = item["id"] as? String, let title = item["title"] as? String,
                  let detail = item["detail"] as? String, let destination = item["destination"] as? String else { return nil }
            return DeviceIssue(id: id, title: title, detail: detail, destination: destination)
        }
    }

    func refresh(background: Bool = false) {
        guard let (endpoint, token) = route() else { issues = []; failure = nil; nextPoll = .distantPast; return }
        if background && carriesIssues { return }
        guard !background || Date() >= nextPoll else { return }
        guard !inFlight else { return }
        inFlight = true
        Task {
            defer { inFlight = false; nextPoll = Date().addingTimeInterval(remote() ? 15 : 3) }
            do {
                let data = try await rpc(endpoint, token, "device_status", [:])
                guard let current = route(), current.0 == endpoint, current.1 == token else { return }
                let report = try JSONDecoder().decode(Report.self, from: data)
                issues = report.health?.issues ?? []
                failure = report.health == nil ? "请更新手机工位，以检查功能权限和运行状态。" : nil
            } catch {
                guard let current = route(), current.0 == endpoint, current.1 == token else { return }
                issues = []
                failure = "暂时无法核对手机功能状态，请检查 MCP 服务。"
            }
        }
    }
}
