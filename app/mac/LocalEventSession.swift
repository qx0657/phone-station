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
    /// 测试可换成不真正等待的实现。生产从 2 秒加倍，最多 30 秒。
    var wait: (UInt64) async -> Void = { nanoseconds in
        try? await Task.sleep(nanoseconds: nanoseconds)
    }

    func start() {
        consider()
    }

    func stop() {
        task?.cancel()
        task = nil
        gaveUp = false
        setActive(false)
    }

    /// 本地路由出现时启动。走远程或没有路由时停掉，不再每秒空转。
    func consider() {
        guard !remote(), route() != nil else {
            pause()
            return
        }
        guard !gaveUp, task == nil else { return }
        task = Task { await loop() }
    }

    /// 序列号或手机应用版本变了，就重新尝试挂起等待。同一身份只放弃一次。
    func noteIdentity(serial: String?, appVersion: String?) {
        let changed = serial != identitySerial || appVersion != identityVersion
        identitySerial = serial
        identityVersion = appVersion
        guard changed, gaveUp else { return }
        gaveUp = false
        consider()
    }

    private func pause() {
        task?.cancel()
        task = nil
        setActive(false)
    }

    private func loop() async {
        let mine = task
        var failures = 0
        defer { if task == mine { task = nil } }
        while !Task.isCancelled {
            guard !remote(), let (endpoint, token) = route() else {
                setActive(false)
                return
            }
            do {
                let data = try await perform(endpoint, token, ["clientId": clientID(), "clipboard": Int(clipSeen), "notifications": Int(noteSeen)])
                if Task.isCancelled { return }
                let reply = try JSONDecoder().decode(Reply.self, from: data)
                if reply.clipboard != clipSeen {
                    clipSeen = reply.clipboard
                    onClipboard()
                }
                if reply.notifications != noteSeen {
                    noteSeen = reply.notifications
                    onNotifications()
                }
                failures = 0
                setActive(true)
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                setActive(false)
                if Self.isUnknownTool(error) {
                    gaveUp = true
                    return
                }
                failures += 1
                await wait(Self.retryNanoseconds(failures: failures))
                if Task.isCancelled { return }
            }
        }
    }

    /// 连续失败：2 秒、4 秒、8 秒、16 秒，之后 30 秒。成功后从 2 秒再计。
    static func retryNanoseconds(failures: Int) -> UInt64 {
        if failures >= 5 { return 30_000_000_000 }
        let count = max(failures, 1)
        return 2_000_000_000 << (count - 1)
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
