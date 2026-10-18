import Foundation

@main
enum DeviceHealthSessionTest {
    @MainActor static func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<10000 {
            if condition() { return }
            await Task.yield()
        }
        preconditionFailure("health refresh did not finish")
    }
    @MainActor static func main() async {
        var calls = 0, blocked = true, fail = false, hold = false, hasRoute = true
        var pending: CheckedContinuation<Data, Error>?
        let session = DeviceHealthSession(startTimer: false) { _, _, name, arguments in
            calls += 1
            precondition(name == "device_status" && arguments.isEmpty, "diagnostics must only use read-only device status")
            if hold { hold = false; return try await withCheckedThrowingContinuation { pending = $0 } }
            if fail { throw NSError(domain: "offline", code: 1) }
            let issues: [[String: String]] = blocked ? [["id": "shizuku", "title": "Shizuku 未启动",
                "detail": "共享剪贴板和远程 shell 不可用。", "destination": "permissions"]] : []
            return try JSONSerialization.data(withJSONObject: ["health": ["issues": issues]])
        }
        session.route = { hasRoute ? ("http://127.0.0.1:18765/mcp", "test") : nil }
        session.refresh(); await waitFor { session.issues.count == 1 }
        precondition(session.failure == nil && session.issues[0].title == "Shizuku 未启动")
        let firstCalls = calls
        session.refresh(background: true)
        for _ in 0..<100 { await Task.yield() }
        precondition(calls == firstCalls, "background diagnostics respect a cooldown after completion")
        blocked = false
        session.refresh(); await waitFor { session.issues.isEmpty }
        fail = true
        session.refresh(); await waitFor { session.failure != nil }
        precondition(session.issues.isEmpty, "unknown current state cannot retain resolved warnings")
        fail = false; hold = true
        session.refresh(); await waitFor { pending != nil }
        let before = calls
        session.refresh(); precondition(calls == before, "only one read in flight")
        hasRoute = false; session.refresh()
        precondition(session.issues.isEmpty && session.failure == nil)
        pending!.resume(returning: try! JSONSerialization.data(withJSONObject: ["health": ["issues": [
            ["id": "old", "title": "old", "detail": "old", "destination": "permissions"]]]]))
        pending = nil
        for _ in 0..<100 { await Task.yield() }
        precondition(session.issues.isEmpty, "late response cannot restore warnings after disconnect")
        var statusCalls = 0
        let watching = DeviceHealthSession(startTimer: false) { _, _, name, _ in
            statusCalls += 1
            precondition(name == "device_status")
            return try JSONSerialization.data(withJSONObject: ["health": ["issues": []]])
        }
        watching.route = { ("http://127.0.0.1:18765/mcp", "test") }
        watching.states = { throw NSError(domain: "events", code: 1) }
        await watching.idle()
        await waitFor { statusCalls == 1 }
        let issueBody: [String: Any] = ["generation": 1, "device": ["batteryPercent": 40, "issues": [
            ["id": "shizuku", "title": "Shizuku 未启动", "detail": "需要 Shizuku", "destination": "permissions"]]]]
        watching.states = { try JSONSerialization.data(withJSONObject: issueBody) }
        await watching.idle()
        precondition(statusCalls == 1 && watching.issues.count == 1 && watching.issues[0].title == "Shizuku 未启动",
                     "an issues event suppresses the idle device_status call")
        await watching.idle()
        precondition(statusCalls == 1, "unchanged issues body does not cause another device_status call")
        watching.refresh()
        await waitFor { statusCalls == 2 }
        let bareCalls = statusCalls
        let bare = DeviceHealthSession(startTimer: false) { _, _, name, _ in
            statusCalls += 1
            precondition(name == "device_status")
            return try JSONSerialization.data(withJSONObject: ["health": ["issues": []]])
        }
        bare.route = { ("http://127.0.0.1:18765/mcp", "test") }
        bare.states = { try JSONSerialization.data(withJSONObject: ["generation": 2, "device": ["batteryPercent": 40]]) }
        await bare.idle()
        await waitFor { statusCalls == bareCalls + 1 }
        print("DeviceHealthSessionTest passed")
    }
}
