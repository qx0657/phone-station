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

        precondition(LocalEventSession.retryNanoseconds(failures: 1) == 2_000_000_000)
        precondition(LocalEventSession.retryNanoseconds(failures: 2) == 4_000_000_000)
        precondition(LocalEventSession.retryNanoseconds(failures: 3) == 8_000_000_000)
        precondition(LocalEventSession.retryNanoseconds(failures: 4) == 16_000_000_000)
        precondition(LocalEventSession.retryNanoseconds(failures: 5) == 30_000_000_000)
        precondition(LocalEventSession.retryNanoseconds(failures: 8) == 30_000_000_000)

        final class RetryLog: @unchecked Sendable {
            let lock = NSLock()
            var calls = 0
            var delays: [UInt64] = []
            func next() throws -> Data {
                lock.lock(); calls += 1; let index = calls; lock.unlock()
                if index == 3 {
                    return Data("{\"clipboard\":1,\"notifications\":2}".utf8)
                }
                throw ClipboardRPC.Failure(message: "暂时失败")
            }
            func append(_ nanoseconds: UInt64) {
                lock.lock(); delays.append(nanoseconds); lock.unlock()
            }
            func record(_ nanoseconds: UInt64) async {
                append(nanoseconds)
                try? await Task.sleep(nanoseconds: 1_000_000)
            }
            func snapshot() -> [UInt64] {
                lock.lock(); defer { lock.unlock() }
                return delays
            }
        }
        let retry = RetryLog()
        let backingOff = LocalEventSession()
        backingOff.remote = { false }
        backingOff.route = { ("http://127.0.0.1:9/mcp", "token") }
        backingOff.perform = { _, _, _ in try retry.next() }
        backingOff.wait = { await retry.record($0) }
        backingOff.start()
        for _ in 0..<80 where retry.snapshot().count < 3 { try? await Task.sleep(nanoseconds: 20_000_000) }
        precondition(retry.snapshot().prefix(3).elementsEqual([2_000_000_000, 4_000_000_000, 2_000_000_000]),
                     "连续失败从 2 秒拉长，成功后回到 2 秒")
        backingOff.stop()

        final class RouteLog: @unchecked Sendable {
            let lock = NSLock()
            var calls = 0
            var waits = 0
            func next() throws -> Data {
                lock.lock(); calls += 1; lock.unlock()
                throw ClipboardRPC.Failure(message: "暂时失败")
            }
            func noteWait() {
                lock.lock(); waits += 1; lock.unlock()
            }
            func wait(_ nanoseconds: UInt64) async {
                noteWait()
                try? await Task.sleep(nanoseconds: 5_000_000)
            }
            func snapshot() -> (Int, Int) {
                lock.lock(); defer { lock.unlock() }
                return (calls, waits)
            }
        }
        final class RouteState: @unchecked Sendable {
            var remote = true
            var endpoint: (String, String)? = ("http://127.0.0.1:9/mcp", "token")
        }
        let routes = RouteLog()
        let state = RouteState()
        let routed = LocalEventSession()
        routed.remote = { state.remote }
        routed.route = { state.endpoint }
        routed.perform = { _, _, _ in try routes.next() }
        routed.wait = { await routes.wait($0) }
        routed.start()
        try? await Task.sleep(nanoseconds: 80_000_000)
        precondition(routes.snapshot() == (0, 0), "走远程时不空转")
        state.remote = false
        state.endpoint = nil
        routed.consider()
        try? await Task.sleep(nanoseconds: 80_000_000)
        precondition(routes.snapshot() == (0, 0), "没有路由时不空转")
        state.endpoint = ("http://127.0.0.1:9/mcp", "token")
        routed.consider()
        for _ in 0..<40 where routes.snapshot().0 == 0 { try? await Task.sleep(nanoseconds: 20_000_000) }
        precondition(routes.snapshot().0 > 0, "本地路由出现后才等待")
        state.remote = true
        routed.consider()
        try? await Task.sleep(nanoseconds: 40_000_000)
        let stopped = routes.snapshot()
        try? await Task.sleep(nanoseconds: 80_000_000)
        precondition(routes.snapshot() == stopped, "离开本地路由后停止")
        routed.stop()
        print("local event session ok")
    }
}
