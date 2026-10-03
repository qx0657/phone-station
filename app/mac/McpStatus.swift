import Foundation

/// 网关已探测到的通道；服务开关或配对资料本身不代表手机在线。
struct McpStatus {
    let endpoint: String
    let token: String
    let channel: String
    let remoteConnected: Bool
    let localOnline: Bool?
    let gatewayReady: Bool
    let remoteConfigured: Bool?
    let remoteChecking: Bool

    let listening: Bool
    let requestAvailable: Bool

    init(_ output: String) {
        if let data = output.data(using: .utf8),
           let value = try? JSONDecoder().decode(Snapshot.self, from: data) {
            let remoteVerified = value.remoteOnline && (value.remoteVerified ?? true)
            let available = value.ready && ((value.mode == "local" && value.localOnline)
                || (value.mode == "remote" && remoteVerified))
            // A busy relay can lose freshness without losing its actual route.
            let dispatchable = available || (value.ready && value.mode == "remote" && value.remoteOnline && value.remoteChecking == true)
            gatewayReady = value.ready
            endpoint = dispatchable ? (value.endpoint ?? "") : ""
            token = dispatchable ? (value.token ?? "") : ""
            channel = dispatchable ? value.mode : ""
            listening = available && !endpoint.isEmpty
            requestAvailable = dispatchable && !endpoint.isEmpty && !token.isEmpty
            remoteConnected = value.ready && value.remoteOnline && (value.remoteVerified ?? true)
            remoteConfigured = value.remoteConfigured
            remoteChecking = value.ready && (value.remoteChecking ?? false)
            localOnline = value.ready ? value.localOnline : nil
            return
        }
        localOnline = nil
        remoteConfigured = nil
        remoteChecking = false
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
            listening = false
            requestAvailable = false
            return
        }
        self.endpoint = endpoint
        token = lines.first { $0.hasPrefix("Authorization: Bearer ") }
            .map { String($0.dropFirst("Authorization: Bearer ".count)) } ?? ""
        self.channel = channel
        listening = !endpoint.isEmpty
        requestAvailable = listening && !token.isEmpty
        remoteConnected = channel == "remote" || fields.contains("relay=online")
    }

    private struct Snapshot: Decodable {
        var ready: Bool
        var mode: String
        var localOnline: Bool
        var remoteOnline: Bool
        var endpoint: String?
        var token: String?
        var remoteConfigured: Bool?
        var remoteVerified: Bool?
        var remoteChecking: Bool?
    }
}
