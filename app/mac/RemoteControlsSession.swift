import AppKit
import CryptoKit
import Foundation

struct RemoteControlsReading: Decodable {
    struct Torch: Decodable { var available: Bool; var on: Bool?; var reason: String }
    var model: String
    var verified: Bool
    var stayAwake: Bool
    var stayAwakeAvailable: Bool
    var stayAwakeReason: String
    var screenshotAvailable: Bool
    var screenshotReason: String
    var torch: Torch
    var supportedModel: Bool { verified && model.replacingOccurrences(of: "_", with: "-") == "PGT-AN20" }
}

enum RemoteCapture {
    struct Info: Decodable { var requestId: String; var path: String; var size: Int; var targetVersion: String; var sha256: String }
    struct Page: Decodable { var offset: Int; var length: Int; var hex: String; var targetVersion: String }
    static let limit = 32 * 1024 * 1024
    static func validate(_ info: Info, id: String) throws {
        guard info.requestId == id, UUID(uuidString: id) != nil,
              info.path == "/storage/emulated/0/Download/手机工位/.captures/\(id.lowercased()).png",
              (8...limit).contains(info.size), !info.targetVersion.isEmpty,
              info.sha256.count == 64 else { throw ClipboardRPC.Failure(message: "截图信息无效，请更新手机工位") }
    }
    static func decode(_ page: Page, info: Info, offset: Int) throws -> Data {
        guard page.offset == offset, page.targetVersion == info.targetVersion,
              page.length > 0, page.length <= min(65536, info.size - offset),
              page.hex.utf8.count == page.length * 2 else { throw ClipboardRPC.Failure(message: "截图已变化或下载不完整") }
        let chars = Array(page.hex.utf8)
        func digit(_ value: UInt8) -> UInt8? {
            switch value { case 48...57: return value - 48; case 97...102: return value - 87; case 65...70: return value - 55; default: return nil }
        }
        var bytes = Data(capacity: page.length)
        for index in stride(from: 0, to: chars.count, by: 2) {
            guard let high = digit(chars[index]), let low = digit(chars[index + 1]) else {
                throw ClipboardRPC.Failure(message: "截图数据无效")
            }
            bytes.append(high * 16 + low)
        }
        return bytes
    }
    static func verify(_ data: Data, info: Info) throws {
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard data.count == info.size, data.prefix(8) == Data([137, 80, 78, 71, 13, 10, 26, 10]),
              hash == info.sha256.lowercased(), NSImage(data: data) != nil else {
            throw ClipboardRPC.Failure(message: "截图校验失败，没有保存不完整的图片")
        }
    }
}

/// 只在无 adb 时接管这三个控件。只读检查可继续；操作失败从不重放。
@MainActor
final class RemoteControlsSession: ObservableObject {
    @Published private(set) var reading: RemoteControlsReading?
    @Published private(set) var failure: String?
    struct PendingCapture: Codable { var id: String; var endpoint: String; var localFile: String? }
    @Published private(set) var pendingCapture: PendingCapture?
    private let captureDefaults: UserDefaults
    private let captureKey = "phoneStationPendingCapture"
    var route: () -> (String, String)? = { nil }
    var monitoring = false
    private var nextPoll = Date.distantPast
    var onFinishedCapture: () -> Void = {}
    private let feedback: StationFeedback
    private var timer: Timer?
    private var inFlight = false
    private var operation = false
    private var epoch = 0
    private var readAt = Date.distantPast
    private var readRoute: (String, String)?
    private let rpc: (String, String, String, [String: Any]) async throws -> Data

    init(feedback: StationFeedback, startTimer: Bool = true, defaults: UserDefaults = .standard,
         rpc: ((String, String, String, [String: Any]) async throws -> Data)? = nil) {
        self.feedback = feedback
        captureDefaults = defaults
        if let data = defaults.data(forKey: captureKey),
           let pending = try? JSONDecoder().decode(PendingCapture.self, from: data), UUID(uuidString: pending.id) != nil {
            pendingCapture = pending
        }
        self.rpc = rpc ?? { endpoint, token, name, args in
            try await ClipboardRPC.call(endpoint: endpoint, token: token, name: name, arguments: args, timeout: 25)
        }
        if startTimer {
            let timer = Timer(timeInterval: 4, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.monitoring, Date() >= self.nextPoll else { return }
                    self.refresh()
                }
            }
            RunLoop.main.add(timer, forMode: .common); self.timer = timer
        }
    }
    private var current: RemoteControlsReading? {
        guard let route = route(), let readRoute, route.0 == readRoute.0, route.1 == readRoute.1,
              Date().timeIntervalSince(readAt) < 30, reading?.supportedModel == true else { return nil }
        return reading
    }
    var canCapture: Bool { current?.screenshotAvailable == true && feedback.activity == nil && pendingCapture == nil }
    var canRecoverCapture: Bool {
        guard let pendingCapture, let currentRoute = route() else { return false }
        return currentRoute.0 == pendingCapture.endpoint && feedback.activity == nil
    }
    var captureRecoveryTitle: String { pendingCapture?.localFile == nil ? "继续下载上次截图" : "清理手机临时截图" }
    private func rememberCapture(_ value: PendingCapture?) {
        pendingCapture = value
        if let value, let data = try? JSONEncoder().encode(value) { captureDefaults.set(data, forKey: captureKey) }
        else { captureDefaults.removeObject(forKey: captureKey) }
    }
    var canStayAwake: Bool { current?.stayAwakeAvailable == true && feedback.activity == nil }
    var canTorch: Bool { current?.torch.available == true && current?.torch.on != nil && feedback.activity == nil }
    var stayAwake: Bool { reading?.stayAwake ?? false }
    var torchOn: Bool { reading?.torch.on ?? false }
    var reason: String? {
        if let failure { return failure }
        guard let reading else { return route() == nil ? nil : "正在检查远程控制状态…" }
        if !reading.supportedModel { return "当前机型尚未验证，远程设备操作未启用。" }
        return [reading.stayAwakeReason, reading.screenshotReason, reading.torch.reason]
            .filter { !$0.isEmpty }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.joined(separator: "；").nilIfEmpty
    }
    func refresh() {
        guard !operation, feedback.activity == nil else { return }
        guard let (endpoint, token) = route() else {
            reading = nil; readRoute = nil; failure = nil; epoch += 1; return
        }
        guard !inFlight else { return }
        inFlight = true
        let ticket = epoch
        Task {
            defer { inFlight = false; nextPoll = Date().addingTimeInterval(10) }
            do {
                let data = try await rpc(endpoint, token, "station_controls_status", [:])
                guard ticket == epoch, let now = route(), now.0 == endpoint, now.1 == token else { return }
                reading = try JSONDecoder().decode(RemoteControlsReading.self, from: data)
                readAt = Date(); readRoute = (endpoint, token); failure = nil
            } catch {
                guard ticket == epoch, let now = route(), now.0 == endpoint, now.1 == token else { return }
                reading = nil; failure = "无法核对远程控制状态，请检查连接、Shizuku 或更新手机工位。"
            }
        }
    }
    func setStayAwake(_ on: Bool) {
        guard canStayAwake, on != stayAwake else { return }
        perform(name: "station_stay_awake", on: on, activity: "正在更改远程息屏设置…",
                notice: on ? "已保持亮屏。" : "已恢复息屏。")
    }
    func setTorch(_ on: Bool) {
        guard canTorch, on != torchOn else { return }
        perform(name: "station_torch", on: on, activity: on ? "正在远程打开手电筒…" : "正在远程关闭手电筒…",
                notice: on ? "手电筒已打开。" : "手电筒已关闭。")
    }
    private func perform(name: String, on: Bool, activity: String, notice: String) {
        guard let (endpoint, token) = route() else { return }
        operation = true; epoch += 1; feedback.activity = activity; feedback.notice = nil
        Task {
            do {
                let data = try await rpc(endpoint, token, name, ["on": on])
                let result = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                let key = name == "station_torch" ? "on" : "stayAwake"
                guard result?[key] as? Bool == on else { throw ClipboardRPC.Failure(message: "手机状态未确认") }
                if name == "station_torch" { reading?.torch.on = on } else { reading?.stayAwake = on }
                feedback.notice = notice
            } catch {
                reading = nil
                feedback.notice = "\(error.localizedDescription)；结果可能已生效，正在重新核对状态。"
            }
            feedback.activity = nil; operation = false; refresh()
        }
    }
    func screenshot() {
        guard canCapture, let (endpoint, token) = route() else { return }
        let id = UUID().uuidString.lowercased()
        rememberCapture(PendingCapture(id: id, endpoint: endpoint, localFile: nil))
        runCapture(id: id, endpoint: endpoint, token: token, new: true)
    }
    func recoverCapture() {
        guard canRecoverCapture, let pending = pendingCapture, let (endpoint, token) = route() else { return }
        runCapture(id: pending.id, endpoint: endpoint, token: token, new: false)
    }
    func discardCapture() {
        guard canRecoverCapture, let pending = pendingCapture, let (endpoint, token) = route() else { return }
        operation = true; feedback.activity = "正在清理上次手机截图…"
        Task {
            defer { feedback.activity = nil; operation = false; refresh() }
            do {
                _ = try await rpc(endpoint, token, "station_screen_capture_release", ["requestId": pending.id])
                rememberCapture(nil); feedback.notice = "手机临时截图已清理。"
            } catch { feedback.notice = "清理结果未确认，保留截图编号：\(pending.id)" }
        }
    }
    private func captureInformation(id: String, endpoint: String, token: String, new: Bool) async throws -> Data {
        let jobID = id.replacingOccurrences(of: "-", with: "")
        let data = new
            ? try await rpc(endpoint, token, "station_screen_capture_start", ["jobId": jobID, "requestId": id])
            : try await rpc(endpoint, token, "station_operation_status", ["jobId": jobID])
        var receipt = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let deadline = Date().addingTimeInterval(90)
        while receipt?["state"] as? String == "queued" || receipt?["state"] as? String == "running" {
            guard Date() < deadline else { throw ClipboardRPC.Failure(message: "截图任务等待超时；可查询原编号") }
            try await Task.sleep(nanoseconds: 500_000_000)
            let data = try await rpc(endpoint, token, "station_operation_status", ["jobId": jobID])
            receipt = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        }
        if receipt?["state"] as? String == "completed", let result = receipt?["result"] as? [String: Any] {
            return try JSONSerialization.data(withJSONObject: result)
        }
        // A restart or an older pending capture may leave a PNG even when its job receipt is missing.
        return try await rpc(endpoint, token, "station_screen_capture_status", ["requestId": id])
    }
    private func runCapture(id: String, endpoint: String, token: String, new: Bool) {
        operation = true; epoch += 1
        feedback.activity = new ? "正在远程截取画面…" : "正在恢复上次截图…"; feedback.notice = nil
        Task {
            defer { feedback.activity = nil; operation = false; refresh() }
            do {
                if pendingCapture?.localFile == nil {
                    let data = try await captureInformation(id: id, endpoint: endpoint, token: token, new: new)
                    if !new, let status = try JSONSerialization.jsonObject(with: data) as? [String: Any], status["available"] as? Bool == false {
                        throw ClipboardRPC.Failure(message: "原截图未找到或已过期；可放弃并清理原编号")
                    }
                    let info = try JSONDecoder().decode(RemoteCapture.Info.self, from: data)
                    try RemoteCapture.validate(info, id: id)
                    var png = Data(capacity: info.size)
                    let deadline = Date().addingTimeInterval(180)
                    while png.count < info.size {
                        guard Date() < deadline else { throw ClipboardRPC.Failure(message: "截图下载超时") }
                        feedback.activity = "正在下载截图… \(png.count * 100 / info.size)%"
                        let data = try await rpc(endpoint, token, "station_file_read_bytes", ["path": info.path, "offset": png.count, "length": 65536])
                        let page = try JSONDecoder().decode(RemoteCapture.Page.self, from: data)
                        png.append(try RemoteCapture.decode(page, info: info, offset: png.count))
                    }
                    try RemoteCapture.verify(png, info: info)
                    let folder = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Pictures/scrcpy")
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let date = DateFormatter(); date.dateFormat = "yyyyMMdd-HHmmss"
                    let file = folder.appendingPathComponent("\(date.string(from: Date()))-remote-\(id.prefix(6)).png")
                    try png.write(to: file, options: .atomic)
                    rememberCapture(PendingCapture(id: id, endpoint: endpoint, localFile: file.path))
                    NSWorkspace.shared.open(file); onFinishedCapture()
                }
                _ = try await rpc(endpoint, token, "station_screen_capture_release", ["requestId": id])
                rememberCapture(nil); feedback.notice = "截图已保存，手机临时文件已清理。"
            } catch {
                feedback.notice = pendingCapture?.localFile == nil
                    ? "\(error.localizedDescription)。可按原编号继续下载，本次未重拍。编号：\(id)"
                    : "截图已保存；手机清理未确认，可继续清理。编号：\(id)"
            }
        }
    }
}

private extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }
