import Foundation
import CryptoKit

struct PhoneNotification: Decodable, Equatable {
    var id: String
    var key: String
    var packageName: String
    var app: String
    var title: String
    var text: String
    var iconPNG: String? = nil
    var sourceID: String { packageName + "\u{0}" + key }
    var deliveryID: String {
        "phone." + SHA256.hash(data: Data(sourceID.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

struct NotificationIcon: Decodable {
    var available: Bool
    var packageName: String?
    var app: String?
    var mimeType: String?
    var base64: String?
    var png: String? {
        guard available, mimeType == "image/png", let base64, base64.utf8.count <= 87_384,
              let data = Data(base64Encoded: base64), data.count <= 65_536,
              data.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) else { return nil }
        return base64
    }
}

/// 横幅只保留正在显示的三条；更新同一条通知不会堆叠。
struct NotificationBannerQueue {
    struct Entry {
        var event: PhoneNotification
        var expires: Date
        var hovered = false
    }
    private(set) var entries: [Entry] = []
    mutating func show(_ event: PhoneNotification, now: Date) {
        expire(now: now)
        let hovered = entries.first(where: { $0.event.sourceID == event.sourceID })?.hovered ?? false
        entries.removeAll { $0.event.sourceID == event.sourceID }
        entries.insert(Entry(event: event, expires: now.addingTimeInterval(6), hovered: hovered), at: 0)
        if entries.count > 3 { entries.removeLast(entries.count - 3) }
    }
    mutating func hover(_ id: String, on: Bool, now: Date) {
        guard let index = entries.firstIndex(where: { $0.event.sourceID == id }) else { return }
        entries[index].hovered = on
        if !on { entries[index].expires = now.addingTimeInterval(6) }
    }
    mutating func expire(now: Date) { entries.removeAll { !$0.hovered && $0.expires <= now } }
    mutating func dismiss(_ id: String) { entries.removeAll { $0.event.sourceID == id } }
    mutating func clear() { entries.removeAll() }
}

struct NotificationSnapshot: Decodable {
    var enabled: Bool
    var accessGranted: Bool
    var listenerConnected: Bool
    var selectedCount: Int
    var macOnline: Bool
    var cursor: String
    var events: [PhoneNotification]
    var baseline: Bool?
}

enum NotificationPermission: String {
    case unknown, notDetermined, denied, authorized, unavailable
    var label: String {
        switch self {
        case .unknown: return "正在检查"
        case .notDetermined: return "待授权"
        case .denied: return "未允许"
        case .authorized: return "已允许"
        case .unavailable: return "请从应用程序打开手机工位"
        }
    }
}

/// 未知结果不重放；每次重连先建立基线，重复事件只展示一次。
struct NotificationReceivePolicy {
    private(set) var cursor: String?
    private var seen: [String] = []
    mutating func disconnect() { cursor = nil; seen.removeAll() }
    mutating func receive(_ snapshot: NotificationSnapshot) -> [PhoneNotification] {
        let first = cursor == nil
        cursor = snapshot.cursor
        guard !first, snapshot.baseline != true, snapshot.enabled,
              snapshot.accessGranted, snapshot.listenerConnected else { return [] }
        var result: [PhoneNotification] = []
        for event in snapshot.events where !seen.contains(event.id) {
            seen.append(event.id)
            result.append(event)
        }
        if seen.count > 256 { seen.removeFirst(seen.count - 256) }
        return result
    }
}
