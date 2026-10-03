import Foundation

struct ClipboardClip: Codable, Equatable {
    var kind: String
    var text: String?
    static let empty = ClipboardClip(kind: "empty", text: nil)
    var preview: String {
        switch kind {
        case "text": return text?.isEmpty == false ? text! : "空文字"
        case "sensitive": return "敏感内容已跳过"
        case "unsupported": return "当前内容已跳过"
        case "image": return "Mac 图片"
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
    var recoverySupported: Bool? = nil
    var sessionId: String? = nil
    var availability: ClipboardAvailability? = nil
    var images: Bool? = nil
    var imagesSupported: Bool? = nil
    var savedImage: Bool? = nil
    var imageError: String? = nil
}

struct ClipboardAvailability: Decodable {
    var status: String
    var reason: String
    var action: String
    var ready: Bool
}

struct ClipboardLocalState {
    var count: Int
    var clip: ClipboardClip
}

/// A failed request is never replayed. Only a later copy can resume against a fresh phone version.
struct ClipboardRecovery {
    static let window: TimeInterval = 30
    enum Decision: Equatable { case baseline, sendNewCopy, receivePhoneCopy }
    private struct Checkpoint {
        var phoneVersion: String
        var sessionId: String?
        var macCount: Int
        var time: TimeInterval
    }
    private var checkpoint: Checkpoint?
    private var attemptedCount: Int?
    private(set) var recovering = false

    mutating func sent(count: Int) { attemptedCount = count }
    mutating func interrupted() { recovering = checkpoint != nil }
    mutating func reset() { checkpoint = nil; attemptedCount = nil; recovering = false }
    mutating func succeeded(_ snapshot: ClipboardSnapshot, count: Int, now: TimeInterval) {
        guard snapshot.shared, snapshot.automatic, snapshot.error == nil,
              snapshot.phone?.kind == "text" || snapshot.phone?.kind == "empty" else { reset(); return }
        checkpoint = Checkpoint(phoneVersion: snapshot.phoneVersion, sessionId: snapshot.sessionId, macCount: count, time: now)
        attemptedCount = count
        recovering = false
    }
    func decision(_ snapshot: ClipboardSnapshot, local: ClipboardLocalState, now: TimeInterval) -> Decision {
        guard recovering, let checkpoint, let attemptedCount,
              now >= checkpoint.time, now - checkpoint.time < Self.window,
              snapshot.recoverySupported == true, snapshot.shared, snapshot.automatic, snapshot.error == nil,
              snapshot.sessionId == checkpoint.sessionId,
              snapshot.phone?.kind == "text" || snapshot.phone?.kind == "empty" else { return .baseline }
        if snapshot.phoneVersion == checkpoint.phoneVersion,
           local.count > attemptedCount, local.clip.kind == "text" { return .sendNewCopy }
        if snapshot.phoneVersion != checkpoint.phoneVersion, local.count == checkpoint.macCount,
           local.clip.kind == "text" || local.clip.kind == "empty" { return .receivePhoneCopy }
        return .baseline
    }
}

/// Never apply a reply over a newer local copy or replay clipboard data after reconnecting.
struct ClipboardSyncPolicy {
    private(set) var phoneVersion: String?
    private var sessionId: String?
    mutating func disconnect() { phoneVersion = nil; sessionId = nil }
    mutating func receive(_ snapshot: ClipboardSnapshot, sentCount: Int, currentCount: Int,
                          current: ClipboardClip, baseline: Bool) -> String? {
        defer { phoneVersion = snapshot.phoneVersion; sessionId = snapshot.sessionId }
        guard !baseline, snapshot.shared, snapshot.automatic, snapshot.error == nil,
              sessionId == snapshot.sessionId,
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
        try await call(endpoint: endpoint, token: token, name: name, arguments: arguments, timeout: 8)
    }
    static func call(endpoint: String, token: String, name: String, arguments: [String: Any], timeout: TimeInterval) async throws -> Data {
        guard let url = URL(string: endpoint), url.host == "127.0.0.1", url.path == "/mcp", !token.isEmpty else {
            throw Failure(message: "请先连接 MCP 服务")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
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
