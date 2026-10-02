import Foundation
import OSLog

private enum Link: Sendable {
    case checking
    case connected
    case unverified
    case offline
}

private struct LinkReading: Sendable {
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
    @Published private var willReconnect = false

    var onMenuIcon: (@MainActor (Bool) -> Void)?
    var allow: () -> Bool = { false }
    var onRefreshTorch: () -> Void = {}
    var onSerial: (String?) -> Void = { _ in }

    private let feedback: StationFeedback
    private var link: Link = .checking
    /// 已经确认是 PGT-AN20 的序列号。菜单栏复查时序列号没变就不再读型号。
    private var trustedSerial: String?
    private var linkWatch: Task<Void, Never>?
    private static let linkWatchInterval: UInt64 = 2_000_000_000
    private var pairedDeviceName = ""
    private var decision = ReconnectDecision()
    private var connectIsManual = false
    /// 断开进行中时又点了连接，晚到的断开结果不要把暂停写回去。
    private var connectGeneration = 0
    private let logger = Logger(subsystem: "com.qx0657.phonestation", category: "link")
    private var statusRevision = 0
    private var controlEpoch = 0

    init(feedback: StationFeedback) {
        self.feedback = feedback
    }

    var isConnected: Bool { link == .connected || remoteConnected }
    var isChecking: Bool { link == .checking && !remoteConnected }
    var isUnverified: Bool { link == .unverified }
    var statusLabel: String {
        if isConnected { return "已连接" }
        if isReconnecting { return "正在重新连接" }
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
            return transport
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
        switch link {
        case .connected: return .ready
        case .unverified: return .caution
        case .checking, .offline: return .neutral
        }
    }

    /// 只合并显示状态；adb 操作仍须持有已验证的序列号。
    func noteRemoteConnection(_ connected: Bool) {
        guard remoteConnected != connected else { return }
        remoteConnected = connected
        onMenuIcon?(isConnected)
    }

    /// A dead wireless MCP route must not leave adb-only actions enabled while
    /// adb devices still reports the old TCP session as online.
    func noteLocalRouteUnavailable() {
        guard let serial, Self.transportLabel(serial) == "无线连接" else { return }
        statusRevision += 1
        accept(statusRevision, name: "手机未连接", detail: "", link: .offline,
               readingControls: false, reconnectable: true)
    }

    /// 面板关着时菜单栏图标也要跟着变。上一次查完隔 2 秒再查。
    /// 这次检查不读息屏、闪光灯和电量，那几项仍在打开面板时读。
    func startWatch() {
        guard linkWatch == nil else { return }
        linkWatch = Task { @MainActor in
            while !Task.isCancelled {
                await self.probe(readingControls: false)
                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: Self.linkWatchInterval)
            }
        }
    }

    func probe(readingControls: Bool) async {
        statusRevision += 1
        let revision = statusRevision
        let trusted = trustedSerial
        let reading = await Task.detached(priority: .utility) {
            Self.readLink(trustedSerial: trusted)
        }.value
        accept(revision, name: reading.name, detail: reading.detail, transport: reading.transport,
               link: reading.link, serial: reading.serial, readingControls: readingControls,
               reconnectable: reading.reconnectable)
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
        let lines = devices.output.split(separator: "\n").dropFirst().map(String.init)
        let online = lines.compactMap { line -> String? in
            let parts = line.split(whereSeparator: \.isWhitespace)
            return parts.count >= 2 && parts[1] == "device" ? String(parts[0]) : nil
        }
        if online.count > 1 {
            return LinkReading(name: "检测到多台设备", detail: "请只保留一台在线设备；多余的无线连接可以在连接页断开。", link: .offline)
        }
        guard let only = online.first else {
            let unauthorized = lines.contains { $0.contains("unauthorized") }
            let detail = unauthorized ? "请在手机上允许 USB 调试。" : "接入 USB，或在手机上开启无线调试后再连接。"
            return LinkReading(name: unauthorized ? "等待手机授权" : "手机未连接", detail: detail, link: .offline,
                               reconnectable: !unauthorized)
        }
        if let trustedSerial, only == trustedSerial {
            if transportLabel(only) == "无线连接" {
                let alive = StationRunner.capture(adb, ["-s", only, "shell", "true"], timeout: 2)
                if !alive.succeeded {
                    return LinkReading(name: "手机未连接", detail: "无线连接已中断。", link: .offline,
                                       reconnectable: true)
                }
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
            serial = newSerial
            battery = nil
        }
        if newLink == .connected, let newSerial {
            trustedSerial = newSerial
            pairedDeviceName = name == "PGT-AN20" ? "荣耀 PGT-AN20" : name
        } else {
            trustedSerial = nil
        }
        if let newSerial {
            if readingControls, feedback.activity == nil {
                refreshStayAwake(serial: newSerial)
                onRefreshTorch()
            }
            Task.detached(priority: .utility) {
                _ = StationRunner.scriptResult("host-state.sh", ["mark"], timeout: 8)
            }
        } else if stayAwake {
            stayAwake = false
        }
        onMenuIcon?(isConnected)
        onSerial(newSerial)
        considerReconnect(reconnectable: reconnectable)
    }

    private func refreshStayAwake(serial: String) {
        let revision = statusRevision
        let epoch = controlEpoch
        Task.detached(priority: .utility) {
            let awake = Self.stayAwakeEnabled(serial)
            let reading = Self.readBattery(serial)
            await MainActor.run {
                guard revision == self.statusRevision, epoch == self.controlEpoch,
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
