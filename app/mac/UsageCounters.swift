import Foundation

/// Counts process starts and connect.sh launches for this menu-bar process.
/// The snapshot is rewritten beside the gateway config so `status --safe` can
/// read it without starting another process. Test binaries do not write it.
enum UsageCounters {
    private static let lock = NSLock()
    private static var processes = ["adb": 0, "script": 0, "other": 0]
    private static var reconnects = ["link": 0]
    static var directoryOverride: URL?
    static var persistOverride: Bool?

    static func processKind(executable: URL, arguments: [String]) -> String {
        switch executable.lastPathComponent {
        case "adb":
            return "adb"
        case "zsh", "sh":
            if arguments.contains(where: { $0.hasSuffix(".sh") }) { return "script" }
            return "other"
        default:
            return "other"
        }
    }

    static func noteProcess(executable: URL, arguments: [String]) {
        update { processes[processKind(executable: executable, arguments: arguments), default: 0] += 1 }
    }

    /// Each launch of connect.sh, including a manual connect.
    static func noteReconnect(_ kind: String) {
        update { reconnects[kind, default: 0] += 1 }
    }

    static func resetForTest(directory: URL) {
        lock.lock()
        processes = ["adb": 0, "script": 0, "other": 0]
        reconnects = ["link": 0]
        directoryOverride = directory
        persistOverride = true
        lock.unlock()
    }

    private static var shouldPersist: Bool {
        if let persistOverride { return persistOverride }
        let name = URL(fileURLWithPath: CommandLine.arguments.first ?? "").lastPathComponent
        return !name.hasSuffix("Test")
    }

    private static func update(_ change: () -> Void) {
        guard shouldPersist else { return }
        lock.lock()
        change()
        let body = snapshotData()
        let destination = fileURL()
        lock.unlock()
        write(body, to: destination)
    }

    private static func fileURL() -> URL {
        if let directoryOverride {
            return directoryOverride.appendingPathComponent("usage-app.json")
        }
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return root.appendingPathComponent("Phone Station", isDirectory: true).appendingPathComponent("usage-app.json")
    }

    private static func snapshotData() -> Data {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        let body: [String: Any] = [
            "processes": processes,
            "reconnects": ["link": reconnects["link"] ?? 0],
            "updatedAt": formatter.string(from: Date())
        ]
        return (try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])) ?? Data()
    }

    private static func write(_ body: Data, to destination: URL) {
        guard !body.isEmpty else { return }
        let directory = destination.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            let temporary = directory.appendingPathComponent(".usage-app-\(UUID().uuidString).json")
            try body.write(to: temporary, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: temporary.path)
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary)
            } else {
                try FileManager.default.moveItem(at: temporary, to: destination)
            }
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        } catch {
            // A missed counter write must not break the connection check that caused it.
        }
    }
}
