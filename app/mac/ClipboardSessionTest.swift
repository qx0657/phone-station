import Foundation

@main
enum ClipboardSessionTest {
    @MainActor
    final class Fake {
        var local = ClipboardLocalState(count: 1, clip: ClipboardClip(kind: "text", text: "old-mac"))
        var phone = "old-phone"
        var version = "p1"
        var calls: [(String, [String: Any])] = []
        var holdExchange = false
        var holdState = false
        var exchangeWait: CheckedContinuation<Data, Error>?
        var stateWait: CheckedContinuation<Data, Error>?
        var writes: [String] = []
        var time: TimeInterval = 100
        var blocked = false
        var images = false
        var imagesSupported = true
        var imageReads = 0
        var savedImages = 0
        var lastVersion: String?
        var imageError: String?
        func copy(_ text: String) { local.count += 1; local.clip = ClipboardClip(kind: "text", text: text) }
        func reply(applied: Bool = false, savedImage: Bool = false) -> Data {
            if blocked {
                return try! JSONSerialization.data(withJSONObject: ["shared": true, "automatic": true,
                    "phone": NSNull(), "phoneVersion": version, "macOnline": false,
                    "error": "在手机上启动 Shizuku", "availability": ["status": "Shizuku 未启动",
                        "reason": "在手机上启动 Shizuku", "action": "permissions", "ready": false]])
            }
            return try! JSONSerialization.data(withJSONObject: ["shared": true, "automatic": true,
                "phone": ["kind": "text", "text": phone], "phoneVersion": version,
                "macOnline": true, "appliedMac": applied, "recoverySupported": true, "sessionId": "session-a",
                "images": images, "imagesSupported": imagesSupported, "savedImage": savedImage,
                "imageError": imageError as Any? ?? NSNull()])
        }
        func rpc(_ endpoint: String, _ token: String, _ name: String, _ args: [String: Any]) async throws -> Data {
            calls.append((name, args))
            if name == "station_clipboard_configure" {
                if let on = args["images"] as? Bool { images = on }
                return reply()
            }
            if name == "station_clipboard_state" {
                if holdState { holdState = false; return try await withCheckedThrowingContinuation { stateWait = $0 } }
                return reply()
            }
            if holdExchange { holdExchange = false; return try await withCheckedThrowingContinuation { exchangeWait = $0 } }
            let apply = args["resume"] as? Bool == true
            if apply { phone = args["macText"] as! String; version = "p2" }
            let saved = images && args["macImage"] != nil && args["phoneVersion"] != nil
                && args["macVersion"] as? String != lastVersion && imageError == nil
            lastVersion = args["macVersion"] as? String
            if saved { savedImages += 1 }
            return reply(applied: apply, savedImage: saved)
        }
        func session() -> ClipboardSession {
            let session = ClipboardSession(startTimer: false, readLocal: { self.local },
                writeLocal: { self.writes.append($0); self.copy($0) },
                readImage: { _ in self.imageReads += 1; return Data([1, 2, 3]) }, rpc: rpc, clock: { self.time })
            session.route = { ("http://127.0.0.1:18765/mcp", "fake") }
            return session
        }
    }
    @MainActor
    static func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<10000 {
            if condition() { return }
            await Task.yield()
        }
        preconditionFailure("asynchronous clipboard step did not finish")
    }
    @MainActor
    static func main() async {
        let fake = Fake(), session = fake.session()
        session.refresh()
        await waitFor { session.connected }
        precondition(session.lastSyncAt == nil, "a first-contact baseline is not a successful sync")
        precondition(fake.calls.map(\.0) == ["station_clipboard_state", "station_clipboard_exchange"])
        precondition(fake.calls.last!.1["phoneVersion"] == nil, "first contact only establishes a baseline")
        fake.holdExchange = true
        session.refresh()
        await waitFor { fake.exchangeWait != nil }
        fake.copy("copy-1"); session.refresh()
        fake.copy("copy-2"); session.refresh()
        fake.copy("copy-3"); session.refresh()
        precondition(session.mac.text == "copy-3", "latest copy stays current during a delayed request")
        fake.time = 103; session.refresh()
        precondition(!session.connected && session.summary == "正在核对剪贴板", "a stalled exchange cannot claim sync is ready")
        fake.holdState = true
        fake.time = 112
        fake.exchangeWait!.resume(throwing: NSError(domain: "handover", code: 1)); fake.exchangeWait = nil
        await waitFor { fake.stateWait != nil }
        precondition(!session.connected && session.summary == "正在恢复同步")
        precondition(fake.calls.last!.0 == "station_clipboard_state" && fake.calls.last!.1["refresh"] as? Bool == true,
                     "unknown outcomes are reconciled with a read-only state probe")
        fake.copy("latest-during-probe"); session.refresh()
        fake.stateWait!.resume(returning: fake.reply()); fake.stateWait = nil
        await waitFor { session.connected }
        let resumed = fake.calls.last!.1
        precondition(resumed["resume"] as? Bool == true && resumed["macText"] as? String == "latest-during-probe")
        precondition(resumed["macVersion"] as? String == "5" && resumed["phoneVersion"] as? String == "p1")
        precondition(fake.writes.isEmpty && session.summary == "自动双向同步")
        precondition(session.lastSyncAt != nil && session.lastSyncDirection == "Mac → 手机")
        let lastSync = session.lastSyncAt
        session.refresh(); await waitFor { fake.calls.count >= 6 && session.connected }
        precondition(session.lastSyncAt == lastSync, "an idle heartbeat must not claim a new sync")

        let unchanged = Fake(), other = unchanged.session()
        other.refresh(); await waitFor { other.connected }
        unchanged.holdExchange = true
        other.refresh(); await waitFor { unchanged.exchangeWait != nil }
        unchanged.exchangeWait!.resume(throwing: NSError(domain: "unknown-result", code: 1)); unchanged.exchangeWait = nil
        await waitFor { !other.connected }
        other.refresh(); await waitFor { other.connected }
        precondition(unchanged.calls.last!.1["resume"] == nil && unchanged.calls.last!.1["phoneVersion"] == nil,
                     "the same old event is never automatically replayed")

        let phoneCopy = Fake(), receiver = phoneCopy.session()
        receiver.refresh(); await waitFor { receiver.connected }
        phoneCopy.holdExchange = true
        receiver.refresh(); await waitFor { phoneCopy.exchangeWait != nil }
        phoneCopy.phone = "new-phone-copy"; phoneCopy.version = "p2"
        phoneCopy.exchangeWait!.resume(throwing: NSError(domain: "handover", code: 1)); phoneCopy.exchangeWait = nil
        await waitFor { !receiver.connected }
        receiver.refresh(); await waitFor { receiver.connected }
        precondition(phoneCopy.writes == ["new-phone-copy"], "phone copies survive a short handover to an unchanged Mac")
        precondition(receiver.lastSyncAt != nil && receiver.lastSyncDirection == "手机 → Mac")
        let blocked = Fake(), unavailable = blocked.session()
        blocked.blocked = true
        unavailable.refresh()
        await waitFor { unavailable.blocker != nil }
        precondition(unavailable.summary == "Shizuku 未启动" && !unavailable.connected,
                     "home summary explains the actual blocker, not generic recovery")
        precondition(blocked.calls.map(\.0) == ["station_clipboard_state"] && blocked.writes.isEmpty,
                     "blocked read-only probe must not send clipboard exchange or write")
        blocked.blocked = false
        unavailable.refresh(); await waitFor { unavailable.connected }
        precondition(unavailable.blocker == nil && unavailable.summary == "自动双向同步", "permission recovery clears the warning")
        precondition(blocked.writes.isEmpty && blocked.calls.last!.1["phoneVersion"] == nil,
                     "recovering from missing permission establishes a baseline")
        unavailable.configure(shared: true)
        await waitFor { blocked.calls.contains(where: { $0.0 == "station_clipboard_configure" }) && !unavailable.busy }
        let configuration = blocked.calls.first(where: { $0.0 == "station_clipboard_configure" })!.1
        precondition(configuration["shared"] as? Bool == true && configuration["automatic"] as? Bool == true,
                     "the single share switch always enables automatic syncing")
        await imageTests()
        print("ClipboardSessionTest passed")
    }
    @MainActor
    static func imageTests() async {
        let fake = Fake(), session = fake.session()
        session.refresh(); await waitFor { session.connected }
        fake.local = ClipboardLocalState(count: 2, clip: ClipboardClip(kind: "image", text: nil))
        var calls = fake.calls.count
        session.refresh(); await waitFor { fake.calls.count > calls && session.connected }
        precondition(!session.images && fake.imageReads == 0 && fake.calls.last!.1["macImage"] == nil,
                     "images default off and no pixel data is read")
        precondition(session.summary == "自动双向同步", "an ignored image does not pause clipboard synchronization")
        session.configure(images: true)
        await waitFor { !session.busy && session.images && session.connected }
        calls = fake.calls.count
        session.refresh(); await waitFor { fake.calls.count > calls && session.connected }
        precondition(fake.imageReads == 0 && fake.savedImages == 0, "enabling images must not import an old copy")
        fake.local.count += 1
        calls = fake.calls.count
        session.refresh(); await waitFor { fake.calls.count > calls && session.connected }
        precondition(fake.imageReads == 1 && fake.savedImages == 1 && session.lastSyncDirection == "Mac → 手机相册")
        calls = fake.calls.count
        session.refresh(); await waitFor { fake.calls.count > calls && session.connected }
        precondition(fake.imageReads == 1 && fake.calls.last!.1["macImage"] == nil, "idle heartbeat never uploads pixels again")
        fake.holdExchange = true; fake.local.count += 1
        session.refresh(); await waitFor { fake.exchangeWait != nil }
        let reads = fake.imageReads
        fake.exchangeWait!.resume(throwing: NSError(domain: "image-result-unknown", code: 1)); fake.exchangeWait = nil
        await waitFor { !session.connected }
        session.refresh(); await waitFor { session.connected }
        precondition(fake.imageReads == reads && fake.calls.last!.1["macImage"] == nil,
                     "an unknown image save must not be replayed after recovery")
        fake.imageError = "图片未能保存到相册"; fake.local.count += 1
        calls = fake.calls.count
        session.refresh(); await waitFor { fake.calls.count > calls && session.connected }
        precondition(session.message == fake.imageError && session.summary == "自动双向同步",
                     "album errors leave clipboard sync connected")
        fake.imageError = nil
        session.configure(images: false); await waitFor { !session.busy && !session.images && session.connected }
        let disabledReads = fake.imageReads
        fake.local.count += 1; calls = fake.calls.count
        session.refresh(); await waitFor { fake.calls.count > calls && session.connected }
        precondition(fake.imageReads == disabledReads && fake.calls.last!.1["macImage"] == nil)
        let oldPhone = Fake(), oldSession = oldPhone.session()
        oldPhone.imagesSupported = false
        oldPhone.local.clip = ClipboardClip(kind: "image", text: nil)
        oldSession.refresh(); await waitFor { oldSession.connected }
        precondition(oldPhone.calls.last!.1["macKind"] as? String == "unsupported" && oldPhone.imageReads == 0,
                     "old phone versions receive the existing unsupported kind")
    }
}
