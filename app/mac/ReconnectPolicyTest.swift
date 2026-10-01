import Foundation

@main
enum ReconnectPolicyTest {
    static func main() {
        checkDelays()
        checkBackoff()
        checkOnlineResets()
        checkHold()
        checkInFlight()
        checkIdleDoesNotSkipTheWait()
        checkCancelledFinishDoesNotBackoff()
        print("reconnect policy ok")
    }

    private static func checkDelays() {
        precondition(ReconnectDecision.retryDelay(failures: 1) == 5)
        precondition(ReconnectDecision.retryDelay(failures: 2) == 15)
        precondition(ReconnectDecision.retryDelay(failures: 3) == 30)
        precondition(ReconnectDecision.retryDelay(failures: 4) == 60)
        precondition(ReconnectDecision.retryDelay(failures: 8) == 60)
    }

    private static func checkBackoff() {
        var decision = ReconnectDecision()
        var now = Date(timeIntervalSince1970: 0)
        for delay in [TimeInterval(5), 15, 30, 60, 60] {
            precondition(decision.consider(.reconnectable, now: now))
            decision.finish(success: false, cancelled: false, now: now)
            precondition(!decision.consider(.reconnectable, now: now.addingTimeInterval(delay - 0.1)))
            now = now.addingTimeInterval(delay)
        }
        precondition(decision.consider(.reconnectable, now: now))
    }

    private static func checkOnlineResets() {
        var decision = ReconnectDecision()
        let now = Date(timeIntervalSince1970: 0)
        precondition(decision.consider(.reconnectable, now: now))
        decision.finish(success: false, cancelled: false, now: now)
        precondition(!decision.consider(.online, now: now.addingTimeInterval(1)))
        precondition(decision.consider(.reconnectable, now: now.addingTimeInterval(1)))
    }

    private static func checkHold() {
        var decision = ReconnectDecision()
        let now = Date(timeIntervalSince1970: 0)
        decision.hold()
        precondition(!decision.consider(.reconnectable, now: now))
        precondition(decision.beginManual())
        precondition(!decision.holdOffline)
        decision.finish(success: false, cancelled: false, now: now)
        decision.hold()
        precondition(!decision.consider(.reconnectable, now: now.addingTimeInterval(5)))
        decision.release()
        precondition(decision.consider(.reconnectable, now: now.addingTimeInterval(5)))
    }

    private static func checkInFlight() {
        var decision = ReconnectDecision()
        let now = Date(timeIntervalSince1970: 0)
        precondition(decision.consider(.reconnectable, now: now))
        precondition(!decision.consider(.reconnectable, now: now.addingTimeInterval(10_000)))
        precondition(!decision.beginManual())
        precondition(!decision.holdOffline)
        decision.finish(success: true, cancelled: false, now: now)
        precondition(!decision.isInFlight)
    }

    private static func checkIdleDoesNotSkipTheWait() {
        var decision = ReconnectDecision()
        let now = Date(timeIntervalSince1970: 0)
        precondition(decision.consider(.reconnectable, now: now))
        decision.finish(success: false, cancelled: false, now: now)
        precondition(!decision.consider(.idle, now: now.addingTimeInterval(100)))
        precondition(decision.consider(.reconnectable, now: now.addingTimeInterval(5)))
    }

    private static func checkCancelledFinishDoesNotBackoff() {
        var decision = ReconnectDecision()
        let now = Date(timeIntervalSince1970: 0)
        precondition(decision.consider(.reconnectable, now: now))
        decision.hold()
        decision.finish(success: true, cancelled: true, now: now)
        precondition(!decision.isInFlight)
        precondition(!decision.consider(.reconnectable, now: now))
        decision.release()
        precondition(decision.consider(.reconnectable, now: now))
    }
}
