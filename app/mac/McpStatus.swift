import Foundation

/// Presentation follows confirmed routes; absent, unused routes are not faults.
struct McpServicePresentation {
    let summary: String
    let detail: String
    let needsAttention: Bool
    let available: Bool

    init(paused: Bool, gatewayReady: Bool, listening: Bool, localOnline: Bool,
         localExpected: Bool, remoteOnline: Bool, remoteConfigured: Bool?, remoteChecking: Bool, closed: Bool = false) {
        available = !paused && !closed && gatewayReady && listening
        if paused {
            summary = "已暂停"
            detail = "在手机首页恢复使用，连接与功能选择会保留。"
            needsAttention = false
        } else if closed {
            summary = "已关闭"
            detail = "在手机「MCP 服务」中选择本地接入或远程连接。"
            needsAttention = false
        } else if !gatewayReady {
            summary = "未启动"
            detail = "连接 MCP 服务后，电脑可调用手机工具。"
            needsAttention = false
        } else if listening {
            let localMissing = localExpected && !localOnline
            let remoteMissing = remoteConfigured == true && !remoteOnline
            needsAttention = localMissing || remoteMissing
            if needsAttention {
                let missing = [localMissing ? "本地未连通" : nil,
                               remoteMissing ? (remoteChecking ? "远程确认中" : "远程未连通") : nil].compactMap { $0 }
                summary = "部分可用 · " + missing.joined(separator: "、")
                detail = "可继续使用已连通的通道；在连接设置中查看未连通的接入。"
            } else {
                summary = "运行中 · " + (localOnline && remoteOnline ? "本地与远程" : remoteOnline ? "远程" : "本地")
                detail = "服务已可用，具体能力仍需手机选择与权限就绪。"
            }
        } else {
            summary = remoteChecking ? "确认连接中" : "等待手机"
            detail = "查看连接设置，确认本地或远程接入已就绪。"
            needsAttention = remoteConfigured == true && !remoteChecking
        }
    }
}

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
