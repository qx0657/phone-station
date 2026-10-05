import Foundation

@main
enum NotificationSessionTest {
    @MainActor final class Fake {
        var permission = NotificationPermission.authorized
        var requests = 0
        var calls: [(String, [String: Any])] = []
        var delivered: [PhoneNotification] = []
        var cursor = "session:0"
        var baseline = false
        var events: [[String: Any]] = []
        var fail = false
        var hold = false
        var holdIcon = false
        var iconFailure = false
        var iconWaiting: CheckedContinuation<Data, Error>?
        var waiting: CheckedContinuation<Data, Error>?
        var endpoint: (String, String)? = ("http://127.0.0.1:18765/mcp", "test")
        func data() -> Data {
            try! JSONSerialization.data(withJSONObject: ["enabled": true, "accessGranted": true,
                "listenerConnected": true, "selectedCount": 1, "macOnline": true,
                "cursor": cursor, "events": events, "baseline": baseline])
        }
        func call(_ name: String, _ args: [String: Any]) async throws -> Data {
            calls.append((name, args))
            if name == "station_notification_icon" {
                if iconFailure { throw ClipboardRPC.Failure(message: "icon unavailable") }
                if holdIcon { return try await withCheckedThrowingContinuation { iconWaiting = $0 } }
                return try JSONSerialization.data(withJSONObject: ["available": true, "packageName": "chat",
                    "app": "Chat", "mimeType": "image/png", "base64": "iVBORw0KGgo="])
            }
            if fail { fail = false; throw ClipboardRPC.Failure(message: "offline") }
            if hold && name == "station_notification_poll" {
                return try await withCheckedThrowingContinuation { waiting = $0 }
            }
            return data()
        }
        func event(_ id: String = "session:1") {
            cursor = id
            events = [["id": id, "key": "phone-key", "packageName": "chat", "app": "Chat", "title": "title", "text": "message"]]
        }
        func session() -> NotificationSession {
            let session = NotificationSession(startTimer: false, receiving: true,
                readPermission: { self.permission }, authorize: { self.requests += 1; return self.permission },
                deliver: { self.delivered.append($0) }, rpc: { _, _, name, args in try await self.call(name, args) },
                saveReceiving: { _ in })
            session.route = { self.endpoint }
            return session
        }
    }
    @MainActor static func tick(_ session: NotificationSession) async {
        session.refresh()
        try? await Task.sleep(nanoseconds: 20_000_000)
    }
    @MainActor static func main() async {
        let guarded = Fake(), guardedSession = guarded.session()
        var granted = false
        guardedSession.remote = { true }
        guardedSession.access = { RemoteAccessState(checked: true, permissions: ["personal": granted]) }
        await tick(guardedSession); await tick(guardedSession)
        guardedSession.configurePhone(true)
        await Task.yield()
        expect(guarded.calls.allSatisfy { $0.0 == "station_notification_status" }, "ungranted notifications only read content-free status")
        expect(guardedSession.remoteBlocker?.scopes == ["personal"], "notification denial provides a resolvable scope")
        granted = true
        await tick(guardedSession)
        expect(guarded.calls.last?.0 == "station_notification_poll" && guarded.delivered.isEmpty, "grant establishes a new baseline")
        guarded.event(); guarded.holdIcon = true
        guardedSession.refresh()
        try? await Task.sleep(nanoseconds: 20_000_000)
        granted = false
        guarded.iconWaiting?.resume(returning: Data("{\"available\":false}".utf8))
        try? await Task.sleep(nanoseconds: 20_000_000)
        expect(guarded.delivered.isEmpty, "revocation during icon lookup suppresses the notification body")
        let fake = Fake()
        let session = fake.session()
        await tick(session) // status
        expect(fake.calls.last?.0 == "station_notification_status", "discover phone settings first")
        fake.event()
        await tick(session) // baseline, even a malformed reply must not replay old notifications
        expect(fake.delivered.isEmpty, "first contact is a baseline")
        fake.event("session:2")
        await tick(session)
        expect(fake.delivered.count == 1, "new event delivered")
        expect(fake.delivered.first?.iconPNG != nil && fake.calls.last?.0 == "station_notification_icon", "selected app icon accompanies the notification")
        await tick(session)
        expect(fake.delivered.count == 1, "duplicate event not delivered twice")
        expect(fake.calls.filter { $0.0 == "station_notification_icon" }.count == 1, "icons are cached instead of fetched on every poll")
        fake.fail = true
        await tick(session)
        fake.event("session:3")
        await tick(session)
        expect(fake.calls.last?.1["cursor"] == nil && fake.delivered.count == 1, "unknown result reconnects without replay")
        fake.baseline = true
        fake.event("new-session:1")
        await tick(session)
        expect(fake.delivered.count == 1, "phone config or restart baseline suppresses stale content")
        fake.baseline = false
        fake.hold = true
        session.refresh()
        try? await Task.sleep(nanoseconds: 20_000_000)
        session.setReceiving(false)
        fake.event("new-session:2")
        fake.waiting?.resume(returning: fake.data()); fake.waiting = nil
        try? await Task.sleep(nanoseconds: 20_000_000)
        expect(fake.delivered.count == 1, "pause suppresses in-flight notifications")
        fake.hold = false
        await tick(session)
        expect(fake.calls.last?.0 == "station_notification_status", "paused Mac never consumes")
        session.setReceiving(true)
        try? await Task.sleep(nanoseconds: 20_000_000)
        fake.hold = true
        session.refresh()
        try? await Task.sleep(nanoseconds: 20_000_000)
        fake.endpoint = nil
        fake.event("new-session:3")
        fake.waiting?.resume(returning: fake.data()); fake.waiting = nil
        try? await Task.sleep(nanoseconds: 20_000_000)
        expect(fake.delivered.count == 1 && !session.connected, "route changes discard in-flight data")
        let unapproved = Fake()
        unapproved.permission = .notDetermined
        let pending = unapproved.session()
        await tick(pending)
        await tick(pending)
        expect(unapproved.requests == 0 && unapproved.calls.allSatisfy { $0.0 == "station_notification_status" }, "permission is only requested by user action")
        pending.setReceiving(false)
        pending.setReceiving(true)
        try? await Task.sleep(nanoseconds: 30_000_000)
        expect(pending.receiving && unapproved.requests == 1, "user can enable receiving before notification authorization")
        let offline = Fake()
        offline.endpoint = nil
        let noRoute = offline.session()
        await tick(noRoute)
        expect(noRoute.permission == .authorized && offline.calls.isEmpty, "Mac permission can be checked before connecting phone")
        let failedIcon = Fake()
        failedIcon.iconFailure = true
        let fallback = failedIcon.session()
        await tick(fallback); await tick(fallback)
        failedIcon.event()
        await tick(fallback)
        expect(failedIcon.delivered.count == 1 && failedIcon.delivered.first?.iconPNG == nil, "icon failure never drops a notification")
        let pausedIcon = Fake()
        let duringIcon = pausedIcon.session()
        await tick(duringIcon); await tick(duringIcon)
        pausedIcon.event(); pausedIcon.holdIcon = true
        duringIcon.refresh()
        try? await Task.sleep(nanoseconds: 20_000_000)
        duringIcon.setReceiving(false)
        pausedIcon.iconWaiting?.resume(returning: Data("{\"available\":false}".utf8))
        try? await Task.sleep(nanoseconds: 20_000_000)
        expect(pausedIcon.delivered.isEmpty, "pausing during icon fetch suppresses the late banner")
        let remote = Fake(), remoteSession = remote.session()
        remoteSession.remote = { true }
        await tick(remoteSession)
        let completed = remote.calls.count
        remoteSession.refresh(background: true)
        try? await Task.sleep(nanoseconds: 20_000_000)
        expect(remote.calls.count == completed, "remote background notification polls wait after the prior response")
        await tick(remoteSession)
        expect(remote.calls.count > completed, "explicit refresh is not delayed by the background cooldown")
        print("Mac 手机通知权限、去重、暂停和断线恢复通过")
    }
    static func expect(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
}
