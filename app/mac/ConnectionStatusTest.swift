import Foundation
import Combine
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
        detailWindowShortcutTest()
        var navigation = StationNavigation()
        navigation.changed(from: .main, to: .more)
        navigation.changed(from: .more, to: .connection)
        precondition(navigation.destination(fallback: .main) == .more, "connection returns to its settings entry")
        navigation.changed(from: .connection, to: .mcp)
        precondition(navigation.destination(fallback: .more) == .connection, "nested setup preserves its entry")
        navigation.changed(from: .mcp, to: .connection)
        precondition(navigation.destination(fallback: .main) == .more, "back unwinds without creating a cycle")
        navigation.changed(from: .connection, to: .main)
        navigation.changed(from: .main, to: .connection)
        precondition(navigation.destination(fallback: .more) == .main, "home entry stays independent")
        navigation.changed(from: .connection, to: .main)
        navigation.changed(from: .main, to: .mcp)
        navigation.changed(from: .mcp, to: .features)
        navigation.changed(from: .features, to: .remotePermissions)
        precondition(navigation.destination(fallback: .more) == .features)
        navigation.changed(from: .remotePermissions, to: .features)
        precondition(navigation.destination(fallback: .more) == .mcp, "capability permission flow returns through its service entry")
        navigation.changed(from: .features, to: .mcp)
        navigation.changed(from: .mcp, to: .connection)
        navigation.changed(from: .connection, to: .remoteRelay)
        precondition(navigation.destination(fallback: .mcp) == .connection, "remote setup returns to its actual connection entry")
        navigation.changed(from: .remoteRelay, to: .connection)
        navigation.changed(from: .connection, to: .mcp)
        precondition(navigation.destination(fallback: .more) == .main, "return does not create connection/service cycles")
        let routed = Station()
        routed.page = .more
        routed.page = .connection
        routed.page = .remoteRelay
        precondition(routed.backDestination == .connection)
        precondition(routed.backPageTitle == StationL10n.text("连接设置"))
        routed.goBack()
        precondition(routed.page == .connection && routed.backDestination == .more)
        routed.goBack()
        precondition(routed.page == .more && routed.backDestination == .main)
        routed.goBack()
        precondition(routed.page == .main)
        routed.page = .commands
        routed.commands.openPage(.editCommand)
        precondition(routed.backDestination == .commands)
        precondition(routed.pageTitle == StationL10n.text("新建命令"))
        routed.goBack()
        precondition(routed.page == .commands)
        routed.openFeatures(.screen)
        precondition(routed.windowTitle == StationL10n.text("屏幕与手机控制") + " · " + StationL10n.text("手机工位"))
        routed.openRemotePermissions()
        precondition(routed.backPageTitle == StationL10n.text("屏幕与手机控制"), "return labels use the actual capability group")
        routed.goBack()
        precondition(routed.page == .features && routed.backDestination == .commands)
        precondition(Set(PhoneFeatureGroup.allCases.flatMap(\.keys)) == Set(PhoneFeatureState.keys), "capability grouping keeps every feature")
        precondition(PhoneFeatureGroup.allCases.flatMap(\.keys).count == PhoneFeatureState.keys.count)
        let switches = PhoneFeatureState.adb((["0"] + Array(repeating: "1", count: PhoneFeatureState.keys.count)).joined(separator: "\n"))
        precondition(switches?.master == false && switches?.selected["alerts"] == true)
        precondition(switches?.allows("alerts") == false, "master blocks without overwriting child intent")
        precondition(PhoneFeatureState.adb("null\nnull") == nil, "legacy phone remains compatible")
        let projection = (["1"] + Array(repeating: "1", count: PhoneFeatureState.keys.count) + ["0", "1"]).joined(separator: "\n")
        precondition(PhoneFeatureState.adb(projection)?.allows("shell") == true)
        precondition(PhoneConnectionPreferences.adb(projection) == PhoneConnectionPreferences(localMcp: false, remote: true))
        precondition(PhoneConnectionPreferences.adb("null\nnull") == nil)
        precondition(PhoneConnectionPreferences.adb(projection.replacingOccurrences(of: "0\n1", with: "null\nnull")) == PhoneConnectionPreferences(localMcp: nil, remote: nil))
        func presentation(local: Bool, wanted: Bool, remote: Bool, configured: Bool?, checking: Bool = false, paused: Bool = false) -> McpServicePresentation {
            McpServicePresentation(paused: paused, gatewayReady: true, listening: local || remote,
                localOnline: local, localExpected: wanted, remoteOnline: remote, remoteConfigured: configured, remoteChecking: checking)
        }
        precondition(presentation(local: false, wanted: false, remote: true, configured: true).summary == "运行中 · 远程", "remote-only service is fully available")
        precondition(!presentation(local: true, wanted: true, remote: false, configured: false).needsAttention, "unused remote route is not a failure")
        precondition(presentation(local: true, wanted: true, remote: true, configured: true).summary == "运行中 · 本地与远程")
        precondition(presentation(local: true, wanted: true, remote: false, configured: true).needsAttention, "local success cannot mask a configured remote failure")
        precondition(presentation(local: false, wanted: true, remote: true, configured: true).needsAttention, "remote success cannot mask a wanted local failure")
        precondition(presentation(local: true, wanted: true, remote: false, configured: true, checking: true).summary.contains("远程确认中"))
        let pausedService = presentation(local: true, wanted: true, remote: true, configured: true, paused: true)
        precondition(pausedService.summary == "已暂停" && !pausedService.available && !pausedService.needsAttention, "pause overrides stale online facts")
        let closedService = McpServicePresentation(paused: false, gatewayReady: true, listening: true,
            localOnline: true, localExpected: false, remoteOnline: true, remoteConfigured: true, remoteChecking: true, closed: true)
        precondition(closedService.summary == "已关闭" && !closedService.available && !closedService.needsAttention, "closed choices override stale route facts")
        let independent = PhoneFeatureState(master: true, selected: ["alerts": false, "notifications": true])
        precondition(independent.allows("notifications") && !independent.allows("alerts"))
        var lines = ConnectionFrames()
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

        let link = LinkSession(feedback: StationFeedback(), rememberedWirelessPeer: { false })
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
        })
        await adb.probe(readingControls: false)
        precondition(adb.serial == "test-phone" && adb.statusLabel == "已连接")
        adb.noteRemoteConnection(true)
        precondition(adb.connectionLine == "无线连接 · 远程连接")
        adb.noteRemoteConnection(false)
        precondition(adb.serial == "test-phone" && adb.isConnected, "远程失败不能禁用仍在线的 adb")
        adb.noteRemoteChecking(true)
        precondition(adb.statusLabel == "已连接", "一条通道待确认不覆盖另一条已确认连接")
        let sequence = ProbeSequence()
        let racing = LinkSession(feedback: StationFeedback(), reader: { sequence.read($0) },
                                 connectCommand: { CommandResult(code: 1, output: "offline", timedOut: false) },
                                 rememberedWirelessPeer: { false })
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
        racing.stopWatch()
        precondition(racing.serial == nil && enabledSerials.isEmpty, "断线事件后的旧探测不能重新启用操作")
        await reconnectWithoutDeviceChangesTest()
        await livenessTest()
        surfaceObservationTest()
        await findPhoneStopPollingTest()
        await findPhoneTest()
        await remoteControlsTest()
        print("ConnectionStatusTest ok")
    }

    @MainActor static func detailWindowShortcutTest() {
        _ = NSApplication.shared
        let window = StationDetailWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 100),
                                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        var closes = 0
        let observer = NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification,
                                                               object: window, queue: nil) { _ in closes += 1 }
        defer { NotificationCenter.default.removeObserver(observer) }
        func key(_ modifiers: NSEvent.ModifierFlags) -> NSEvent {
            NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: modifiers, timestamp: 0,
                windowNumber: window.windowNumber, context: nil, characters: "w", charactersIgnoringModifiers: "w",
                isARepeat: false, keyCode: 13)!
        }
        precondition(!window.performKeyEquivalent(with: key([.command, .option])))
        precondition(closes == 0, "other shortcuts do not close the detail window")
        precondition(window.performKeyEquivalent(with: key(.command)))
        precondition(closes == 1, "Command-W uses the native close lifecycle")
    }

    @MainActor static func reconnectWithoutDeviceChangesTest() async {
        final class Attempts: @unchecked Sendable {
            private let lock = NSLock()
            private var value = 0
            func connect() -> CommandResult {
                lock.lock(); value += 1; lock.unlock()
                return CommandResult(code: 1, output: "failed to connect: No route to host", timedOut: false)
            }
            var count: Int {
                lock.lock(); defer { lock.unlock() }; return value
            }
        }
        let attempts = Attempts()
        let link = LinkSession(feedback: StationFeedback(), reader: { _ in
            LinkReading(name: "offline", detail: "", link: .offline, reconnectable: true)
        }, connectCommand: { attempts.connect() }, rememberedWirelessPeer: { true })
        await link.probe(readingControls: false)
        for _ in 0..<300 where attempts.count < 2 {
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        precondition(attempts.count == 2, "unchanged empty device list still retries after backoff")
        precondition(!link.needsWirelessPairing, "network failure does not demand pairing")
        link.stopWatch()
    }

    @MainActor static func livenessTest() async {
        precondition([0, 1, 2, 3, 9].map { McpSession.routeRetrySeconds(step: $0) } == [5, 10, 20, 30, 30])
        precondition(McpSession.routeRetrySeconds(step: -1) == 5)
        precondition(McpSession.shouldRefreshAfterLocalMcp(previous: false, enabled: true, serial: "phone", localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: nil, enabled: true, serial: "phone", localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: true, enabled: true, serial: "phone", localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: false, enabled: false, serial: "phone", localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: false, enabled: nil, serial: "phone", localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: false, enabled: true, serial: nil, localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: false, enabled: true, serial: "", localOnline: false))
        precondition(!McpSession.shouldRefreshAfterLocalMcp(previous: false, enabled: true, serial: "phone", localOnline: true))
        let query = LinkSession.featureQuery()
        precondition(query.contains("settings get global phonestation_enabled"))
        precondition(query.contains("phonestation_feature_files_read"))
        precondition(query.contains("phonestation_mcp") && query.contains("phonestation_remote"))
        precondition(!query.contains("\n"), "feature switches share one shell")
        precondition(query.filter { $0 == ";" }.count == PhoneFeatureState.keys.count + 2)
        precondition(!FeatureReadPolicy.shouldRead(serialOnline: true, mcpAvailable: true, mcpHasFeatures: true))
        precondition(FeatureReadPolicy.shouldRead(serialOnline: true, mcpAvailable: true, mcpHasFeatures: false))
        precondition(FeatureReadPolicy.shouldRead(serialOnline: true, mcpAvailable: false, mcpHasFeatures: false))
        precondition(!FeatureReadPolicy.shouldRead(serialOnline: false, mcpAvailable: false, mcpHasFeatures: false))

        final class Log: @unchecked Sendable {
            let lock = NSLock()
            var shells: [String] = []
            var connects = 0
            var alive = false
            func shell(_ serial: String) -> Bool {
                lock.lock(); shells.append(serial); let alive = self.alive; lock.unlock(); return alive
            }
            func connect() -> CommandResult {
                lock.lock(); connects += 1; lock.unlock()
                return CommandResult(code: 1, output: "skipped", timedOut: false)
            }
        }
        let log = Log()
        let connected = LinkReading(name: "PGT-AN20", detail: "", transport: "无线连接", link: .connected, serial: "dead-phone")
        let link = LinkSession(feedback: StationFeedback(), reader: { _ in connected },
                               shellAlive: { log.shell($0) }, connectCommand: { log.connect() })
        await link.probe(readingControls: false)
        precondition(link.serial == "dead-phone")
        link.noteGatewayLocal(false)
        link.noteGatewayLocal(false)
        try? await Task.sleep(nanoseconds: 50_000_000)
        precondition(log.shells.isEmpty && log.connects == 0, "offline without a prior online sample does not probe")
        link.noteGatewayLocal(true)
        link.noteGatewayLocal(false)
        for _ in 0..<40 where log.shells.isEmpty { try? await Task.sleep(nanoseconds: 25_000_000) }
        precondition(log.shells == ["dead-phone"] && link.serial == nil, "a failed shell true drops the stale adb session")
        for _ in 0..<40 where log.connects == 0 { try? await Task.sleep(nanoseconds: 25_000_000) }
        precondition(log.connects == 1, "a dead session uses the existing reconnect")
        await link.probe(readingControls: false)
        precondition(link.serial == nil, "track-devices style rediscovery does not mark a dead session online")
        log.alive = true
        link.noteGatewayLocal(true)
        link.noteGatewayLocal(false)
        for _ in 0..<40 where link.serial != "dead-phone" { try? await Task.sleep(nanoseconds: 25_000_000) }
        precondition(link.serial == "dead-phone" && log.connects == 1, "a later successful probe restores the session")

        let idle = Log()
        idle.alive = true
        let quiet = LinkSession(feedback: StationFeedback(), reader: { _ in connected },
                                 shellAlive: { idle.shell($0) }, connectCommand: { idle.connect() })
        quiet.idleLivenessNanoseconds = 40_000_000
        quiet.noteLocalMcp(false)
        await quiet.probe(readingControls: false)
        try? await Task.sleep(nanoseconds: 120_000_000)
        precondition(idle.shells.isEmpty, "a closed panel does not probe")
        quiet.notePanel(true)
        for _ in 0..<40 where idle.shells.isEmpty { try? await Task.sleep(nanoseconds: 20_000_000) }
        precondition(!idle.shells.isEmpty && idle.connects == 0, "an open panel probes while local MCP is off")
        let seen = idle.shells.count
        quiet.notePanel(false)
        try? await Task.sleep(nanoseconds: 120_000_000)
        precondition(idle.shells.count == seen, "closing the panel stops the probe")

        let gatewayLog = Log()
        let byGateway = LinkSession(feedback: StationFeedback(), reader: { _ in connected },
                                     shellAlive: { gatewayLog.shell($0) }, connectCommand: { gatewayLog.connect() })
        await byGateway.probe(readingControls: false)
        byGateway.notePanel(false)
        byGateway.noteLocalMcp(true)
        byGateway.noteGatewayLocal(true)
        byGateway.noteGatewayLocal(false)
        for _ in 0..<40 where byGateway.serial != nil { try? await Task.sleep(nanoseconds: 25_000_000) }
        precondition(byGateway.serial == nil && gatewayLog.shells == ["dead-phone"])
        let shellsAtFailure = gatewayLog.shells.count
        byGateway.noteGatewayLocal(true)
        for _ in 0..<40 where byGateway.serial != "dead-phone" { try? await Task.sleep(nanoseconds: 25_000_000) }
        precondition(byGateway.serial == "dead-phone" && gatewayLog.shells.count == shellsAtFailure && gatewayLog.connects == 1,
                     "失败后由网关在线复原")

        let recheckLog = Log()
        let byRecheck = LinkSession(feedback: StationFeedback(), reader: { _ in connected },
                                     shellAlive: { recheckLog.shell($0) }, connectCommand: { recheckLog.connect() })
        byRecheck.idleLivenessNanoseconds = 40_000_000
        await byRecheck.probe(readingControls: false)
        byRecheck.notePanel(false)
        byRecheck.noteLocalMcp(true)
        byRecheck.noteGatewayLocal(true)
        byRecheck.noteGatewayLocal(false)
        for _ in 0..<40 where byRecheck.serial != nil { try? await Task.sleep(nanoseconds: 25_000_000) }
        precondition(byRecheck.serial == nil)
        recheckLog.alive = true
        for _ in 0..<50 where byRecheck.serial != "dead-phone" { try? await Task.sleep(nanoseconds: 20_000_000) }
        precondition(byRecheck.serial == "dead-phone" && recheckLog.shells.count > 1 && recheckLog.connects == 1,
                     "失败后由复查复原")
        let rechecked = recheckLog.shells.count
        try? await Task.sleep(nanoseconds: 120_000_000)
        precondition(recheckLog.shells.count == rechecked, "复查复原后停止")

        let projection = (["1"] + Array(repeating: "0", count: PhoneFeatureState.keys.count) + ["0", "1"]).joined(separator: "\n")
        let features = LinkSession(feedback: StationFeedback(), reader: { _ in connected },
                                    featureShell: { _ in projection })
        await features.probe(readingControls: false)
        await features.readProjectedFeatures()
        precondition(features.phoneFeatures?.master == true && features.phoneFeatures?.allows("shell") == false)
        precondition(features.connectionPreferences == PhoneConnectionPreferences(localMcp: false, remote: true))
    }

    @MainActor static func surfaceObservationTest() {
        let station = Station()
        var signals = 0
        let observation = station.objectWillChange.sink { signals += 1 }
        func expectForwarding(_ source: ObservableObjectPublisher, _ expected: Int, _ why: String) {
            signals = 0
            source.send()
            precondition(signals == expected, why)
        }
        expectForwarding(station.remoteControls.objectWillChange, 0, "closed surfaces do not redraw")
        station.panelVisible = true
        expectForwarding(station.remoteControls.objectWillChange, 1, "opening the panel subscribes immediately using new visibility")
        expectForwarding(station.torch.objectWillChange, 1, "other main controls redraw too")
        station.panelVisible = false
        expectForwarding(station.remoteControls.objectWillChange, 0, "closing the panel releases main subscriptions")
        station.settingsOpen = true
        expectForwarding(station.remoteControls.objectWillChange, 1, "opening settings on Home subscribes")
        station.page = .about
        expectForwarding(station.remoteControls.objectWillChange, 0, "page changes remove the previous page subscriptions")
        expectForwarding(station.login.objectWillChange, 1, "page changes subscribe to the destination page")
        station.page = .main
        expectForwarding(station.remoteControls.objectWillChange, 1, "returning to Home resumes live button labels")
        station.panelVisible = true
        station.panelVisible = false
        expectForwarding(station.remoteControls.objectWillChange, 1, "closing the panel keeps the settings window live")
        station.settingsOpen = false
        expectForwarding(station.remoteControls.objectWillChange, 0, "closing the last surface releases subscriptions")
        observation.cancel()
    }

    @MainActor static func findPhoneStopPollingTest() async {
        let feedback = StationFeedback()
        var phoneRinging = false
        var reads = 0
        var writes = 0
        let controls = RemoteControlsSession(feedback: feedback, startTimer: false, rpc: { _, _, name, args in
            if name == "device_control" {
                precondition(args["action"] as? String == "find_phone")
                writes += 1
                phoneRinging = args["on"] as! Bool
                return try JSONSerialization.data(withJSONObject: ["ringing": phoneRinging])
            }
            reads += 1
            return try JSONSerialization.data(withJSONObject: ["model": "PGT-AN20", "verified": true,
                "stayAwake": false, "stayAwakeAvailable": true, "stayAwakeReason": "",
                "screenshotAvailable": true, "screenshotReason": "",
                "torch": ["on": false, "available": true, "reason": ""],
                "findPhone": ["available": true, "ringing": phoneRinging, "until": 0, "reason": ""]])
        })
        controls.route = { ("fake-status-endpoint", "fake-token") }
        controls.monitoring = true
        controls.refresh()
        for _ in 0..<100 where !controls.canFindPhone { try? await Task.sleep(nanoseconds: 1_000_000) }
        let idleReads = reads
        controls.pollIfNeeded(now: Date().addingTimeInterval(1.1))
        await Task.yield()
        precondition(reads == idleReads, "idle controls retain the longer polling interval")
        controls.setFindPhone(true)
        for _ in 0..<100 where feedback.activity != nil || reads == idleReads { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(controls.findingPhone && writes == 1)
        let ringingReads = reads
        phoneRinging = false // Notification Stop or the phone's automatic timeout.
        feedback.activity = "another operation"
        controls.pollIfNeeded(now: Date().addingTimeInterval(1.1))
        for _ in 0..<100 where controls.findingPhone { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(reads == ringingReads + 1 && !controls.findingPhone,
                     "phone-side stop reaches the Mac after one second, even while another action is busy")
        precondition(writes == 1, "observing phone-side stop must not send another switch command")
        feedback.activity = nil
        controls.monitoring = false
        controls.accessMonitoring = { false }
        let stoppedReads = reads
        controls.pollIfNeeded(now: Date().addingTimeInterval(60))
        await Task.yield()
        precondition(reads == stoppedReads, "inactive monitoring does not poll")
    }

    @MainActor static func findPhoneTest() async {
        let feedback = StationFeedback()
        var ringing = false
        var writes = 0
        var omitCapability = false
        var route: (String, String)? = ("test-endpoint", "test-token")
        let controls = RemoteControlsSession(feedback: feedback, startTimer: false, rpc: { _, _, name, args in
            if name == "device_control" {
                precondition(args["action"] as? String == "find_phone")
                writes += 1
                ringing = args["on"] as! Bool
                if ringing { throw ClipboardRPC.Failure(message: "response lost") }
                return try JSONSerialization.data(withJSONObject: ["ringing": false])
            }
            var status: [String: Any] = ["model": "PGT-AN20", "verified": true,
                "stayAwake": false, "stayAwakeAvailable": true, "stayAwakeReason": "",
                "screenshotAvailable": true, "screenshotReason": "",
                "torch": ["on": false, "available": true, "reason": ""]]
            if !omitCapability { status["findPhone"] = ["available": true, "ringing": ringing, "until": 0, "reason": ""] }
            return try JSONSerialization.data(withJSONObject: status)
        })
        controls.route = { route }
        controls.refresh()
        for _ in 0..<100 where !controls.canFindPhone { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(controls.canFindPhone && !controls.findingPhone)
        controls.setFindPhone(true)
        controls.setFindPhone(true)
        for _ in 0..<100 where feedback.activity != nil || !controls.findingPhone { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(writes == 1 && controls.findingPhone, "lost response is followed by status, never replayed")
        controls.setFindPhone(false)
        for _ in 0..<100 where feedback.activity != nil || controls.findingPhone { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(writes == 2 && !controls.findingPhone)
        omitCapability = true
        controls.refresh()
        for _ in 0..<100 where controls.canFindPhone { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(!controls.canFindPhone && controls.findPhoneReason?.contains("更新") == true, "old phone is disabled with update guidance")
        route = nil
        precondition(!controls.canFindPhone)
    }

    @MainActor static func remoteControlsTest() async {
        let suite = "capture-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let feedback = StationFeedback()
        let setup = SetupSession(feedback: feedback, defaults: defaults)
        setup.installBlocker = { RemoteAccessState(checked: true, permissions: [:]).blocker(for: [.install]) }
        setup.install()
        precondition(setup.installJobID == nil && feedback.activity == nil && feedback.notice?.contains("尚未开始安装") == true,
                     "denied installation does not allocate or overwrite a task ID")
        feedback.notice = nil
        var route: (String, String)? = ("test-endpoint", "test-token")
        var model = "PGT-AN20"
        var torchOn = false
        var writes = 0
        var reads = 0
        var permissions: [String: Bool]? = nil
        let controls = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: { _, _, name, args in
            if name == "device_control" && args["action"] as? String == "torch" {
                writes += 1
                torchOn = args["on"] as! Bool // 操作已生效，但应答丢失。
                throw ClipboardRPC.Failure(message: "response lost")
            }
            precondition(name == "device_status")
            reads += 1
            return try JSONSerialization.data(withJSONObject: ["model": model, "verified": model == "PGT-AN20",
                "stayAwake": true, "stayAwakeAvailable": true, "stayAwakeReason": "",
                "screenshotAvailable": true, "screenshotReason": "", "remotePermissions": permissions as Any? ?? NSNull(),
                "torch": ["on": torchOn, "available": true, "reason": ""]])
        })
        controls.route = { route }
        controls.refresh()
        for _ in 0..<100 where controls.reading == nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(controls.canCapture && controls.canStayAwake && controls.canTorch && controls.stayAwake)
        permissions = ["controls": true, "files.read": false]
        controls.refresh()
        for _ in 0..<100 where controls.reading?.remotePermissions == nil { try? await Task.sleep(nanoseconds: 1_000_000) }
        precondition(!controls.canCapture && controls.canStayAwake, "capture is blocked before creating a screenshot when download permission is missing")
        permissions = ["controls": true, "files.read": true]
        controls.refresh()
        for _ in 0..<100 where !controls.canCapture { try? await Task.sleep(nanoseconds: 1_000_000) }
        let readsBeforeTorch = reads
        controls.setTorch(true)
        precondition(!controls.canCapture, "忙时不得重复发起操作")
        for _ in 0..<100 where feedback.activity != nil || reads <= readsBeforeTorch { try? await Task.sleep(nanoseconds: 1_000_000) }
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
        let recovery = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: { _, _, name, args in
            if name == "screenshot" && args["action"] as? String == "start" { captureCalls += 1; throw ClipboardRPC.Failure(message: "lost") }
            if name == "task_status" { return Data("{\"state\":\"result_unknown\"}".utf8) }
            if name == "screenshot" && args["action"] as? String == "release" { return Data("{}".utf8) }
            if name == "screenshot" && args["action"] as? String == "status" {
                statusCalls += 1
                return try JSONSerialization.data(withJSONObject: ["available": false])
            }
            precondition(name == "device_status")
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
        let cleanup = RemoteControlsSession(feedback: feedback, startTimer: false, defaults: defaults, rpc: { _, _, name, args in
            if name == "device_status" {
                return try JSONSerialization.data(withJSONObject: ["model": "PGT-AN20", "verified": true,
                    "stayAwake": true, "stayAwakeAvailable": true, "stayAwakeReason": "",
                    "screenshotAvailable": true, "screenshotReason": "", "remotePermissions": ["controls": true],
                    "torch": ["on": false, "available": true, "reason": ""]])
            }
            precondition(name == "screenshot" && args["action"] as? String == "release", "保存后只清理，不重新下载")
            releaseCalls += 1
            if releaseCalls == 1 { throw ClipboardRPC.Failure(message: "lost cleanup response") }
            return Data("{}".utf8)
        })
        cleanup.route = { ("capture-endpoint", "token") }
        cleanup.refresh()
        for _ in 0..<100 where cleanup.reading == nil { try? await Task.sleep(nanoseconds: 1_000_000) }
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
