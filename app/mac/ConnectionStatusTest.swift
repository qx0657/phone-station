import Foundation

@main
struct ConnectionStatusTest {
    @MainActor
    static func main() {
        let endpoint = "http://127.0.0.1:18765/mcp"
        let remote = McpStatus("\(endpoint)\nchannel=remote local=0ms remote=42ms relay=online\nAuthorization: Bearer test")
        precondition(remote.listening && remote.remoteConnected && remote.channel == "remote")
        precondition(remote.token == "test")
        let both = McpStatus("\(endpoint)\nchannel=local local=8ms remote=42ms relay=online")
        precondition(both.listening && both.remoteConnected && both.channel == "local")
        let local = McpStatus("\(endpoint)\nchannel=local local=8ms remote=0ms relay=offline")
        precondition(local.listening && !local.remoteConnected)
        let started = McpStatus("\(endpoint)\nAuthorization: Bearer test")
        precondition(started.listening && !started.remoteConnected && started.channel.isEmpty)
        let snapshot = McpStatus("{\"ready\":true,\"mode\":\"remote\",\"localOnline\":false,\"remoteOnline\":true,\"endpoint\":\"\(endpoint)\",\"token\":\"test\"}")
        precondition(snapshot.remoteConnected && snapshot.channel == "remote" && snapshot.localOnline == false)
        precondition(snapshot.endpoint == endpoint && snapshot.token == "test")
        let offline = McpStatus("{\"ready\":true,\"mode\":\"offline\",\"localOnline\":false,\"remoteOnline\":false}")
        precondition(!offline.listening && !offline.remoteConnected && offline.gatewayReady)
        precondition(!McpStatus("off").gatewayReady)
        for output in ["off", "", "\(endpoint)\nchannel=offline relay=online"] {
            let unavailable = McpStatus(output)
            precondition(!unavailable.listening && !unavailable.remoteConnected)
        }

        let link = LinkSession(feedback: StationFeedback())
        var iconStates: [Bool] = []
        link.onMenuIcon = { iconStates.append($0) }
        precondition(!link.isConnected && link.isChecking)
        link.noteRemoteConnection(true)
        precondition(link.isConnected && !link.isChecking)
        precondition(link.statusLabel == "已连接" && link.statusTone == .ready)
        precondition(link.headline == "已配对手机" && link.connectionLine == "远程连接")
        precondition(link.serial == nil, "远程连接不能启用 adb 操作")
        link.noteRemoteConnection(true)
        precondition(iconStates == [true])
        link.noteRemoteConnection(false)
        precondition(!link.isConnected && link.isChecking && link.serial == nil)
        precondition(iconStates == [true, false])
        print("ConnectionStatusTest ok")
    }
}
