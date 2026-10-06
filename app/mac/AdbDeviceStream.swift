import Foundation
import Network

/// The app owns the adb smart socket itself, so even SIGKILL cannot orphan a watcher.
@MainActor
final class AdbDeviceStream {
    struct Endpoint: Equatable {
        let host: String
        let port: UInt16

        static func configured(_ environment: [String: String]) -> Endpoint? {
            if let socket = environment["ADB_SERVER_SOCKET"] {
                guard socket.hasPrefix("tcp:") else { return nil }
                let address = String(socket.dropFirst(4))
                let parts = address.split(separator: ":", omittingEmptySubsequences: false)
                guard let port = UInt16(parts.last ?? ""), port > 0 else { return nil }
                var host = parts.count == 1 ? "127.0.0.1" : parts.dropLast().joined(separator: ":")
                if host.hasPrefix("["), host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
                return Endpoint(host: host.isEmpty ? "127.0.0.1" : host, port: port)
            }
            guard let port = UInt16(environment["ANDROID_ADB_SERVER_PORT"] ?? "5037"), port > 0 else { return nil }
            return Endpoint(host: environment["ANDROID_ADB_SERVER_ADDRESS"] ?? "127.0.0.1", port: port)
        }
    }

    struct Frames {
        private var buffer = Data()
        private(set) var accepted = false
        enum Invalid: Error { case response }

        mutating func append(_ bytes: Data) throws -> [String] {
            buffer.append(bytes)
            if !accepted {
                guard buffer.count >= 4 else { return [] }
                guard buffer.prefix(4) == Data("OKAY".utf8) else { throw Invalid.response }
                buffer.removeFirst(4)
                accepted = true
            }
            var frames: [String] = []
            while buffer.count >= 4 {
                guard let header = String(data: buffer.prefix(4), encoding: .ascii),
                      header.allSatisfy({ $0.isHexDigit }), let count = Int(header, radix: 16) else { throw Invalid.response }
                guard buffer.count >= count + 4 else { break }
                frames.append(String(decoding: buffer.dropFirst(4).prefix(count), as: UTF8.self))
                buffer.removeFirst(count + 4)
            }
            return frames
        }
    }

    var onFrame: (String) -> Void = { _ in }
    var onDisconnect: () -> Void = {}
    private let endpoint: Endpoint
    private var connection: NWConnection?
    private var retry: Task<Void, Never>?
    private var handshake: Task<Void, Never>?
    private var decoder = Frames()
    private var running = false

    init(endpoint: Endpoint) { self.endpoint = endpoint }

    func start() {
        guard !running else { return }
        running = true
        connect()
    }

    func stop() {
        running = false
        retry?.cancel(); retry = nil
        handshake?.cancel(); handshake = nil
        let old = connection
        connection = nil
        old?.cancel()
    }

    private func connect() {
        guard running else { return }
        decoder = Frames()
        let socket = NWConnection(host: NWEndpoint.Host(endpoint.host), port: NWEndpoint.Port(rawValue: endpoint.port)!, using: .tcp)
        connection = socket
        socket.stateUpdateHandler = { [weak self, weak socket] state in
            Task { @MainActor in
                guard let self, let socket, self.connection === socket else { return }
                switch state {
                case .ready:
                    let service = "host:track-devices-l"
                    socket.send(content: Data((String(format: "%04x", service.utf8.count) + service).utf8), completion: .contentProcessed { [weak self, weak socket] error in
                        if error != nil { Task { @MainActor [weak self, weak socket] in
                            if let socket { self?.disconnected(socket) }
                        } }
                    })
                    self.receive(socket)
                case .failed, .waiting:
                    self.disconnected(socket)
                default: break
                }
            }
        }
        handshake = Task { [weak self, weak socket] in
            do { try await Task.sleep(nanoseconds: 3_000_000_000) } catch { return }
            if let socket { self?.disconnected(socket) }
        }
        socket.start(queue: .main)
    }

    private func receive(_ socket: NWConnection) {
        socket.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self, weak socket] bytes, _, complete, error in
            Task { @MainActor in
                guard let self, let socket, self.connection === socket else { return }
                do {
                    let frames = try self.decoder.append(bytes ?? Data())
                    if self.decoder.accepted { self.handshake?.cancel(); self.handshake = nil }
                    for frame in frames { self.onFrame(frame) }
                } catch { self.disconnected(socket); return }
                guard self.connection === socket else { return }
                if complete || error != nil { self.disconnected(socket) }
                else { self.receive(socket) }
            }
        }
    }

    private func disconnected(_ socket: NWConnection) {
        guard connection === socket else { return }
        connection = nil
        handshake?.cancel(); handshake = nil
        socket.cancel()
        onDisconnect()
        guard running else { return }
        retry = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            self?.connect()
        }
    }
}
