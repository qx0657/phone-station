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
    private var task: Task<Void, Never>?
    private var clipSeen: Int64 = 0
    private var noteSeen: Int64 = 0
    private var active = false

    func start() {
        guard task == nil else { return }
        task = Task { await loop() }
    }

    func stop() {
        task?.cancel()
        task = nil
        setActive(false)
    }

    private func loop() async {
        while !Task.isCancelled {
            guard !remote(), let (endpoint, token) = route() else {
                setActive(false)
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                continue
            }
            do {
                let data = try await ClipboardRPC.callDetached(endpoint: endpoint, token: token, name: "station_event_wait",
                    arguments: ["clientId": clientID(), "clipboard": Int(clipSeen), "notifications": Int(noteSeen)], timeout: 30)
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
                if Self.missing(error) { return }
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

    private static func missing(_ error: Error) -> Bool {
        error.localizedDescription.contains("没有这个工具")
    }
}
