import Foundation

@main
struct LocalEventSessionTest {
    @MainActor
    static func main() async {
        precondition(!LocalEventSession.isUnknownTool(ClipboardRPC.Failure(message: "没有这个工具")))
        precondition(LocalEventSession.isUnknownTool(ClipboardRPC.Failure(message: "没有这个工具", code: "unknown_tool")))

        final class Calls: @unchecked Sendable {
            let lock = NSLock()
            var count = 0
            func next() throws -> Data {
                lock.lock(); count += 1; lock.unlock()
                throw ClipboardRPC.Failure(message: "没有这个工具", code: "unknown_tool")
            }
        }
        let calls = Calls()
        let session = LocalEventSession()
        session.remote = { false }
        session.route = { ("http://127.0.0.1:9/mcp", "token") }
        session.perform = { _, _, _ in try calls.next() }
        session.start()
        for _ in 0..<50 where calls.count == 0 { try? await Task.sleep(nanoseconds: 20_000_000) }
        try? await Task.sleep(nanoseconds: 80_000_000)
        precondition(calls.count == 1, "unknown_tool stops the wait until the phone identity changes")
        session.noteIdentity(serial: "phone", appVersion: nil)
        for _ in 0..<50 where calls.count < 2 { try? await Task.sleep(nanoseconds: 20_000_000) }
        precondition(calls.count == 2, "a new serial retries the wait")
        session.noteIdentity(serial: "phone", appVersion: nil)
        try? await Task.sleep(nanoseconds: 80_000_000)
        precondition(calls.count == 2, "the same identity does not retry")
        session.noteIdentity(serial: "phone", appVersion: "70")
        for _ in 0..<50 where calls.count < 3 { try? await Task.sleep(nanoseconds: 20_000_000) }
        precondition(calls.count == 3, "a new phone app version retries the wait")
        session.stop()
        print("local event session ok")
    }
}
