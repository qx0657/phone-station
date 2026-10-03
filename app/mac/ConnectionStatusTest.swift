import Foundation
import AppKit
import CryptoKit

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
        let busy = McpStatus("{\"ready\":true,\"mode\":\"remote\",\"localOnline\":false,\"remoteOnline\":true,\"remoteVerified\":false,\"remoteChecking\":true,\"endpoint\":\"\(endpoint)\",\"token\":\"test\"}")
        precondition(!busy.listening && !busy.remoteConnected && busy.requestAvailable && busy.endpoint == endpoint,
                     "a busy relay retains its dispatch route without claiming a fresh connection")
        let expired = McpStatus("{\"ready\":true,\"mode\":\"remote\",\"localOnline\":false,\"remoteOnline\":true,\"remoteVerified\":false,\"remoteChecking\":false,\"endpoint\":\"\(endpoint)\",\"token\":\"test\"}")
        precondition(!expired.requestAvailable && expired.endpoint.isEmpty, "stale idle and unavailable routes are not dispatched")
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
        await remoteControlsTest()
        print("ConnectionStatusTest ok")
    }

    @MainActor static func remoteControlsTest() async {
        let suite = "capture-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let feedback = StationFeedback()
        var route: (String, String)? = ("test-endpoint", "test-token")
        var model = "PGT-AN20"
        var torchOn = false
        var writes = 0
        var reads = 0
        let controls = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: { _, _, name, args in
            if name == "station_torch" {
                writes += 1
                torchOn = args["on"] as! Bool // 操作已生效，但应答丢失。
                throw ClipboardRPC.Failure(message: "response lost")
            }
            precondition(name == "station_controls_status")
            reads += 1
            return try JSONSerialization.data(withJSONObject: ["model": model, "verified": true,
                "stayAwake": true, "stayAwakeAvailable": true, "stayAwakeReason": "",
                "screenshotAvailable": true, "screenshotReason": "",
                "torch": ["on": torchOn, "available": true, "reason": ""]])
        })
        controls.route = { route }
        controls.refresh()
        for _ in 0..<100 where controls.reading == nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(controls.canCapture && controls.canStayAwake && controls.canTorch && controls.stayAwake)
        controls.setTorch(true)
        precondition(!controls.canCapture, "忙时不得重复发起操作")
        for _ in 0..<100 where feedback.activity != nil || reads < 2 { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(writes == 1 && controls.torchOn && feedback.notice?.contains("结果可能已生效") == true,
                     "丢失应答后只能读取实际状态，不能重放开关")
        route = nil
        precondition(!controls.canCapture && !controls.canTorch, "断线立即禁用控件")
        controls.refresh()
        route = ("another-endpoint", "another-token")
        model = "unsupported"
        controls.refresh()
        for _ in 0..<100 where controls.reading == nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(!controls.canCapture && !controls.canStayAwake && !controls.canTorch,
                     "服务器字段不能为未验证型号启用操作")

        var captureCalls = 0
        var statusCalls = 0
        let recovery = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: { _, _, name, _ in
            if name == "station_screen_capture_start" { captureCalls += 1; throw ClipboardRPC.Failure(message: "lost") }
            if name == "station_operation_status" { return Data("{\"state\":\"result_unknown\"}".utf8) }
            if name == "station_screen_capture_release" { return Data("{}".utf8) }
            if name == "station_screen_capture_status" {
                statusCalls += 1
                return try JSONSerialization.data(withJSONObject: ["available": false])
            }
            precondition(name == "station_controls_status")
            return try JSONSerialization.data(withJSONObject: ["model": "PGT-AN20", "verified": true,
                "stayAwake": true, "stayAwakeAvailable": true, "stayAwakeReason": "",
                "screenshotAvailable": true, "screenshotReason": "",
                "torch": ["on": false, "available": true, "reason": ""]])
        })
        recovery.route = { ("capture-endpoint", "token") }
        recovery.refresh()
        for _ in 0..<100 where recovery.reading == nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        recovery.screenshot()
        for _ in 0..<100 where feedback.activity != nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(captureCalls == 1 && recovery.pendingCapture != nil && !recovery.canCapture)
        let restored = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults)
        precondition(restored.pendingCapture?.id == recovery.pendingCapture?.id, "重启后保留原编号")
        recovery.recoverCapture()
        for _ in 0..<100 where feedback.activity != nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(captureCalls == 1 && statusCalls == 1 && recovery.pendingCapture != nil, "未知结果只查询，不重拍")
        recovery.discardCapture()
        for _ in 0..<100 where feedback.activity != nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(recovery.pendingCapture == nil)

        let cleanupID = UUID().uuidString.lowercased()
        let saved = RemoteControlsSession.PendingCapture(id: cleanupID, endpoint: "capture-endpoint", localFile: "/test/saved.png")
        defaults.set(try! JSONEncoder().encode(saved), forKey: "phoneStationPendingCapture")
        var releaseCalls = 0
        let cleanup = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: { _, _, name, _ in
            if name == "station_controls_status" { throw ClipboardRPC.Failure(message: "offline") }
            precondition(name == "station_screen_capture_release", "保存后只清理，不重新下载")
            releaseCalls += 1
            if releaseCalls == 1 { throw ClipboardRPC.Failure(message: "lost cleanup response") }
            return Data("{}".utf8)
        })
        cleanup.route = { ("capture-endpoint", "token") }
        cleanup.recoverCapture()
        for _ in 0..<100 where feedback.activity != nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(cleanup.pendingCapture != nil)
        cleanup.recoverCapture()
        for _ in 0..<100 where feedback.activity != nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(cleanup.pendingCapture == nil && releaseCalls == 2)

        let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1, pixelsHigh: 1,
                bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                colorSpaceName: .deviceRGB, bytesPerRow: 4, bitsPerPixel: 32)!
        bitmap.setColor(.red, atX: 0, y: 0)
        let png = bitmap.representation(using: .png, properties: [:])!
        let id = UUID().uuidString.lowercased()
        let info = RemoteCapture.Info(requestId: id, path: "/storage/emulated/0/Download/手机工位/.captures/\(id).png",
            size: png.count, targetVersion: "one", sha256: SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined())
        try! RemoteCapture.validate(info, id: id)
        try! RemoteCapture.verify(png, info: info)
        let page = RemoteCapture.Page(offset: 0, length: png.count,
                                      hex: png.map { String(format: "%02x", $0) }.joined(), targetVersion: "one")
        precondition(try! RemoteCapture.decode(page, info: info, offset: 0) == png)
        var badPage = page; badPage.targetVersion = "changed"
        do { _ = try RemoteCapture.decode(badPage, info: info, offset: 0); preconditionFailure("changed file") } catch {}
        badPage = page; badPage.length = 0
        do { _ = try RemoteCapture.decode(badPage, info: info, offset: 0); preconditionFailure("empty page") } catch {}
        var badInfo = info; badInfo.path = "/storage/emulated/0/Download/another.png"
        do { try RemoteCapture.validate(badInfo, id: id); preconditionFailure("wrong path") } catch {}
        var broken = png; broken[broken.count - 1] ^= 1
        do { try RemoteCapture.verify(broken, info: info); preconditionFailure("corrupt PNG") } catch {}
        print("RemoteControls tests ok")
    }
}
