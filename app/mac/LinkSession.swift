import Foundation
import OSLog

enum Link: Sendable {
    case checking
    case connected
    case unverified
    case offline
}

struct LinkReading: Sendable {
    var name: String
    var detail: String
    var transport: String = ""
    var link: Link
    var serial: String? = nil
    /// 一台设备都没有，而且不是在等手机上点允许。这种才自动跑 connect.sh。
    var reconnectable = false
}

@MainActor
final class LinkSession: ObservableObject {
    @Published private(set) var deviceName = "正在检查设备…"
    @Published private(set) var deviceDetail = ""
    @Published private(set) var transport = ""
    @Published private(set) var serial: String?
    @Published private(set) var battery: BatteryReport.Reading?
    @Published private(set) var stayAwake = false
    @Published private(set) var isReconnecting = false
    @Published private(set) var remoteConnected = false
    @Published private(set) var remoteChecking = false
    @Published private var link: Link = .checking
    @Published private var willReconnect = false

    var onMenuIcon: (@MainActor (Bool) -> Void)?
    var allow: () -> Bool = { false }
    var onRefreshTorch: () -> Void = {}
    var onSerial: (String?) -> Void = { _ in }

    private let feedback: StationFeedback
    private let reader: @Sendable (String?) -> LinkReading
    private let heartbeat: @Sendable () -> Void
    /// 已经确认是 PGT-AN20 的序列号。菜单栏复查时序列号没变就不再读型号。
    private var trustedSerial: String?
    private var linkWatch: Task<Void, Never>?
    private static let linkWatchInterval: UInt64 = 1_000_000_000
    private var deviceStream: ConnectionStream?
    private var probeInFlight = false
    private var probeAgain = false
    private var controlsRequested = false
    private var heartbeatInFlight = false
    private var lastHeartbeat = Date.distantPast
    private var tidying = false
    private var pairedDeviceName = ""
    private var decision = ReconnectDecision()
    private var connectIsManual = false
    /// 断开进行中时又点了连接，晚到的断开结果不要把暂停写回去。
    private var connectGeneration = 0
    private let logger = Logger(subsystem: "com.qx0657.phonestation", category: "link")
    private var statusRevision = 0
    private var controlEpoch = 0
    private var connectionEpoch = 0

    init(feedback: StationFeedback,
         reader: @escaping @Sendable (String?) -> LinkReading = { LinkSession.readLink(trustedSerial: $0) },
         heartbeat: @escaping @Sendable () -> Void = { _ = StationRunner.scriptResult("host-state.sh", ["mark"], timeout: 3) }) {
        self.feedback = feedback
        self.reader = reader
        self.heartbeat = heartbeat
    }

    var isConnected: Bool { link == .connected || remoteConnected }
    var isChecking: Bool { link == .checking && !remoteConnected }
    var isUnverified: Bool { link == .unverified }
    var statusLabel: String {
        if isConnected { return "已连接" }
        if isReconnecting { return "正在重新连接" }
        if remoteChecking { return "确认连接中" }
        switch link {
        case .checking: return "检查中"
        case .connected: return "已连接"
        case .unverified: return "未验证"
        case .offline: return "未连接"
        }
    }
    var headline: String {
        if remoteConnected && link != .connected {
            return pairedDeviceName.isEmpty ? "已配对手机" : pairedDeviceName
        }
        switch link {
        case .checking:
            return "正在检查设备…"
        case .offline:
            return deviceName
        case .connected, .unverified:
            return deviceName == "PGT-AN20" ? "荣耀 PGT-AN20" : deviceName
        }
    }
    var connectionLine: String {
        if remoteConnected && link != .connected { return "远程连接" }
        switch link {
        case .checking:
            return ""
        case .connected, .unverified:
            return transport + (remoteConnected ? " · 远程连接" : "")
        case .offline:
            if decision.holdOffline {
                return "已断开无线。再点「连接手机」，之后才会自动重连。"
            }
            if willReconnect {
                return "掉线后会自动连接。"
            }
            return deviceDetail
        }
    }
    var statusTone: StationTone {
        if isConnected { return .ready }
        if isReconnecting { return .caution }
        if remoteChecking { return .caution }
        switch link {
        case .connected: return .ready
        case .unverified: return .caution
        case .checking, .offline: return .neutral
        }
    }

    var adbStatusLabel: String {
        if serial != nil { return "已连接" }
        if isReconnecting || connectIsManual { return "重新连接中" }
        if link == .checking { return "检查中" }
        if link == .unverified { return "未验证" }
        return decision.holdOffline ? "已断开" : "未连接"
    }

    /// 只合并显示状态；adb 操作仍须持有已验证的序列号。
    func noteRemoteConnection(_ connected: Bool) {
        guard remoteConnected != connected else { return }
        remoteConnected = connected
        onMenuIcon?(isConnected)
    }

    func noteRemoteChecking(_ checking: Bool) {
        guard remoteChecking != checking else { return }
        remoteChecking = checking
        onMenuIcon?(isConnected)
    }

    /// adb 列表事件立即触发复查，另每秒验证实际应答，避免僵死 TCP 会话仍显示在线。
    func startWatch() {
        guard linkWatch == nil else { return }
        if let adb = StationRunner.executable("adb") {
            let stream = ConnectionStream(executable: adb, arguments: ["track-devices", "-l"], format: .adb)
            stream.onFrame = { [weak self] listing in self?.devicesChanged(listing) }
            stream.onDisconnect = { [weak self] in self?.devicesChanged("") }
            deviceStream = stream
            stream.start()
        }
        linkWatch = Task { @MainActor in
            while !Task.isCancelled {
                await self.probe(readingControls: false)
                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: Self.linkWatchInterval)
            }
        }
    }

    func probe(readingControls: Bool) async {
        controlsRequested = controlsRequested || readingControls
        if probeInFlight { probeAgain = true; return }
        probeInFlight = true
        repeat {
            probeAgain = false
            let controls = controlsRequested
            controlsRequested = false
            statusRevision += 1
            let revision = statusRevision
            let trusted = trustedSerial
            let reader = self.reader
            let reading = await Task.detached(priority: .utility) { reader(trusted) }.value
            accept(revision, name: reading.name, detail: reading.detail, transport: reading.transport,
                   link: reading.link, serial: reading.serial, readingControls: controls,
                   reconnectable: reading.reconnectable)
        } while probeAgain
        probeInFlight = false
    }

    func stopWatch() {
        linkWatch?.cancel(); linkWatch = nil
        deviceStream?.stop(); deviceStream = nil
    }

    func devicesChanged(_ listing: String) {
        let devices = AdbDevice.parse(listing)
        statusRevision += 1
        // Drop the capability immediately. A pending shell result belongs to the old list.
        if let serial, !devices.contains(where: { $0.serial == serial && $0.state == "device" }) {
            accept(statusRevision, name: "手机未连接", detail: "正在检查连接…", link: .offline,
                   readingControls: false, reconnectable: false)
        }
        if devices.filter({ $0.state == "device" }).count > 1, !tidying {
            tidying = true
            Task.detached(priority: .utility) {
                _ = StationRunner.scriptResult("connect.sh", timeout: 15)
                await MainActor.run { self.tidying = false }
            }
        }
        Task { await self.probe(readingControls: false) }
    }

    func connect() {
        guard feedback.activity == nil else { return }
        connectGeneration += 1
        feedback.notice = nil
        connectIsManual = true
        setReconnecting(false)
        if decision.beginManual() {
            feedback.activity = "正在连接手机…"
            launchConnect()
        } else {
            feedback.activity = "正在连接手机…"
        }
    }

    func disconnect() {
        guard feedback.activity == nil else { return }
        decision.hold()
        connectGeneration += 1
        let generation = connectGeneration
        feedback.activity = "正在断开无线连接…"
        feedback.notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("disconnect.sh", timeout: 20)
            await MainActor.run {
                guard generation == self.connectGeneration else {
                    if self.feedback.activity == "正在断开无线连接…" { self.feedback.activity = nil }
                    return
                }
                self.feedback.activity = nil
                if result.succeeded {
                    self.feedback.notice = "无线连接已断开。"
                } else {
                    self.decision.release()
                    self.feedback.notice = StationText.reason(result.output, fallback: "断开无线连接失败。")
                }
                Task { await self.probe(readingControls: true) }
            }
        }
    }

    func setStayAwake(_ on: Bool) {
        guard allow(), on != stayAwake else { return }
        let previous = stayAwake
        stayAwake = on
        feedback.activity = on ? "正在开启保持亮屏…" : "正在恢复息屏…"
        feedback.notice = nil
        let epoch = bumpControls()
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("stay-awake.sh", [on ? "on" : "off"], timeout: 20)
            await MainActor.run {
                guard epoch == self.controlEpoch else { return }
                self.controlEpoch += 1
                self.feedback.activity = nil
                if result.succeeded {
                    self.stayAwake = on
                    self.feedback.notice = on ? "已保持亮屏。" : "已恢复息屏。"
                } else {
                    self.stayAwake = previous
                    self.feedback.notice = result.timedOut ? "保持亮屏没有响应。" : StationText.reason(result.output, fallback: "无法更改息屏设置。")
                }
            }
        }
    }

    private nonisolated static func readLink(trustedSerial: String?) -> LinkReading {
        guard let adb = StationRunner.executable("adb") else {
            return LinkReading(name: "未找到 adb", detail: "请安装 Android platform-tools。", link: .offline)
        }
        let devices = StationRunner.capture(adb, ["devices", "-l"], timeout: 2)
        if !devices.succeeded {
            let detail = devices.timedOut ? "adb 检查超时，请重试。" : StationText.reason(devices.output, fallback: "无法读取 adb 设备列表。")
            return LinkReading(name: "设备检查失败", detail: detail, link: .offline)
        }
        let listed = AdbDevice.parse(devices.output)
        var online = listed.filter { $0.state == "device" }.map(\.serial)
        if online.count > 1 {
            let identities = online.map { serial -> String in
                let result = StationRunner.capture(adb, ["-s", serial, "shell", "getprop", "ro.serialno"], timeout: 2)
                let identity = result.output.trimmingCharacters(in: .whitespacesAndNewlines)
                return result.succeeded && !identity.isEmpty && identity != "unknown" ? identity : serial
            }
            guard Set(identities).count == 1 else {
                return LinkReading(name: "检测到多台设备", detail: "请只保留一台在线设备；多余的无线连接可以在连接页断开。", link: .offline)
            }
            online.sort { left, right in
                let a = transportLabel(left) == "USB 连接" ? 0 : left.contains(where: \.isWhitespace) ? 2 : 1
                let b = transportLabel(right) == "USB 连接" ? 0 : right.contains(where: \.isWhitespace) ? 2 : 1
                return a < b
            }
        }
        guard let only = online.first else {
            let unauthorized = listed.contains { $0.state == "unauthorized" || $0.state == "authorizing" }
            let detail = unauthorized ? "请在手机上允许 USB 调试。" : "接入 USB，或在手机上开启无线调试后再连接。"
            return LinkReading(name: unauthorized ? "等待手机授权" : "手机未连接", detail: detail, link: .offline,
                               reconnectable: !unauthorized)
        }
        if let trustedSerial, only == trustedSerial {
            let alive = StationRunner.capture(adb, ["-s", only, "shell", "true"], timeout: 2)
            if !alive.succeeded {
                return LinkReading(name: "手机未连接", detail: "调试连接已中断。", link: .offline,
                                   reconnectable: true)
            }
            return LinkReading(name: "PGT-AN20", detail: "", transport: transportLabel(only), link: .connected, serial: only)
        }
        let model = StationRunner.capture(adb, ["-s", only, "shell", "getprop", "ro.product.model"], timeout: 2)
        let rawName = model.output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard model.succeeded, !rawName.isEmpty else {
            return LinkReading(name: "无法识别手机型号", detail: "请检查连接，然后重新打开面板。", link: .offline)
        }
        if rawName == "PGT-AN20" {
            return LinkReading(name: rawName, detail: "", transport: transportLabel(only), link: .connected, serial: only)
        }
        return LinkReading(name: rawName, detail: "该型号尚未验证。请先查看项目说明中的设备支持范围。",
                           transport: transportLabel(only), link: .unverified)
    }

    private nonisolated static func transportLabel(_ serial: String) -> String {
        let wireless = serial.contains(":") || serial.contains("._adb-tls-connect._tcp")
        return wireless ? "无线连接" : "USB 连接"
    }

    private func accept(_ revision: Int, name: String, detail: String, transport: String = "",
                        link newLink: Link, serial newSerial: String? = nil, readingControls: Bool,
                        reconnectable: Bool = false) {
        guard revision == statusRevision else { return }
        if deviceName != name { deviceName = name }
        if deviceDetail != detail { deviceDetail = detail }
        if self.transport != transport { self.transport = transport }
        if link != newLink { link = newLink }
        let serialChanged = serial != newSerial
        if serialChanged {
            connectionEpoch += 1
            serial = newSerial
            battery = nil
            lastHeartbeat = .distantPast
        }
        if newLink == .connected, let newSerial {
            trustedSerial = newSerial
            pairedDeviceName = name == "PGT-AN20" ? "荣耀 PGT-AN20" : name
        } else {
            trustedSerial = nil
        }
        if let newSerial {
            if (readingControls || serialChanged), feedback.activity == nil {
                refreshStayAwake(serial: newSerial)
                onRefreshTorch()
            }
            markHeartbeat()
        } else if stayAwake {
            stayAwake = false
        }
        onMenuIcon?(isConnected)
        onSerial(newSerial)
        considerReconnect(reconnectable: reconnectable)
    }

    private func refreshStayAwake(serial: String) {
        let epoch = controlEpoch
        let connection = connectionEpoch
        Task.detached(priority: .utility) {
            let awake = Self.stayAwakeEnabled(serial)
            let reading = Self.readBattery(serial)
            await MainActor.run {
                guard epoch == self.controlEpoch, connection == self.connectionEpoch,
                      self.feedback.activity == nil, self.serial == serial else { return }
                self.stayAwake = awake
                self.battery = reading
            }
        }
    }

    private nonisolated static func readBattery(_ serial: String) -> BatteryReport.Reading? {
        guard let adb = StationRunner.executable("adb") else { return nil }
        let result = StationRunner.capture(adb, ["-s", serial, "shell", "dumpsys", "battery"], timeout: 8)
        guard result.succeeded else { return nil }
        return BatteryReport.parse(result.output)
    }

    private nonisolated static func stayAwakeEnabled(_ serial: String) -> Bool {
        guard let adb = StationRunner.executable("adb") else { return false }
        let result = StationRunner.capture(adb, ["-s", serial, "shell", "settings", "get", "system", "screen_off_timeout"], timeout: 8)
        let value = Int(result.output.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
        return result.succeeded && value >= 3_600_000
    }

    private func bumpControls() -> Int {
        controlEpoch += 1
        return controlEpoch
    }

    private func markHeartbeat() {
        guard !heartbeatInFlight, Date().timeIntervalSince(lastHeartbeat) >= 2 else { return }
        heartbeatInFlight = true
        lastHeartbeat = Date()
        Task.detached(priority: .utility) {
            self.heartbeat()
            await MainActor.run { self.heartbeatInFlight = false }
        }
    }

    private func considerReconnect(reconnectable: Bool) {
        let reading: ReconnectDecision.Reading
        switch link {
        case .connected, .unverified:
            reading = .online
        case .offline where reconnectable:
            reading = .reconnectable
        case .checking, .offline:
            reading = .idle
        }
        let start = decision.consider(reading, now: Date())
        let waiting = link == .offline && reconnectable && !decision.holdOffline
        if willReconnect != waiting { willReconnect = waiting }
        guard start else { return }
        connectIsManual = false
        setReconnecting(true)
        logger.notice("Automatic connect started")
        launchConnect()
    }

    private func setReconnecting(_ value: Bool) {
        guard isReconnecting != value else { return }
        isReconnecting = value
        onMenuIcon?(isConnected)
    }

    private func launchConnect() {
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("connect.sh", timeout: 30)
            await MainActor.run { self.finishConnect(result) }
        }
    }

    private func finishConnect(_ result: CommandResult) {
        let manual = connectIsManual
        connectIsManual = false
        if manual { feedback.activity = nil }
        setReconnecting(false)
        let held = decision.holdOffline
        decision.finish(success: result.succeeded, cancelled: held, now: Date())
        logger.notice("Connect finished, ok: \(result.succeeded, privacy: .public), manual: \(manual, privacy: .public), held: \(held, privacy: .public)")
        if held {
            if result.succeeded, feedback.activity == nil {
                quietDisconnect()
            }
            return
        }
        if result.succeeded {
            feedback.notice = nil
        } else if manual {
            feedback.notice = result.timedOut
                ? "连接超时。请检查手机的无线调试和 Wi-Fi。"
                : StationText.reason(result.output, fallback: "连接失败。请检查无线调试或 USB 授权。")
        }
        Task { await self.probe(readingControls: result.succeeded || manual) }
    }

    /// 用户已经要求保持断开，但迟到的 connect.sh 又连上了。先核对这一次还算不算数，再拆掉。
    private func quietDisconnect() {
        let generation = connectGeneration
        Task { @MainActor in
            guard generation == self.connectGeneration, self.decision.holdOffline else { return }
            await Task.detached(priority: .utility) {
                _ = StationRunner.scriptResult("disconnect.sh", timeout: 20)
            }.value
            guard generation == self.connectGeneration, self.decision.holdOffline else {
                await self.probe(readingControls: false)
                return
            }
            await self.probe(readingControls: false)
        }
    }
}

enum StationTone {
    case ready
    case caution
    case neutral
}
