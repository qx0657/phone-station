import AppKit
import Foundation
import OSLog
import ImageIO

@MainActor
final class ClipboardSession: ObservableObject {
    @Published private(set) var shared = false
    @Published private(set) var automatic = true
    @Published private(set) var images = false
    @Published private(set) var imagesSupported = false
    @Published private(set) var phone: ClipboardClip?
    @Published private(set) var mac = ClipboardClip.empty
    @Published private(set) var connected = false
    @Published private(set) var recovering = false
    @Published private(set) var checking = false
    @Published private(set) var message: String?
    @Published private(set) var busy = false
    @Published private(set) var blocker: ClipboardAvailability?
    @Published private(set) var lastSyncAt: Date?
    @Published private(set) var lastSyncDirection: String?
    var route: () -> (String, String)? = { nil }
    var remote: () -> Bool = { false }
    private let clientID = UUID().uuidString
    private var policy = ClipboardSyncPolicy()
    private var recovery = ClipboardRecovery()
    private var timer: Timer?
    private var inFlight = false
    private var showing = false
    private var requestStarted: TimeInterval?
    private var slowAfter: TimeInterval = 6
    private var nextPoll: TimeInterval = 0
    private let readLocal: () -> ClipboardLocalState
    private let writeLocal: (String) -> Void
    private let readImage: (Int) -> Data?
    private var attemptedImageCount: Int?
    private var lastSentCount: Int?
    private let rpc: ((String, String, String, [String: Any]) async throws -> Data)?
    private let clock: () -> TimeInterval
    private let logger = Logger(subsystem: "com.qx0657.phonestation", category: "clipboard")

    init(startTimer: Bool = true, readLocal: (() -> ClipboardLocalState)? = nil,
         writeLocal: ((String) -> Void)? = nil, readImage: ((Int) -> Data?)? = nil,
         rpc: ((String, String, String, [String: Any]) async throws -> Data)? = nil,
         clock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.readLocal = readLocal ?? Self.readPasteboard
        self.writeLocal = writeLocal ?? Self.writePasteboard
        self.readImage = readImage ?? Self.readPasteboardImage
        self.rpc = rpc
        self.clock = clock
        if startTimer {
            let timer = Timer(timeInterval: 0.8, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
    }
    var summary: String {
        guard shared else { return "未开启" }
        if let blocker { return blocker.status }
        guard connected else { return recovering ? "正在恢复同步" : "等待剪贴板连接" }
        if checking { return "同步响应较慢" }
        if phone?.kind == "locked" { return "手机已锁定 · 同步暂停" }
        if automatic, let reason = pauseReason(phone, device: "手机") { return reason }
        if automatic, let reason = pauseReason(mac, device: "Mac") { return reason }
        return automatic ? "自动双向同步" : "自动同步已暂停"
    }
    private func pauseReason(_ clip: ClipboardClip?, device: String) -> String? {
        switch clip?.kind {
        case "sensitive": return "\(device) 敏感内容 · 已跳过"
        case "oversize": return "\(device) 文字过长 · 已跳过"
        default: return nil
        }
    }
    func show() { showing = true; nextPoll = 0; refresh() }
    func hide() { showing = false }
    func configure(shared: Bool? = nil, automatic: Bool? = nil, images: Bool? = nil) {
        var args: [String: Any] = [:]
        if let shared { args["shared"] = shared }
        if let automatic { args["automatic"] = automatic }
        else if shared == true { args["automatic"] = true }
        if let images { args["images"] = images }
        perform("station_clipboard_configure", args) { [weak self] data in
            guard let self, let snapshot = try? JSONDecoder().decode(ClipboardSnapshot.self, from: data) else { return }
            self.applySettings(snapshot)
            self.policy.disconnect()
            self.recovery.reset()
            self.connected = false
            self.recovering = snapshot.shared
            self.message = snapshot.shared ? "正在连接剪贴板…" : "共享已关闭"
            self.lastSyncAt = nil
            self.lastSyncDirection = nil
        }
    }
    private func perform(_ name: String, _ arguments: [String: Any], done: @escaping (Data) -> Void) {
        guard !busy, let (endpoint, token) = route() else {
            message = "请先连接 MCP 服务，稍后重试"
            return
        }
        busy = true
        Task {
            while inFlight { try? await Task.sleep(nanoseconds: 50_000_000) }
            do {
                let data = try await call(endpoint, token, name, arguments)
                done(data)
            } catch { message = error.localizedDescription }
            busy = false
            nextPoll = 0
            refresh()
        }
    }
    private func sample() -> ClipboardLocalState {
        let local = shared ? readLocal() : ClipboardLocalState(count: 0, clip: .empty)
        if shared { mac = local.clip }
        return local
    }
    func refresh() {
        // Sample newer copies while a request waits; no content queue is needed.
        let local = sample()
        let availableRoute = route()
        if availableRoute == nil { interrupted(nil) }
        if inFlight, availableRoute != nil, connected, let requestStarted, clock() - requestStarted >= slowAfter {
            checking = shared
        }
        guard !inFlight, !busy else { return }
        guard let (endpoint, token) = availableRoute else { return }
        // New Mac copies bypass the idle remote polling interval.
        guard clock() >= nextPoll || (shared && local.count != lastSentCount) else { return }
        let needsProbe = recovery.recovering || policy.phoneVersion == nil
        let wasRecovering = recovering
        inFlight = true
        requestStarted = nil
        Task {
            var sentCount = local.count
            defer {
                inFlight = false
                checking = false
                requestStarted = nil
                nextPoll = clock() + (remote() ? 2 : 0)
                if shared, sample().count != sentCount, !busy { refresh() }
            }
            do {
                var current = local
                var baseline = policy.phoneVersion == nil
                var resume = false
                var version = policy.phoneVersion
                if needsProbe {
                    // Reconcile an unknown outcome without replaying the old exchange.
                    let data = try await call(endpoint, token, "station_clipboard_state", ["refresh": true])
                    let snapshot = try JSONDecoder().decode(ClipboardSnapshot.self, from: data)
                    applySettings(snapshot)
                    if let error = snapshot.error { interrupted(error); return }
                    guard snapshot.shared else {
                        policy.disconnect(); recovery.reset(); recovering = false; connected = true
                        phone = nil; mac = .empty; message = nil
                        return
                    }
                    current = sample()
                    switch recovery.decision(snapshot, local: current, now: clock()) {
                    case .sendNewCopy:
                        resume = true; baseline = true; version = snapshot.phoneVersion
                    case .receivePhoneCopy:
                        baseline = false; version = nil
                    case .baseline:
                        baseline = true; version = nil
                    }
                    // A changed route is reconciled again on the next refresh.
                    guard let currentRoute = route(), currentRoute.0 == endpoint, currentRoute.1 == token else {
                        interrupted(nil); return
                    }
                }
                let wireKind = current.clip.kind == "image" && !imagesSupported ? "unsupported" : current.clip.kind
                var args: [String: Any] = ["clientId": clientID, "macVersion": String(current.count), "macKind": wireKind]
                if let text = current.clip.text { args["macText"] = text }
                if current.clip.kind == "image", images, imagesSupported, automatic,
                   !baseline, lastSentCount != current.count, attemptedImageCount != current.count {
                    attemptedImageCount = current.count
                    if let png = readImage(current.count), png.count <= 4 * 1024 * 1024 {
                        args["macImage"] = png.base64EncodedString()
                    } else { message = "图片无法读取或超过 4 MB / 3200 万像素，已跳过" }
                }
                if let version { args["phoneVersion"] = version }
                if resume { args["resume"] = true }
                sentCount = current.count
                lastSentCount = sentCount
                recovery.sent(count: sentCount)
                let data = try await call(endpoint, token, "station_clipboard_exchange", args)
                guard let currentRoute = route(), currentRoute.0 == endpoint, currentRoute.1 == token else {
                    interrupted(nil); return
                }
                let snapshot = try JSONDecoder().decode(ClipboardSnapshot.self, from: data)
                let settingsChanged = shared != snapshot.shared || automatic != snapshot.automatic || images != (snapshot.images ?? false)
                applySettings(snapshot)
                if let error = snapshot.error { interrupted(error); return }
                let latest = sample()
                var acknowledgedCount = sentCount
                if let text = policy.receive(snapshot, sentCount: sentCount, currentCount: latest.count,
                                             current: latest.clip, baseline: baseline || settingsChanged) {
                    writeLocal(text)
                    let written = readLocal()
                    mac = written.clip
                    acknowledgedCount = written.count
                    noteSync("手机 → Mac")
                } else if snapshot.savedImage == true { noteSync("Mac → 手机相册") }
                else if snapshot.appliedMac == true { noteSync("Mac → 手机") }
                else if wasRecovering || needsProbe { message = nil }
                if let error = snapshot.imageError { message = error }
                connected = true
                recovering = false
                recovery.succeeded(snapshot, count: acknowledgedCount, now: clock())
                if wasRecovering { logger.notice("Clipboard exchange recovered") }
                if !shared {
                    phone = nil; mac = .empty; policy.disconnect(); recovery.reset()
                } else if snapshot.phone?.kind == "locked" || snapshot.phone?.kind == "sensitive" || snapshot.phone?.kind == "oversize" {
                    // Unlocking or unblocking content establishes a baseline instead of copying old text.
                    policy.disconnect(); recovery.reset()
                }
            } catch { interrupted(error.localizedDescription) }
        }
    }
    private func call(_ endpoint: String, _ token: String, _ name: String, _ arguments: [String: Any]) async throws -> Data {
        requestStarted = nil
        checking = false
        slowAfter = arguments["macImage"] == nil ? 6 : 25
        if let rpc {
            requestStarted = clock()
            return try await rpc(endpoint, token, name, arguments)
        }
        return try await ClipboardRPC.call(endpoint: endpoint, token: token, name: name, arguments: arguments,
            timeout: arguments["macImage"] == nil ? 8 : 30, onStart: {
                let valid = await MainActor.run {
                    guard let current = self.route(), current.0 == endpoint, current.1 == token else { return false }
                    self.requestStarted = self.clock()
                    return true
                }
                guard valid else {
                    throw ClipboardRPC.Failure(message: "剪贴板连接已改变")
                }
            })
    }
    private func noteSync(_ direction: String) {
        lastSyncAt = Date()
        lastSyncDirection = direction
        message = nil
    }
    private func applySettings(_ snapshot: ClipboardSnapshot) {
        shared = snapshot.shared
        automatic = snapshot.automatic
        images = snapshot.images ?? false
        imagesSupported = snapshot.imagesSupported == true
        if !shared { lastSyncAt = nil; lastSyncDirection = nil }
        phone = snapshot.phone
        blocker = snapshot.availability.flatMap {
            !$0.reason.isEmpty && $0.action == "permissions" ? $0 : nil
        }
    }
    private func interrupted(_ reason: String?) {
        if connected { logger.notice("Clipboard exchange interrupted; checking state before recovery") }
        connected = false
        checking = false
        phone = nil
        recovery.interrupted()
        recovering = shared
        if let reason, showing || shared { message = reason }
        if route() == nil { blocker = nil }
    }
    private static func readPasteboard() -> ClipboardLocalState {
        let board = NSPasteboard.general
        let count = board.changeCount
        let types = board.types ?? []
        let clip: ClipboardClip
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
            || types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) {
            clip = ClipboardClip(kind: "sensitive", text: nil)
        } else if types.contains(.fileURL) {
            clip = ClipboardClip(kind: "unsupported", text: nil)
        } else if types.contains(.png) || types.contains(.tiff) {
            // Metadata only. Read and encode pixels solely for a new event with images enabled.
            clip = ClipboardClip(kind: "image", text: nil)
        } else if let text = board.string(forType: .string) {
            clip = text.utf16.count > 100_000 ? ClipboardClip(kind: "oversize", text: nil) : ClipboardClip(kind: "text", text: text)
        } else { clip = ClipboardClip(kind: types.isEmpty ? "empty" : "unsupported", text: nil) }
        return ClipboardLocalState(count: count, clip: clip)
    }
    private static func readPasteboardImage(_ expectedCount: Int) -> Data? {
        let board = NSPasteboard.general
        let types = board.types ?? []
        guard board.changeCount == expectedCount, !types.contains(.fileURL),
              !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")),
              !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")),
              let data = board.data(forType: .png) ?? board.data(forType: .tiff),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              width.doubleValue > 0, height.doubleValue > 0,
              width.doubleValue * height.doubleValue <= 32_000_000,
              board.changeCount == expectedCount else { return nil }
        let png: Data
        if types.contains(.png), CGImageSourceGetType(source) as String? == "public.png" {
            png = data
        } else {
            guard let bitmap = NSBitmapImageRep(data: data),
                  let encoded = bitmap.representation(using: .png, properties: [:]) else { return nil }
            png = encoded
        }
        return board.changeCount == expectedCount && png.count <= 4 * 1024 * 1024 ? png : nil
    }
    private static func writePasteboard(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}
