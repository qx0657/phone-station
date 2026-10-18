import Foundation

enum NativeScreen {
    static func port() -> UInt16? {
        guard let root = Bundle.main.resourceURL else { return nil }
        let binary = root.appendingPathComponent("phone-station/lib/station")
        guard FileManager.default.isExecutableFile(atPath: binary.path) else { return nil }
        let result = StationRunner.capture(binary, ["screen"], timeout: 3)
        guard result.code == 0 else { return nil }
        guard let line = result.output.split(whereSeparator: \.isNewline).first else { return nil }
        let parts = line.split(separator: ":")
        guard parts.count == 2, parts[0] == "127.0.0.1", let port = UInt16(parts[1]), port > 0 else { return nil }
        return port
    }
}
