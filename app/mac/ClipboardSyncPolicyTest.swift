import Foundation

@main
enum ClipboardSyncPolicyTest {
    static func main() {
        var policy = ClipboardSyncPolicy()
        let clip = ClipboardClip(kind: "text", text: "from-phone")
        func snapshot(_ version: String, applied: Bool = false) -> ClipboardSnapshot {
            ClipboardSnapshot(shared: true, automatic: true, phone: clip,
                              phoneVersion: version, macOnline: true, appliedMac: applied, error: nil)
        }
        let local = ClipboardClip(kind: "text", text: "on-mac")
        precondition(policy.receive(snapshot("1"), sentCount: 1, currentCount: 1, current: local, baseline: true) == nil)
        precondition(policy.receive(snapshot("2"), sentCount: 1, currentCount: 1, current: local, baseline: false) == "from-phone")
        precondition(policy.receive(snapshot("3"), sentCount: 1, currentCount: 2, current: local, baseline: false) == nil,
                     "a response cannot overwrite a copy made in flight")
        precondition(policy.receive(snapshot("4", applied: true), sentCount: 2, currentCount: 2, current: local, baseline: false) == nil,
                     "Mac-originated text must not bounce back")
        precondition(policy.receive(snapshot("5"), sentCount: 2, currentCount: 2,
                                    current: ClipboardClip(kind: "sensitive", text: nil), baseline: false) == nil)
        policy.disconnect()
        precondition(policy.receive(snapshot("6"), sentCount: 2, currentCount: 2, current: local, baseline: false) == nil)
        var healthy = snapshot("stable")
        healthy.recoverySupported = true
        healthy.sessionId = "service-a"
        var recovery = ClipboardRecovery()
        recovery.sent(count: 1)
        recovery.succeeded(healthy, count: 1, now: 100)
        recovery.sent(count: 1)
        recovery.interrupted()
        let newer = ClipboardLocalState(count: 4, clip: local)
        precondition(recovery.decision(healthy, local: newer, now: 112) == .sendNewCopy,
                     "several copies during handover must preserve the latest event")
        precondition(recovery.decision(healthy, local: ClipboardLocalState(count: 1, clip: local), now: 112) == .baseline,
                     "an unknown old request must not be replayed")
        recovery.sent(count: 4)
        precondition(recovery.decision(healthy, local: newer, now: 113) == .baseline,
                     "a failed resumed write must not be replayed either")
        precondition(recovery.decision(healthy, local: ClipboardLocalState(count: 5, clip: local), now: 113) == .sendNewCopy)
        var changed = healthy
        changed.phoneVersion = "phone-newer"
        precondition(recovery.decision(changed, local: newer, now: 113) == .baseline,
                     "a concurrent phone copy must prevent resuming Mac text")
        precondition(recovery.decision(changed, local: ClipboardLocalState(count: 1, clip: local), now: 113) == .receivePhoneCopy,
                     "a phone copy during handover can reach an unchanged Mac")
        precondition(recovery.decision(healthy, local: newer, now: 131) == .baseline, "long outages rebuild a baseline")
        changed = healthy; changed.sessionId = "service-b"
        precondition(recovery.decision(changed, local: newer, now: 113) == .baseline, "service restart is a new baseline")
        changed = healthy; changed.recoverySupported = nil
        precondition(recovery.decision(changed, local: newer, now: 113) == .baseline, "older phones must recover conservatively")
        changed = healthy; changed.phone = ClipboardClip(kind: "locked", text: nil)
        precondition(recovery.decision(changed, local: newer, now: 113) == .baseline)
        precondition(recovery.decision(healthy, local: ClipboardLocalState(count: 5, clip: ClipboardClip(kind: "sensitive", text: nil)), now: 113) == .baseline)
        var restartedPolicy = ClipboardSyncPolicy()
        _ = restartedPolicy.receive(healthy, sentCount: 1, currentCount: 1, current: local, baseline: true)
        changed = healthy; changed.phoneVersion = "restart"; changed.sessionId = "service-b"
        precondition(restartedPolicy.receive(changed, sentCount: 1, currentCount: 1, current: local, baseline: false) == nil,
                     "a restarted service must not push old phone text to Mac")
        print("ClipboardSyncPolicyTest passed")
    }
}
