import Foundation

@main
enum CommandSessionTest {
    @MainActor final class Phone {
        struct Call { var name: String; var arguments: [String: Any] }
        var calls: [Call] = []
        var model = "PGT-AN20"
        var verified = true
        var shellAvailable = true
        var dropStart = false
        var failStatus = false
        var state = "completed"
        var wrongID = false
        var jobID = ""
        var stdout = "foreground app"
        var stderr = ""
        var exitCode = 0
        var timedOut = false
        var truncated = false
        var holdModel = false
        var held: CheckedContinuation<Data, Error>?
        var starts: [Call] { calls.filter { $0.name == "station_shell_start" } }
        var queries: [Call] { calls.filter { $0.name == "station_operation_status" } }

        func call(_ endpoint: String, _ token: String, _ name: String, _ arguments: [String: Any]) async throws -> Data {
            calls.append(Call(name: name, arguments: arguments))
            switch name {
            case "station_controls_status":
                if holdModel {
                    holdModel = false
                    return try await withCheckedThrowingContinuation { held = $0 }
                }
                return data(["model": model, "verified": verified])
            case "station_shell_status":
                return data(["available": shellAvailable, "reason": shellAvailable ? "" : "Shizuku 未启动"])
            case "station_shell_start":
                jobID = arguments["jobId"] as! String
                precondition(arguments["timeoutMs"] as? Int == 15000)
                precondition(arguments["maxOutputBytes"] as? Int == 32768)
                if dropStart { throw NSError(domain: "lost-response", code: 1) }
                return receipt()
            case "station_operation_status":
                precondition(arguments["jobId"] as? String == jobID, "query must retain the submitted ID")
                if failStatus { throw NSError(domain: "offline", code: 1) }
                return receipt()
            default: preconditionFailure("unexpected tool: \(name)")
            }
        }
        func receipt() -> Data {
            var value: [String: Any] = ["jobId": wrongID ? String(repeating: "0", count: 32) : jobID,
                                        "kind": "shell", "state": state]
            if state == "completed" {
                value["result"] = ["stdout": stdout, "stderr": stderr, "exitCode": exitCode,
                                   "timedOut": timedOut, "outputTruncated": truncated]
            } else if state == "rejected" { value["error"] = "任务队列已满，尚未执行" }
            return data(value)
        }
        func data(_ value: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: value) }
    }

    @MainActor static func waitFor(_ condition: () -> Bool) async {
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        while !condition() {
            precondition(ProcessInfo.processInfo.systemUptime < deadline, "command session did not finish")
            try! await Task.sleep(nanoseconds: 1_000_000)
        }
    }
    @MainActor static func withDefaults(_ operation: (UserDefaults) async -> Void) async {
        let name = "phone-station-command-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        await operation(defaults)
    }
    @MainActor static func ready(_ session: CommandSession) async {
        session.refreshRemote()
        await waitFor { session.disabledReason(for: session.commands[0]) == nil }
    }
    @MainActor static func main() async {
        await withDefaults { await availability($0) }
        await withDefaults { await completion($0) }
        await withDefaults { await recovery($0) }
        await withDefaults { await staleReading($0) }
        print("CommandSessionTest passed")
    }

    @MainActor static func availability(_ defaults: UserDefaults) async {
        let phone = Phone(), feedback = StationFeedback()
        let session = CommandSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: phone.call)
        var connected = true
        session.remoteRoute = { connected ? ("http://127.0.0.1:18765/mcp", "test") : nil }
        precondition(session.commands.count == 2)
        precondition(session.disabledReason(for: session.commands[0]) != nil)
        await ready(session)
        precondition(phone.calls.allSatisfy { ["station_controls_status", "station_shell_status"].contains($0.name) },
                     "opening the page must not execute shell commands")
        precondition(!session.canOpenShell)
        let install = SavedAdbCommand(id: UUID(), name: "install", arguments: "install app.apk")
        let interactive = SavedAdbCommand(id: UUID(), name: "shell", arguments: "shell")
        for command in [install, interactive] {
            precondition(session.disabledReason(for: command) != nil)
            session.run(command)
        }
        precondition(phone.starts.isEmpty)
        feedback.activity = "busy"
        session.run(session.commands[0]); precondition(phone.starts.isEmpty)
        feedback.activity = nil
        phone.shellAvailable = false
        session.refreshRemote()
        await waitFor { session.disabledReason(for: session.commands[0]) == "Shizuku 未启动" }
        session.run(session.commands[0]); precondition(phone.starts.isEmpty)
        let shellReads = phone.calls.filter { $0.name == "station_shell_status" }.count
        phone.model = "other"; phone.verified = false
        session.refreshRemote()
        await waitFor { session.remoteFailure?.contains("other") == true }
        precondition(phone.calls.filter { $0.name == "station_shell_status" }.count == shellReads)
        session.run(session.commands[0]); precondition(phone.starts.isEmpty)
        connected = false; session.refreshRemote()
        precondition(session.disabledReason(for: session.commands[0]) == "请先连接手机。")
        session.serial = { "verified-local-serial" }; session.allow = { true }
        precondition(session.disabledReason(for: install) == nil && session.canOpenShell,
                     "local adb remains available independently of remote Shizuku")
        session.allow = { false }; session.run(install)
        precondition(phone.starts.isEmpty)
    }

    @MainActor static func completion(_ defaults: UserDefaults) async {
        let phone = Phone(), feedback = StationFeedback()
        let session = CommandSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: phone.call)
        session.remoteRoute = { ("http://127.0.0.1:18765/mcp", "test") }
        await ready(session)
        session.run(session.commands[0])
        precondition(session.pendingCommand != nil && defaults.data(forKey: "phoneStationPendingCommand") != nil,
                     "save the job ID before sending a command")
        session.run(session.commands[0])
        await waitFor { feedback.activity == nil }
        precondition(phone.starts.count == 1 && phone.queries.isEmpty)
        precondition(phone.starts[0].arguments["command"] as? String == "dumpsys window | grep mCurrentFocus")
        precondition(session.commandOutput?.text == "foreground app" && session.pendingCommand == nil)
        precondition(defaults.data(forKey: "phoneStationPendingCommand") == nil)

        phone.stdout = ""; phone.stderr = "permission denied"; phone.exitCode = 1
        session.run(session.commands[1]); await waitFor { feedback.activity == nil }
        precondition(session.commandOutput?.text.contains("退出码：1") == true)
        precondition(session.commandOutput?.text.contains("permission denied") == true)
        phone.timedOut = true; phone.truncated = true
        session.run(session.commands[1]); await waitFor { feedback.activity == nil }
        precondition(session.commandOutput?.text.contains("手机已停止") == true)
        precondition(session.commandOutput?.text.contains("输出不完整") == true)
        phone.state = "rejected"
        session.run(session.commands[1]); await waitFor { feedback.activity == nil }
        precondition(session.pendingCommand == nil && session.commandOutput?.text.contains("尚未执行") == true)
    }

    @MainActor static func recovery(_ defaults: UserDefaults) async {
        let phone = Phone(), feedback = StationFeedback()
        let session = CommandSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: phone.call)
        session.remoteRoute = { ("http://127.0.0.1:18765/mcp", "test") }
        await ready(session)
        phone.dropStart = true; phone.failStatus = true
        session.run(session.commands[0]); await waitFor { feedback.activity == nil }
        let pending = session.pendingCommand!
        precondition(phone.starts.count == 1 && phone.queries.count == 1)
        precondition(session.commandOutput?.text.contains(pending.jobID) == true)
        session.run(session.commands[0]); precondition(phone.starts.count == 1)

        let restartedPhone = Phone(), restartedFeedback = StationFeedback()
        restartedPhone.jobID = pending.jobID
        let restarted = CommandSession(feedback: restartedFeedback, startTimer: false, defaults: defaults, rpc: restartedPhone.call)
        var endpoint = "http://127.0.0.1:18765/mcp"
        restarted.remoteRoute = { (endpoint, "new-token") }
        precondition(restarted.pendingCommand?.jobID == pending.jobID && restarted.canRecoverCommand)
        endpoint = "http://127.0.0.1:19999/mcp"
        precondition(!restarted.canRecoverCommand, "do not query a pending job on a different gateway")
        endpoint = pending.endpoint
        restartedPhone.wrongID = true
        restarted.recoverCommand(); await waitFor { restartedFeedback.activity == nil }
        precondition(restarted.pendingCommand != nil && restartedPhone.starts.isEmpty)
        restartedPhone.wrongID = false; restartedPhone.state = "missing"
        restarted.recoverCommand(); await waitFor { restartedFeedback.activity == nil }
        precondition(restarted.pendingCommand != nil && restartedPhone.starts.isEmpty,
                     "missing does not mean that the command was not executed")
        restartedPhone.state = "completed"
        restarted.serial = { "verified-local-serial" }
        restarted.remoteRoute = { nil }
        restarted.recoveryRoute = { (endpoint, "new-token") }
        precondition(restarted.canRecoverCommand, "reconnecting through local MCP must still allow status queries")
        restarted.recoverCommand(); await waitFor { restartedFeedback.activity == nil }
        precondition(restarted.pendingCommand == nil && restartedPhone.starts.isEmpty)
        precondition(restarted.commandOutput?.text == "foreground app")

        // A dropped start reply can also recover immediately using the original ID.
        phone.failStatus = false
        session.recoverCommand(); await waitFor { feedback.activity == nil }
        precondition(session.pendingCommand == nil && phone.starts.count == 1)
        session.run(session.commands[0]); await waitFor { feedback.activity == nil }
        precondition(phone.starts.count == 2 && session.pendingCommand == nil)
    }

    @MainActor static func staleReading(_ defaults: UserDefaults) async {
        let phone = Phone(), feedback = StationFeedback()
        let session = CommandSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: phone.call)
        var token = "old"
        var connected = true
        session.remoteRoute = { connected ? ("http://127.0.0.1:18765/mcp", token) : nil }
        phone.holdModel = true
        session.refreshRemote(); await waitFor { phone.held != nil }
        token = "new"
        phone.held!.resume(returning: phone.data(["model": "PGT-AN20", "verified": true])); phone.held = nil
        try! await Task.sleep(nanoseconds: 10_000_000)
        precondition(phone.calls.count == 1 && session.disabledReason(for: session.commands[0]) != nil,
                     "late capability reads cannot enable a different route")
        await ready(session)
        phone.dropStart = true; phone.state = "missing"
        session.run(session.commands[0]); await waitFor { feedback.activity == nil }
        precondition(session.pendingCommand != nil)
        connected = false; session.refreshRemote()
        precondition(!session.canRecoverCommand)
        session.finishFollowingCommand()
        precondition(session.pendingCommand == nil && session.commandOutput?.text.contains("可能已执行") == true)
        precondition(phone.starts.count == 1)
    }
}
