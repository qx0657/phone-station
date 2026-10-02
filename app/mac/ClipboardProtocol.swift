import Foundation

struct ClipboardClip: Codable, Equatable {
    var kind: String
    var text: String?
    static let empty = ClipboardClip(kind: "empty", text: nil)
    var preview: String {
        switch kind {
        case "text": return text?.isEmpty == false ? text! : "空文字"
        case "sensitive": return "敏感内容已跳过"
        case "unsupported": return "当前是图片或文件，仅支持文字"
        case "oversize": return "文字超过 100000 字，已跳过"
        case "locked": return "手机已锁定，解锁后继续"
        default: return "剪贴板为空"
        }
    }
}

struct ClipboardSnapshot: Decodable {
    var shared: Bool
    var automatic: Bool
    var phone: ClipboardClip?
    var phoneVersion: String
    var macOnline: Bool
    var appliedMac: Bool?
    var error: String?
}

/// Never apply a reply over a newer local copy or replay clipboard data after reconnecting.
struct ClipboardSyncPolicy {
    private(set) var phoneVersion: String?
    mutating func disconnect() { phoneVersion = nil }
    mutating func receive(_ snapshot: ClipboardSnapshot, sentCount: Int, currentCount: Int,
                          current: ClipboardClip, baseline: Bool) -> String? {
        defer { phoneVersion = snapshot.phoneVersion }
        guard !baseline, snapshot.shared, snapshot.automatic, snapshot.error == nil,
              let prior = phoneVersion, prior != snapshot.phoneVersion,
              sentCount == currentCount, snapshot.appliedMac != true,
              current.kind == "text" || current.kind == "empty",
              snapshot.phone?.kind == "text", let text = snapshot.phone?.text,
              current.text != text else { return nil }
        return text
    }
}

enum ClipboardRPC {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }
    static func call(endpoint: String, token: String, name: String, arguments: [String: Any]) async throws -> Data {
        guard let url = URL(string: endpoint), url.host == "127.0.0.1", url.path == "/mcp", !token.isEmpty else {
            throw Failure(message: "请先连接 MCP 服务")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "jsonrpc": "2.0", "id": UUID().uuidString, "method": "tools/call",
            "params": ["name": name, "arguments": arguments]
        ])
        let configuration = URLSessionConfiguration.ephemeral
        configuration.connectionProxyDictionary = [:]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let rpc = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = rpc["result"] as? [String: Any],
              let content = result["content"] as? [[String: Any]],
              let text = content.first(where: { $0["type"] as? String == "text" })?["text"] as? String else {
            throw Failure(message: "剪贴板暂时未连接，请检查 MCP 服务和手机版本")
        }
        if result["isError"] as? Bool == true {
            let detail = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any]
            throw Failure(message: detail?["error"] as? String ?? "请更新手机工位后重试")
        }
        return Data(text.utf8)
    }
}
