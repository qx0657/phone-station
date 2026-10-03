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
    private var inFlight = false
    private var timer: Timer?
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
                Task { @MainActor in self?.refresh() }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
    }
    func refresh() {
        guard let (endpoint, token) = route() else { issues = []; failure = nil; return }
        guard !inFlight else { return }
        inFlight = true
        Task {
            defer { inFlight = false }
            do {
                let data = try await rpc(endpoint, token, "station_device_status", [:])
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
