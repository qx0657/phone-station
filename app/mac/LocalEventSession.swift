import Foundation

/// 本地通道挂起，直到手机剪贴板或通知计数变化。远程继续用已有的事件流。
@MainActor
final class LocalEventSession {
    var route: () -> (String, String)? = { nil }
    var remote: () -> Bool = { true }
    var clientID: () -> String = { "" }
    var onClipboard: () -> Void = {}
    var onNotifications: () -> Void = {}
    var onActive: (Bool) -> Void = { _ in }
    var perform: (String, String, [String: Any]) async throws -> Data = { endpoint, token, arguments in
        try await ClipboardRPC.callDetached(endpoint: endpoint, token: token, name: "station_event_wait", arguments: arguments, timeout: 30)
    }
    private var task: Task<Void, Never>?
    private var clipSeen: Int64 = 0
    private var noteSeen: Int64 = 0
    private var active = false
    private var gaveUp = false
    private var identitySerial: String?
    private var identityVersion: String?

    func start() {
        guard task == nil else { return }
        task = Task { await loop() }
    }

    func stop() {
        task?.cancel()
        task = nil
        gaveUp = false
        setActive(false)
    }

    /// 序列号或手机应用版本变了，就重新尝试挂起等待。同一身份只放弃一次。
    func noteIdentity(serial: String?, appVersion: String?) {
        let changed = serial != identitySerial || appVersion != identityVersion
        identitySerial = serial
        identityVersion = appVersion
        guard changed, gaveUp else { return }
        gaveUp = false
        start()
    }

    private func loop() async {
        let mine = task
        defer { if task == mine { task = nil } }
        while !Task.isCancelled {
            guard !remote(), let (endpoint, token) = route() else {
                setActive(false)
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                continue
            }
            do {
                let data = try await perform(endpoint, token, ["clientId": clientID(), "clipboard": Int(clipSeen), "notifications": Int(noteSeen)])
                let reply = try JSONDecoder().decode(Reply.self, from: data)
                if reply.clipboard != clipSeen {
                    clipSeen = reply.clipboard
                    onClipboard()
                }
                if reply.notifications != noteSeen {
                    noteSeen = reply.notifications
                    onNotifications()
                }
                setActive(true)
            } catch is CancellationError {
                return
            } catch {
                setActive(false)
                if Self.isUnknownTool(error) {
                    gaveUp = true
                    return
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    private func setActive(_ value: Bool) {
        guard active != value else { return }
        active = value
        onActive(value)
    }

    private struct Reply: Decodable {
        var clipboard: Int64
        var notifications: Int64
    }

    static func isUnknownTool(_ error: Error) -> Bool {
        (error as? ClipboardRPC.Failure)?.code == "unknown_tool"
    }
}
