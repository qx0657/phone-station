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
        print("usage counters ok")
    }
}
