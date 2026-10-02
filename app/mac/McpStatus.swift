import Foundation

/// 网关已探测到的通道；服务开关或配对资料本身不代表手机在线。
struct McpStatus {
    let endpoint: String
    let token: String
    let channel: String
    let remoteConnected: Bool
    let localOnline: Bool?
    let gatewayReady: Bool

    var listening: Bool { !endpoint.isEmpty }

    init(_ output: String) {
        if let data = output.data(using: .utf8),
           let value = try? JSONDecoder().decode(Snapshot.self, from: data) {
            let available = value.ready && (value.mode == "local" || value.mode == "remote")
            gatewayReady = value.ready
            endpoint = available ? (value.endpoint ?? "") : ""
            token = available ? (value.token ?? "") : ""
            channel = available ? value.mode : ""
            remoteConnected = available && value.remoteOnline
            localOnline = value.ready ? value.localOnline : nil
            return
        }
        localOnline = nil
        gatewayReady = output.contains("http://")
        let lines = output.split(separator: "\n").map(String.init)
        let fields = lines.first { $0.hasPrefix("channel=") }?.split(separator: " ") ?? []
        let channel = fields.first.map { String($0.dropFirst("channel=".count)) } ?? ""
        guard let endpoint = lines.first(where: { $0.hasPrefix("http://") }),
              channel.isEmpty || channel == "local" || channel == "remote" else {
            self.endpoint = ""
            token = ""
            self.channel = ""
            remoteConnected = false
            return
        }
        self.endpoint = endpoint
        token = lines.first { $0.hasPrefix("Authorization: Bearer ") }
            .map { String($0.dropFirst("Authorization: Bearer ".count)) } ?? ""
        self.channel = channel
        remoteConnected = channel == "remote" || fields.contains("relay=online")
    }

    private struct Snapshot: Decodable {
        var ready: Bool
        var mode: String
        var localOnline: Bool
        var remoteOnline: Bool
        var endpoint: String?
        var token: String?
    }
}
