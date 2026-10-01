import Foundation

/// 菜单栏在没有在线设备时要不要再跑 `connect.sh`。
/// 第一次马上找。没找到之后隔 5 秒、15 秒、30 秒，再往后每 60 秒。
/// 已经在找时不会再开一次。`holdOffline` 为真时不自动找，手动连接会清掉。
struct ReconnectDecision {
    enum Reading {
        case online
        case reconnectable
        case idle
    }

    private(set) var holdOffline = false
    private(set) var isInFlight = false
    private var failures = 0
    private var nextAttempt = Date.distantPast

    static func retryDelay(failures: Int) -> TimeInterval {
        switch failures {
        case ...1: return 5
        case 2: return 15
        case 3: return 30
        default: return 60
        }
    }

    mutating func hold() {
        holdOffline = true
    }

    mutating func release() {
        holdOffline = false
    }

    /// 该自动开一次时返回 true，并把这一次标成进行中。
    mutating func consider(_ reading: Reading, now: Date) -> Bool {
        if reading == .online {
            failures = 0
            nextAttempt = .distantPast
            return false
        }
        guard reading == .reconnectable, !holdOffline, !isInFlight, now >= nextAttempt else {
            return false
        }
        isInFlight = true
        return true
    }

    /// 清掉暂停。已经在连接时返回 false，调用方沿用那一次。
    mutating func beginManual() -> Bool {
        holdOffline = false
        if isInFlight { return false }
        isInFlight = true
        return true
    }

    mutating func finish(success: Bool, cancelled: Bool, now: Date) {
        isInFlight = false
        if cancelled { return }
        if success {
            failures = 0
            nextAttempt = .distantPast
        } else {
            failures += 1
            nextAttempt = now.addingTimeInterval(Self.retryDelay(failures: failures))
        }
    }
}
