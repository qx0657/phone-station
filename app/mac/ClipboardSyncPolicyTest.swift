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
        print("ClipboardSyncPolicyTest passed")
    }
}
