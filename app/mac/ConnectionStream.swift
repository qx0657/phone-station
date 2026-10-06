import Foundation

/// Gateway NDJSON snapshots can span reads.
struct ConnectionFrames {
    private var buffer = Data()

    mutating func append(_ bytes: Data) -> [String] {
        buffer.append(bytes)
        var frames: [String] = []
        while true {
            guard let end = buffer.firstIndex(of: 10) else {
                if buffer.count > 65536 { buffer.removeAll() }
                return frames
            }
            frames.append(String(decoding: buffer[..<end], as: UTF8.self))
            buffer.removeSubrange(...end)
        }
    }
}

struct AdbDevice: Equatable {
    let serial: String
    let state: String

    static func parse(_ listing: String) -> [AdbDevice] {
        let regex = try! NSRegularExpression(pattern: #"^(.+?)\s+(device|offline|unauthorized|authorizing)(?:\s+.*)?$"#)
        return listing.split(separator: "\n").compactMap { raw in
            let line = String(raw)
            guard let match = regex.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
                  let serial = Range(match.range(at: 1), in: line),
                  let state = Range(match.range(at: 2), in: line) else { return nil }
            return AdbDevice(serial: String(line[serial]), state: String(line[state]))
        }
    }
}

@MainActor
final class ConnectionStream {
    private var task: Task<Void, Never>?
    private var process: Process?
    private var lifetime: Pipe?
    private let executable: URL
    private let arguments: [String]
    var onFrame: (String) -> Void = { _ in }
    var onDisconnect: () -> Void = {}

    init(executable: URL, arguments: [String]) {
        self.executable = executable; self.arguments = arguments
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let child = Process()
                let pipe = Pipe()
                let lifetime = Pipe()
                child.executableURL = self.executable
                child.arguments = self.arguments
                child.environment = StationRunner.environment
                child.environment?["PHONE_STATION_WATCH_STDIN"] = "1"
                child.standardInput = lifetime
                child.standardOutput = pipe
                child.standardError = FileHandle.nullDevice
                self.process = child
                self.lifetime = lifetime
                do {
                    try child.run()
                    try? lifetime.fileHandleForReading.close()
                    let owner = self
                    await Task.detached(priority: .utility) {
                        var decoder = ConnectionFrames()
                        while true {
                            let bytes = pipe.fileHandleForReading.availableData
                            if bytes.isEmpty { break }
                            let frames = decoder.append(bytes)
                            await owner.receive(frames, from: child)
                        }
                        try? pipe.fileHandleForReading.close()
                    }.value
                } catch { }
                try? lifetime.fileHandleForReading.close()
                try? lifetime.fileHandleForWriting.close()
                if self.process === child {
                    self.process = nil
                    self.lifetime = nil
                    self.onDisconnect()
                }
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func receive(_ frames: [String], from child: Process) {
        guard process === child else { return }
        for frame in frames { onFrame(frame) }
    }

    func stop() {
        task?.cancel(); task = nil
        try? lifetime?.fileHandleForWriting.close(); lifetime = nil
        if let process, process.isRunning { process.terminate() }
        process = nil
    }
}
