import Foundation

@main
enum UsageCountersTest {
    static func main() {
        precondition(UsageCounters.processKind(executable: URL(fileURLWithPath: "/usr/bin/adb"), arguments: ["devices", "-l"]) == "adb")
        precondition(UsageCounters.processKind(executable: URL(fileURLWithPath: "/bin/zsh"), arguments: ["/tmp/connect.sh"]) == "script")
        precondition(UsageCounters.processKind(executable: URL(fileURLWithPath: "/bin/sh"), arguments: ["-c", "printf ok"]) == "other")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        UsageCounters.resetForTest(directory: directory)
        UsageCounters.noteProcess(executable: URL(fileURLWithPath: "/usr/bin/adb"), arguments: ["devices", "-l"])
        UsageCounters.noteProcess(executable: URL(fileURLWithPath: "/bin/zsh"), arguments: ["/opt/phone/scripts/connect.sh"])
        UsageCounters.noteReconnect("link")
        let url = directory.appendingPathComponent("usage-app.json")
        let data = try! Data(contentsOf: url)
        let json = try! JSONSerialization.jsonObject(with: data) as! [String: Any]
        let processes = json["processes"] as! [String: NSNumber]
        let reconnects = json["reconnects"] as! [String: NSNumber]
        precondition(processes["adb"]?.intValue == 1 && processes["script"]?.intValue == 1 && processes["other"]?.intValue == 0, "\(processes)")
        precondition(reconnects["link"]?.intValue == 1, "\(reconnects)")
        let updated = json["updatedAt"] as? String ?? ""
        precondition(updated.count == 20 && updated.hasSuffix("Z"), updated)
        let attributes = try! FileManager.default.attributesOfItem(atPath: url.path)
        precondition((attributes[.posixPermissions] as? NSNumber)?.uint16Value == 0o600, "usage file is private")
        precondition(json["clipboardLatency"] == nil, "latency stays absent until a sample")
        UsageCounters.noteClipboardLatency(ms: 310, uncertainty: 12)
        UsageCounters.noteClipboardLatency(ms: -4, uncertainty: -1)
        let again = try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let latency = again["clipboardLatency"] as! [String: NSNumber]
        precondition(latency["samples"]?.intValue == 2 && latency["lastMs"]?.intValue == 0
                     && latency["uncertaintyMs"]?.intValue == 0 && latency["maxMs"]?.intValue == 310, "\(latency)")
        let clocksMatch = ClipboardLatency.estimate(copiedAt: 1_000, phoneClock: 1_050, sentAt: 1_000,
                                                    receivedAt: 1_100, writtenAt: 1_110)
        precondition(clocksMatch?.ms == 110 && clocksMatch?.uncertainty == 50, "\(String(describing: clocksMatch))")
        let phoneAhead = ClipboardLatency.estimate(copiedAt: 6_000, phoneClock: 6_050, sentAt: 1_000,
                                                   receivedAt: 1_100, writtenAt: 1_110)
        precondition(phoneAhead?.ms == 110 && phoneAhead?.uncertainty == 50, "\(String(describing: phoneAhead))")
        let withinError = ClipboardLatency.estimate(copiedAt: 1_150, phoneClock: 1_050, sentAt: 1_000,
                                                    receivedAt: 1_100, writtenAt: 1_100)
        precondition(withinError?.ms == 0 && withinError?.uncertainty == 50, "\(String(describing: withinError))")
        precondition(ClipboardLatency.estimate(copiedAt: 1_151, phoneClock: 1_050, sentAt: 1_000,
                                               receivedAt: 1_100, writtenAt: 1_100) == nil, "beyond the round trip")
        precondition(ClipboardLatency.estimate(copiedAt: 1_000, phoneClock: 1_050, sentAt: 1_000,
                                               receivedAt: 1_100, writtenAt: 3_601_100) == nil, "stale copy")
        precondition(ClipboardLatency.estimate(copiedAt: 0, phoneClock: 1_050, sentAt: 1_000,
                                               receivedAt: 1_100, writtenAt: 1_110) == nil, "missing copy time")
        let body = #"{"shared":true,"automatic":true,"phone":null,"phoneVersion":"v","macOnline":false,"phoneCopiedAt":1000,"phoneClock":2000}"#
        let snapshot = try! JSONDecoder().decode(ClipboardSnapshot.self, from: Data(body.utf8))
        precondition(snapshot.phoneCopiedAt == 1000 && snapshot.phoneClock == 2000)
        let older = #"{"shared":true,"automatic":false,"phone":null,"phoneVersion":"v","macOnline":false}"#
        precondition(try! JSONDecoder().decode(ClipboardSnapshot.self, from: Data(older.utf8)).phoneCopiedAt == nil)
        print("usage counters ok")
    }
}
