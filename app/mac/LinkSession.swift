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
    var phones: [PhoneRoster.Seat] = []
    /// 一台设备都没有，而且不是在等手机上点允许。这种才自动跑 connect.sh。
    var reconnectable = false
    var features: PhoneFeatureState? = nil
    var preferences: PhoneConnectionPreferences? = nil
}

@MainActor
final class LinkSession: ObservableObject {
    @Published private(set) var deviceName = "正在检查设备…"
    @Published private(set) var deviceDetail = ""
    @Published private(set) var transport = ""
    @Published private(set) var serial: String?
    @Published private(set) var phones: [PhoneRoster.Seat] = []
    @Published private(set) var phoneFeatures: PhoneFeatureState?
    @Published private(set) var connectionPreferences: PhoneConnectionPreferences?
    @Published private(set) var battery: BatteryReport.Reading?
    @Published private(set) var stayAwake = false
    @Published private(set) var isReconnecting = false
    @Published private(set) var remoteConnected = false
    @Published private(set) var remoteChecking = false
    @Published private var link: Link = .checking
    @Published private var willReconnect = false
    /// 无线调试已经出现，但这台电脑还没配对。此时出示二维码，并暂停自动重连。
    @Published private(set) var needsWirelessPairing = false

    var onMenuIcon: (@MainActor (Bool) -> Void)?
    var allow: () -> Bool = { false }
    var onRefreshTorch: () -> Void = {}
    var onSerial: (String?) -> Void = { _ in }
    var onPairingNeeded: () -> Void = {}

    private let feedback: StationFeedback
    private let reader: @Sendable (String?) -> LinkReading
    /// 已经确认是 PGT-AN20 的序列号。菜单栏复查时序列号没变就不再读型号。
    private var trustedSerial: String?
    private var linkWatch: Task<Void, Never>?
    private var watching = false
    private static let linkWatchInterval: UInt64 = 1_000_000_000
    private var deviceStream: AdbDeviceStream?
    private var probeInFlight = false
    private var probeAgain = false
    private var controlsRequested = false
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
    /// track-devices 仍列着、但 shell true 失败的序列号。离开列表、网关重新在线或复查成功之前不当作在线。
    private var unreachableSerial: String?
    private var listedSerial: String?
    private var gatewayLocalOnline: Bool?
    private var localMcp: Bool?
    private var panelOpen = false
    private var livenessInFlight = false
    private var livenessGeneration = 0
    private var idleTask: Task<Void, Never>?
    private var recheckTask: Task<Void, Never>?
    var idleLivenessNanoseconds: UInt64 = 15_000_000_000
    private let shellAlive: @Sendable (String) -> Bool
    private let connectCommand: @Sendable () -> CommandResult
    private let featureShell: @Sendable (String) -> String

    init(feedback: StationFeedback,
         reader: @escaping @Sendable (String?) -> LinkReading = { LinkSession.readLink(trustedSerial: $0) },
         shellAlive: @escaping @Sendable (String) -> Bool = { LinkSession.shellTrue($0) },
         connectCommand: @escaping @Sendable () -> CommandResult = { StationRunner.scriptResult("connect.sh", timeout: 30) },
         featureShell: @escaping @Sendable (String) -> String = { LinkSession.readFeatureProjection($0) }) {
        self.feedback = feedback
        self.reader = reader
        self.shellAlive = shellAlive
        self.connectCommand = connectCommand
        self.featureShell = featureShell
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
            if needsWirelessPairing {
                return "这台电脑尚未配对。用手机扫描二维码即可连接。"
            }
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
        if needsWirelessPairing { return "未配对" }
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

    /// 设备增减来自 track-devices。不再每秒执行 adb devices 或 settings get。
    /// 非 TCP 的 adb 服务连不上这条订阅，仍按原来的间隔复查设备列表。
    func startWatch() {
        guard !watching else { return }
        watching = true
        if StationRunner.executable("adb") != nil,
           let endpoint = AdbDeviceStream.Endpoint.configured(StationRunner.environment) {
            let stream = AdbDeviceStream(endpoint: endpoint)
            stream.onFrame = { [weak self] listing in self?.devicesChanged(listing) }
            stream.onDisconnect = { [weak self] in self?.devicesChanged("") }
            deviceStream = stream
            stream.start()
        } else if linkWatch == nil, StationRunner.executable("adb") != nil {
            linkWatch = Task { @MainActor in
                while !Task.isCancelled {
                    await self.probe(readingControls: false)
                    if Task.isCancelled { break }
                    try? await Task.sleep(nanoseconds: Self.linkWatchInterval)
                }
            }
        }
        Task { await probe(readingControls: false) }
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
                   reconnectable: reading.reconnectable, listedPhones: reading.phones)
            if revision == statusRevision {
                if reading.features != nil { phoneFeatures = reading.features }
                if reading.preferences != nil { connectionPreferences = reading.preferences }
            }
        } while probeAgain
        probeInFlight = false
    }

    func stopWatch() {
        watching = false
        linkWatch?.cancel(); linkWatch = nil
        deviceStream?.stop(); deviceStream = nil
        idleTask?.cancel(); idleTask = nil
        stopUnreachableRecheck()
    }

    func devicesChanged(_ listing: String) {
        let devices = AdbDevice.parse(listing)
        statusRevision += 1
        let revision = statusRevision
        // Drop the capability immediately. A pending shell result belongs to the old list.
        if let serial, !devices.contains(where: { $0.serial == serial && $0.state == "device" }) {
            accept(statusRevision, name: "手机未连接", detail: "正在检查连接…", link: .offline,
                   readingControls: false, reconnectable: false)
        }
        let trusted = trustedSerial
        let preferred = PhoneRoster.remembered(defaults: .standard)
        let onlineCount = devices.filter { $0.state == "device" }.count
        Task {
            let reading = await Task.detached(priority: .utility) {
                Self.describe(devices, trustedSerial: trusted, preferred: preferred, adb: StationRunner.executable("adb"))
            }.value
            guard revision == self.statusRevision else { return }
            if onlineCount > 1, reading.phones.count == 1, !self.tidying {
                self.tidying = true
                Task.detached(priority: .utility) {
                    UsageCounters.noteReconnect("link")
                    _ = StationRunner.scriptResult("connect.sh", timeout: 15)
                    await MainActor.run { self.tidying = false }
                }
            }
            let listedGone = reading.serial == nil || (self.unreachableSerial != nil && reading.serial != self.unreachableSerial)
            if listedGone, self.unreachableSerial != nil {
                self.livenessGeneration += 1
                self.unreachableSerial = nil
                self.stopUnreachableRecheck()
            }
            self.accept(revision, name: reading.name, detail: reading.detail, transport: reading.transport,
                        link: reading.link, serial: reading.serial, readingControls: false,
                        reconnectable: reading.reconnectable, listedPhones: reading.phones)
        }
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
        let preferred = PhoneRoster.remembered(defaults: .standard)
        return describe(AdbDevice.parse(devices.output), trustedSerial: trustedSerial, preferred: preferred, adb: adb)
    }

    func selectPhone(_ serial: String) {
        PhoneRoster.remember(serial, defaults: .standard)
        Task { await probe(readingControls: true) }
    }

    private nonisolated static func describe(_ listed: [AdbDevice], trustedSerial: String?, preferred: String? = nil, adb: URL?) -> LinkReading {
        guard let adb else {
            return LinkReading(name: "未找到 adb", detail: "请安装 Android platform-tools。", link: .offline)
        }
        let online = listed.filter { $0.state == "device" }.map(\.serial)
        let seats = PhoneRoster.collapse(serials: online, identity: { serial in
            guard online.count > 1 else { return serial }
            let result = StationRunner.capture(adb, ["-s", serial, "shell", "getprop", "ro.serialno"], timeout: 2)
            let identity = result.output.trimmingCharacters(in: .whitespacesAndNewlines)
            return result.succeeded && !identity.isEmpty && identity != "unknown" ? identity : serial
        }, transport: transportLabel)
        guard let chosen = PhoneRoster.choose(seats, preferred: preferred) else {
            let unauthorized = listed.contains { $0.state == "unauthorized" || $0.state == "authorizing" }
            let detail = unauthorized ? "请在手机上允许 USB 调试。" : "接入 USB，或在手机上开启无线调试后再连接。"
            return LinkReading(name: unauthorized ? "等待手机授权" : "手机未连接", detail: detail, link: .offline,
                               reconnectable: !unauthorized)
        }
        let only = chosen.serial
        if let trustedSerial, only == trustedSerial {
            return LinkReading(name: "PGT-AN20", detail: "", transport: transportLabel(only), link: .connected, serial: only, phones: seats)
        }
        let model = StationRunner.capture(adb, ["-s", only, "shell", "getprop", "ro.product.model"], timeout: 2)
        let rawName = model.output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard model.succeeded, !rawName.isEmpty else {
            return LinkReading(name: "无法识别手机型号", detail: "请检查连接，然后重新打开面板。", link: .offline, phones: seats)
        }
        if rawName == "PGT-AN20" {
            return LinkReading(name: rawName, detail: "", transport: transportLabel(only), link: .connected, serial: only, phones: seats)
        }
        return LinkReading(name: rawName, detail: "该型号尚未验证。请先查看项目说明中的设备支持范围。",
                           transport: transportLabel(only), link: .unverified, phones: seats)
    }

    private nonisolated static func transportLabel(_ serial: String) -> String {
        let wireless = serial.contains(":") || serial.contains("._adb-tls-connect._tcp")
        return wireless ? "无线连接" : "USB 连接"
    }

    private func accept(_ revision: Int, name: String, detail: String, transport: String = "",
                        link newLink: Link, serial newSerial: String? = nil, readingControls: Bool,
                        reconnectable: Bool = false, listedPhones: [PhoneRoster.Seat] = []) {
        guard revision == statusRevision else { return }
        var linkValue = newLink
        var serialValue = newSerial
        var reconnect = reconnectable
        if linkValue == .connected, serialValue == unreachableSerial {
            linkValue = .offline
            serialValue = nil
            reconnect = false
        }
        if phones != listedPhones { phones = listedPhones }
        if deviceName != name { deviceName = name }
        if deviceDetail != detail { deviceDetail = detail }
        if self.transport != transport { self.transport = transport }
        if link != linkValue { link = linkValue }
        let serialChanged = serial != serialValue
        if serialChanged {
            connectionEpoch += 1
            serial = serialValue
            battery = nil
            connectionPreferences = nil
            phoneFeatures = nil
        }
        let previousListed = listedSerial
        if let serialValue {
            listedSerial = serialValue
        } else if unreachableSerial == nil {
            listedSerial = nil
        }
        if listedSerial != previousListed { restartIdleLiveness() }
        if linkValue == .connected, let serialValue {
            trustedSerial = serialValue
            pairedDeviceName = name == "PGT-AN20" ? "荣耀 PGT-AN20" : name
            if needsWirelessPairing { needsWirelessPairing = false }
        } else {
            trustedSerial = nil
        }
        if let serialValue {
            if (readingControls || serialChanged), feedback.activity == nil {
                refreshStayAwake(serial: serialValue)
                onRefreshTorch()
            }
        } else if stayAwake {
            stayAwake = false
        }
        onMenuIcon?(isConnected)
        onSerial(serialValue)
        considerReconnect(reconnectable: reconnect)
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
        let result = StationRunner.capture(adb, ["-s", serial, "shell",
            "settings get system screen_off_timeout; settings get global stay_on_while_plugged_in"], timeout: 8)
        return result.succeeded && StayAwakeReport.held(result.output)
    }

    private func bumpControls() -> Int {
        controlEpoch += 1
        return controlEpoch
    }

    func noteWirelessPaired() {
        needsWirelessPairing = false
        feedback.notice = "已配对，并尝试连接。"
        Task { await probe(readingControls: true) }
    }

    private func considerReconnect(reconnectable: Bool) {
        if needsWirelessPairing {
            if willReconnect { willReconnect = false }
            return
        }
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
        UsageCounters.noteReconnect("link")
        let run = connectCommand
        Task.detached(priority: .userInitiated) {
            let result = run()
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
            needsWirelessPairing = false
            feedback.notice = nil
        } else if !result.timedOut && WirelessConnectFailure.pausesAutomaticReconnect(
            result.output, alreadyPaired: !pairedDeviceName.isEmpty) {
            needsWirelessPairing = true
            if manual { feedback.notice = nil }
            onPairingNeeded()
        } else {
            if !result.timedOut { needsWirelessPairing = false }
            if manual {
                feedback.notice = result.timedOut
                    ? "连接超时。请检查手机的无线调试和 Wi-Fi。"
                    : StationText.reason(result.output, fallback: "连接失败。请检查无线调试或 USB 授权。")
            }
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

    /// 网关本地通道从在线变为离线，而 adb 仍列着这台设备时，做一次存活探测。
    /// 探测失败后，网关重新在线就解除不可达标记并重新读设备列表。
    func noteGatewayLocal(_ online: Bool) {
        let previous = gatewayLocalOnline
        gatewayLocalOnline = online
        if online, previous != true, unreachableSerial != nil {
            livenessGeneration += 1
            unreachableSerial = nil
            stopUnreachableRecheck()
            Task { await self.probe(readingControls: false) }
        }
        guard previous == true, !online, let serial = listedSerial ?? self.serial else { return }
        Task { await confirmAlive(serial) }
    }

    func notePanel(_ open: Bool) {
        guard panelOpen != open else { return }
        panelOpen = open
        restartIdleLiveness()
    }

    func noteLocalMcp(_ enabled: Bool?) {
        guard localMcp != enabled else { return }
        localMcp = enabled
        restartIdleLiveness()
    }

    /// MCP 读不到功能开关时，用一次 shell 读手机投影出来的设置。
    func readProjectedFeatures() async {
        guard let serial else { return }
        let read = featureShell
        let output = await Task.detached { read(serial) }.value
        guard self.serial == serial else { return }
        if let features = PhoneFeatureState.adb(output) { phoneFeatures = features }
        if let preferences = PhoneConnectionPreferences.adb(output) {
            connectionPreferences = preferences
            noteLocalMcp(preferences.localMcp)
        }
    }

    nonisolated static func featureQuery() -> String {
        let names = ["phonestation_enabled"]
            + PhoneFeatureState.keys.map { "phonestation_feature_" + $0.replacingOccurrences(of: ".", with: "_") }
            + ["phonestation_mcp", "phonestation_remote"]
        return names.map { "settings get global \($0)" }.joined(separator: "; ")
    }

    private nonisolated static func shellTrue(_ serial: String) -> Bool {
        guard let adb = StationRunner.executable("adb") else { return false }
        return StationRunner.capture(adb, ["-s", serial, "shell", "true"], timeout: 2).succeeded
    }

    private nonisolated static func readFeatureProjection(_ serial: String) -> String {
        guard let adb = StationRunner.executable("adb") else { return "" }
        let result = StationRunner.capture(adb, ["-s", serial, "shell", featureQuery()], timeout: 8)
        return result.succeeded ? result.output : ""
    }

    /// 本机 MCP 关闭时没有网关探测。面板打开期间每 15 秒查一次，关闭不查。
    /// 不可达标记另有复查，不看面板和 MCP 开关。
    private func restartIdleLiveness() {
        idleTask?.cancel()
        idleTask = nil
        guard panelOpen, localMcp == false, listedSerial != nil else { return }
        idleTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: self.idleLivenessNanoseconds)
                if Task.isCancelled { return }
                guard self.panelOpen, self.localMcp == false, let serial = self.listedSerial else { return }
                await self.confirmAlive(serial)
            }
        }
    }

    /// 标记期间每 15 秒复查一次 shell true。序列号离开列表或复原后停止。
    private func startUnreachableRecheck() {
        guard recheckTask == nil, unreachableSerial != nil else { return }
        recheckTask = Task { @MainActor in
            let mine = self.recheckTask
            defer { if self.recheckTask == mine { self.recheckTask = nil } }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: self.idleLivenessNanoseconds)
                if Task.isCancelled { return }
                guard let serial = self.unreachableSerial else { return }
                await self.confirmAlive(serial)
                if self.unreachableSerial == nil || Task.isCancelled { return }
            }
        }
    }

    private func stopUnreachableRecheck() {
        let running = recheckTask
        recheckTask = nil
        running?.cancel()
    }

    private func confirmAlive(_ serial: String) async {
        if livenessInFlight { return }
        livenessInFlight = true
        let generation = livenessGeneration
        let check = shellAlive
        let alive = await Task.detached { check(serial) }.value
        livenessInFlight = false
        guard generation == livenessGeneration else { return }
        guard listedSerial == serial || self.serial == serial else { return }
        if alive {
            let recover = unreachableSerial == serial
            if recover { unreachableSerial = nil }
            if recover { await self.probe(readingControls: false) }
            return
        }
        let alreadyMarked = unreachableSerial == serial
        unreachableSerial = serial
        listedSerial = serial
        if !alreadyMarked {
            startUnreachableRecheck()
            statusRevision += 1
            accept(statusRevision, name: "手机未连接", detail: "正在重新连接…", link: .offline,
                   readingControls: false, reconnectable: true)
        }
    }
}

enum StationTone {
    case ready
    case caution
    case neutral
}
