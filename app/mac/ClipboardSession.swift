import AppKit
import Foundation

@MainActor
final class ClipboardSession: ObservableObject {
    @Published private(set) var shared = false
    @Published private(set) var automatic = true
    @Published private(set) var phone: ClipboardClip?
    @Published private(set) var mac = ClipboardClip.empty
    @Published private(set) var connected = false
    @Published private(set) var message: String?
    @Published private(set) var busy = false
    var route: () -> (String, String)? = { nil }
    var startMcp: () -> Void = {}
    private let clientID = UUID().uuidString
    private var policy = ClipboardSyncPolicy()
    private var timer: Timer?
    private var inFlight = false
    private var showing = false
    private var failed = false

    init() {
        let timer = Timer(timeInterval: 0.8, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }
    var summary: String { shared ? (automatic ? "自动双向同步" : "共享预览") : "未开启" }
    func show() { showing = true; mac = readMac(); refresh() }
    func hide() { showing = false }
    func configure(shared: Bool? = nil, automatic: Bool? = nil) {
        var args: [String: Any] = [:]
        if let shared { args["shared"] = shared }
        if let automatic { args["automatic"] = automatic }
        perform("station_clipboard_configure", args) { [weak self] data in
            guard let self, let snapshot = try? JSONDecoder().decode(ClipboardSnapshot.self, from: data) else { return }
            self.shared = snapshot.shared
            self.automatic = snapshot.automatic
            self.phone = snapshot.phone
            self.policy.disconnect()
            self.message = snapshot.shared ? "已开启共享。接下来复制的文字会按设置同步。" : "共享已关闭"
        }
    }
    func copyPhoneToMac() {
        guard connected, phone?.kind == "text", let text = phone?.text else { return }
        writeMac(text)
        mac = readMac()
        message = "已复制到 Mac"
    }
    func copyMacToPhone() {
        let current = readMac()
        guard current.kind == "text", let text = current.text, !text.isEmpty else { return }
        perform("station_clipboard_set", ["text": text]) { [weak self] _ in
            self?.message = "已复制到手机"
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
                let data = try await ClipboardRPC.call(endpoint: endpoint, token: token, name: name, arguments: arguments)
                done(data)
            } catch { message = error.localizedDescription }
            busy = false
            refresh()
        }
    }
    private func refresh() {
        guard !inFlight, !busy else { return }
        guard let (endpoint, token) = route() else {
            connected = false; phone = nil; policy.disconnect(); failed = true
            return
        }
        let count = NSPasteboard.general.changeCount
        let current = shared || showing ? readMac() : .empty
        if shared || showing { mac = current }
        let baseline = policy.phoneVersion == nil || failed
        var args: [String: Any] = [:]
        if shared {
            args = ["clientId": clientID, "macVersion": String(count), "macKind": current.kind]
            if let text = current.text { args["macText"] = text }
            if !baseline, let version = policy.phoneVersion { args["phoneVersion"] = version }
        }
        inFlight = true
        Task {
            defer { inFlight = false }
            do {
                let data = try await ClipboardRPC.call(endpoint: endpoint, token: token,
                    name: "station_clipboard_exchange", arguments: args)
                let snapshot = try JSONDecoder().decode(ClipboardSnapshot.self, from: data)
                let wasShared = shared
                let wasAutomatic = automatic
                shared = snapshot.shared
                automatic = snapshot.automatic
                phone = snapshot.phone
                connected = snapshot.error == nil
                if let error = snapshot.error {
                    message = error; policy.disconnect(); failed = true
                    return
                }
                let newBaseline = baseline || !wasShared || wasAutomatic != automatic
                if let text = policy.receive(snapshot, sentCount: count, currentCount: NSPasteboard.general.changeCount,
                                             current: readMac(), baseline: newBaseline) {
                    writeMac(text)
                    mac = readMac()
                    message = "手机 → Mac · 已同步"
                } else if snapshot.appliedMac == true { message = "Mac → 手机 · 已同步" }
                else if failed { message = nil }
                if !shared { phone = nil; mac = .empty; policy.disconnect() }
                failed = false
            } catch {
                connected = false; phone = nil; policy.disconnect(); failed = true
                if showing || shared { message = error.localizedDescription }
            }
        }
    }
    private func readMac() -> ClipboardClip {
        let board = NSPasteboard.general
        let types = board.types ?? []
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
            || types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) {
            return ClipboardClip(kind: "sensitive", text: nil)
        }
        if types.contains(.fileURL) { return ClipboardClip(kind: "unsupported", text: nil) }
        guard let text = board.string(forType: .string) else {
            return ClipboardClip(kind: types.isEmpty ? "empty" : "unsupported", text: nil)
        }
        return text.utf16.count > 100_000 ? ClipboardClip(kind: "oversize", text: nil) : ClipboardClip(kind: "text", text: text)
    }
    private func writeMac(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}
