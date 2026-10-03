import Foundation

private final class ProbeSequence: @unchecked Sendable {
    let started = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var count = 0
    func hasStarted() -> Bool { started.wait(timeout: .now()) == .success }
    func read(_ trusted: String?) -> LinkReading {
        lock.lock(); count += 1; let index = count; lock.unlock()
        if index == 1 {
            started.signal(); release.wait()
            return LinkReading(name: "PGT-AN20", detail: "", transport: "无线连接", link: .connected, serial: "old-phone")
        }
        return LinkReading(name: "手机未连接", detail: "", link: .offline)
    }
}

@main
struct ConnectionStatusTest {
    @MainActor
    static func main() async {
        var frames = ConnectionFrames(format: .adb)
        precondition(frames.append(Data("00".utf8)).isEmpty)
        precondition(frames.append(Data("03a".utf8)).isEmpty)
        precondition(frames.append(Data("bc00000001x".utf8)) == ["abc", "", "x"])
        var lines = ConnectionFrames(format: .lines)
        precondition(lines.append(Data("{\"ready\":".utf8)).isEmpty)
        precondition(lines.append(Data("true}\n{}\n".utf8)) == ["{\"ready\":true}", "{}"])
        let devices = AdbDevice.parse("List of devices attached\nadb-phone (3)._adb-tls-connect._tcp device model:PGT_AN20\nusb-device unauthorized\n")
        precondition(devices == [AdbDevice(serial: "adb-phone (3)._adb-tls-connect._tcp", state: "device"),
                                 AdbDevice(serial: "usb-device", state: "unauthorized")])
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
        let stale = McpStatus("{\"ready\":true,\"mode\":\"remote\",\"localOnline\":false,\"remoteOnline\":true,\"remoteVerified\":false,\"remoteChecking\":true,\"remoteConfigured\":true}")
        precondition(!stale.listening && !stale.remoteConnected && stale.remoteChecking)
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

        let adb = LinkSession(feedback: StationFeedback(), reader: { _ in
            LinkReading(name: "PGT-AN20", detail: "", transport: "无线连接", link: .connected, serial: "test-phone")
        }, heartbeat: {})
        await adb.probe(readingControls: false)
        precondition(adb.serial == "test-phone" && adb.statusLabel == "已连接")
        adb.noteRemoteConnection(true)
        precondition(adb.connectionLine == "无线连接 · 远程连接")
        adb.noteRemoteConnection(false)
        precondition(adb.serial == "test-phone" && adb.isConnected, "远程失败不能禁用仍在线的 adb")
        adb.noteRemoteChecking(true)
        precondition(adb.statusLabel == "已连接", "一条通道待确认不覆盖另一条已确认连接")
        let sequence = ProbeSequence()
        let racing = LinkSession(feedback: StationFeedback(), reader: { sequence.read($0) }, heartbeat: {})
        var enabledSerials: [String] = []
        racing.onSerial = { if let serial = $0 { enabledSerials.append(serial) } }
        let probe = Task { await racing.probe(readingControls: false) }
        while !sequence.hasStarted() {
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        racing.devicesChanged("")
        await Task.yield()
        sequence.release.signal()
        await probe.value
        precondition(racing.serial == nil && enabledSerials.isEmpty, "断线事件后的旧探测不能重新启用操作")
        print("ConnectionStatusTest ok")
    }
}
