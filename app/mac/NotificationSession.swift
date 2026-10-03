import AppKit
import Foundation

@MainActor
final class NotificationSession: ObservableObject {
    @Published private(set) var snapshot: NotificationSnapshot?
    @Published private(set) var connected = false
    @Published private(set) var receiving: Bool
    @Published private(set) var permission = NotificationPermission.unknown
    @Published private(set) var message: String?
    @Published private(set) var busy = false
    @Published private(set) var customBanners: Bool
    @Published private(set) var previewing = false
    var route: () -> (String, String)? = { nil }
    private let clientID = UUID().uuidString
    private var policy = NotificationReceivePolicy()
    private var timer: Timer?
    private var inFlight = false
    private var permissionTime = Date.distantPast
    private let readPermission: () async -> NotificationPermission
    private let authorize: () async throws -> NotificationPermission
    private let deliver: (PhoneNotification) async throws -> Void
    private let rpc: (String, String, String, [String: Any]) async throws -> Data
    private let saveReceiving: (Bool) -> Void
    private let dismissBanners: () -> Void
    private let previewBanner: (PhoneNotification) -> Void
    private var icons: [String: (String?, Date)] = [:]
    private var iconRoute: (String, String)?
    private var generation = 0

    init(startTimer: Bool = true, receiving: Bool? = nil,
         readPermission: (() async -> NotificationPermission)? = nil,
         authorize: (() async throws -> NotificationPermission)? = nil,
         deliver: ((PhoneNotification) async throws -> Void)? = nil,
         rpc: ((String, String, String, [String: Any]) async throws -> Data)? = nil,
         saveReceiving: ((Bool) -> Void)? = nil,
         dismissBanners: (() -> Void)? = nil,
         previewBanner: ((PhoneNotification) -> Void)? = nil) {
        self.receiving = receiving ?? (UserDefaults.standard.object(forKey: "receivePhoneNotifications") as? Bool ?? true)
        self.customBanners = UserDefaults.standard.object(forKey: "phoneNotificationBanners") as? Bool ?? true
        self.readPermission = readPermission ?? { await NotificationDelivery.shared.permission() }
        self.authorize = authorize ?? { try await NotificationDelivery.shared.authorize() }
        self.deliver = deliver ?? { try await NotificationDelivery.shared.post($0) }
        self.rpc = rpc ?? { endpoint, token, name, arguments in
            try await ClipboardRPC.call(endpoint: endpoint, token: token, name: name, arguments: arguments,
                timeout: name == "station_notification_icon" ? 2 : 8)
        }
        self.saveReceiving = saveReceiving ?? { UserDefaults.standard.set($0, forKey: "receivePhoneNotifications") }
        self.dismissBanners = dismissBanners ?? {
            if Bundle.main.bundleURL.pathExtension == "app" { NotificationBannerCenter.shared.dismissAll() }
        }
        self.previewBanner = previewBanner ?? { event in Task { try? await NotificationDelivery.shared.post(event) } }
        if startTimer {
            let timer = Timer(timeInterval: 2, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
    }
    var summary: String {
        guard connected, let snapshot else { return "等待手机连接" }
        guard snapshot.enabled else { return "手机端未开启" }
        guard snapshot.accessGranted else { return "手机需授权通知使用权" }
        guard snapshot.selectedCount > 0 else { return "请在手机选择应用" }
        guard snapshot.listenerConnected else { return "等待手机通知服务" }
        guard receiving else { return "这台 Mac 已暂停接收" }
        guard permission == .authorized else { return "Mac 需允许显示通知" }
        return "正在接收 · \(snapshot.selectedCount) 个应用"
    }
    func show() { permissionTime = .distantPast; refresh() }
    func setReceiving(_ on: Bool) {
        receiving = on
        generation += 1
        saveReceiving(on)
        if !on { dismissBanners() }
        policy.disconnect()
        message = nil
        refresh()
    }
    func setCustomBanners(_ on: Bool) {
        customBanners = on
        UserDefaults.standard.set(on, forKey: "phoneNotificationBanners")
        dismissBanners()
    }
    func preview() {
        guard !previewing else { return }
        previewing = true
        let version = generation
        Task {
            defer { previewing = false }
            var event = PhoneNotification(id: "preview", key: "preview", packageName: "preview",
                app: "手机工位", title: "横幅预览", text: "这是界面验证示例，收到手机通知时会显示对应应用图标。")
            if let (endpoint, token) = route(),
               let data = try? await rpc(endpoint, token, "station_notification_icon", [:]),
               let icon = try? JSONDecoder().decode(NotificationIcon.self, from: data),
               let png = icon.png, let app = icon.app, let pkg = icon.packageName {
                event.app = app; event.packageName = pkg; event.iconPNG = png
            }
            guard version == generation, customBanners, receiving else { return }
            previewBanner(event)
        }
    }
    func requestPermission() {
        guard !busy else { return }
        busy = true
        Task {
            do { permission = try await authorize(); message = permission == .authorized ? nil : "请在 macOS 通知设置中允许手机工位显示通知" }
            catch { message = "Mac 通知授权失败，请在系统设置中检查手机工位的通知权限" }
            permissionTime = Date()
            policy.disconnect()
            busy = false
            refresh()
        }
    }
    func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }
    func configurePhone(_ enabled: Bool) {
        guard !busy, let (endpoint, token) = route() else { return }
        busy = true
        Task {
            while inFlight { try? await Task.sleep(nanoseconds: 50_000_000) }
            defer { busy = false; refresh() }
            do {
                let data = try await rpc(endpoint, token, "station_notification_configure", ["enabled": enabled])
                snapshot = try JSONDecoder().decode(NotificationSnapshot.self, from: data)
                policy.disconnect()
                message = nil
            } catch {
                policy.disconnect()
                message = "手机同步设置未确认，请在手机「设置 → 通知」中核实"
            }
        }
    }
    func refresh() {
        guard !inFlight, !busy else { return }
        inFlight = true
        Task {
            defer { inFlight = false }
            if Date().timeIntervalSince(permissionTime) >= 10 {
                permission = await readPermission()
                permissionTime = Date()
                if permission != .authorized { policy.disconnect(); dismissBanners() }
            }
            guard let (endpoint, token) = route() else {
                connected = false; snapshot = nil; policy.disconnect()
                icons.removeAll(); iconRoute = nil; dismissBanners()
                return
            }
            if iconRoute?.0 != endpoint || iconRoute?.1 != token {
                icons.removeAll(); iconRoute = (endpoint, token); dismissBanners()
            }
            let version = generation
            let consume = receiving && permission == .authorized && snapshot?.enabled == true
            var arguments: [String: Any] = [:]
            if consume {
                arguments["clientId"] = clientID
                if let cursor = policy.cursor { arguments["cursor"] = cursor }
            } else { policy.disconnect() }
            do {
                let data = try await rpc(endpoint, token, consume ? "station_notification_poll" : "station_notification_status", arguments)
                guard let current = route(), current.0 == endpoint, current.1 == token else {
                    connected = false; policy.disconnect(); dismissBanners(); return
                }
                let state = try JSONDecoder().decode(NotificationSnapshot.self, from: data)
                snapshot = state
                connected = true
                message = nil
                if !state.enabled || !state.accessGranted || !state.listenerConnected || state.selectedCount == 0 {
                    icons.removeAll(); dismissBanners()
                }
                if consume && receiving && permission == .authorized && version == generation {
                    let events = policy.receive(state)
                    for event in events {
                        var presented = event
                        presented.iconPNG = await icon(for: event.packageName, endpoint: endpoint, token: token)
                        guard receiving, permission == .authorized, version == generation, let current = route(),
                              current.0 == endpoint, current.1 == token else { policy.disconnect(); break }
                        do { try await deliver(presented) }
                        catch { message = "Mac 通知未显示，请检查系统通知设置" }
                    }
                }
            } catch {
                connected = false; policy.disconnect()
                dismissBanners()
                message = "通知同步暂时未连接，请检查 MCP 服务并更新手机工位"
            }
        }
    }
    private func icon(for packageName: String, endpoint: String, token: String) async -> String? {
        if let cached = icons[packageName], Date().timeIntervalSince(cached.1) < (cached.0 == nil ? 30 : 600) {
            return cached.0
        }
        let data = try? await rpc(endpoint, token, "station_notification_icon", ["packageName": packageName])
        let value = data.flatMap { try? JSONDecoder().decode(NotificationIcon.self, from: $0) }
        let png = value?.packageName == packageName ? value?.png : nil
        // 图标只缓存在内存；断线和暂停的在途结果不会显示。
        if icons.count >= 48 { icons.removeAll() }
        icons[packageName] = (png, Date())
        return png
    }
}
