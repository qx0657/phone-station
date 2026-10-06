import AppKit
import UserNotifications

@MainActor
final class NotificationDelivery: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelivery()
    private var center: UNUserNotificationCenter? {
        // 电脑上的 CLI 测试没有 App bundle，不能初始化系统通知中心。
        guard Bundle.main.bundleURL.pathExtension == "app" else { return nil }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        return center
    }
    func permission() async -> NotificationPermission {
        guard let center else { return .unavailable }
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .authorized, .provisional, .ephemeral: return .authorized
        @unknown default: return .denied
        }
    }
    func authorize() async throws -> NotificationPermission {
        guard let center else { return .unavailable }
        _ = try await center.requestAuthorization(options: [.alert, .sound])
        return await permission()
    }
    func post(_ event: PhoneNotification) async throws {
        guard let center else { return }
        let content = UNMutableNotificationContent()
        content.title = event.title.isEmpty ? event.app : event.title
        content.subtitle = StationL10n.format("{0} · 手机", event.app)
        content.body = event.text
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized else { return }
        let custom = UserDefaults.standard.object(forKey: "phoneNotificationBanners") as? Bool ?? true
        if custom {
            // 系统只留一条安静的记录；桌面横幅由自己的窗口显示。
            content.interruptionLevel = .passive
            content.userInfo = ["phoneCustomBanner": true]
        } else { content.sound = .default }
        // 同一条手机通知的更新替换同一个 Mac 条目。
        try await center.add(UNNotificationRequest(identifier: event.deliveryID, content: content, trigger: nil))
        guard UserDefaults.standard.object(forKey: "receivePhoneNotifications") as? Bool != false else { return }
        var banner = event
        if settings.showPreviewsSetting == .never {
            banner.title = StationL10n.text("收到一条新通知"); banner.text = ""
        }
        if custom, UserDefaults.standard.object(forKey: "phoneNotificationBanners") as? Bool != false,
           NotificationBannerCenter.shared.show(banner), settings.soundSetting == .enabled {
            NSSound(named: "Glass")?.play()
        }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
        willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler(notification.request.content.userInfo["phoneCustomBanner"] as? Bool == true
            ? [.list] : [.banner, .list, .sound])
    }
}
