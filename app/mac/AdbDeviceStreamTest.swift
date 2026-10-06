import Foundation
import Network

@MainActor
private final class TrackingServer {
    let listener: NWListener
    var sockets: [NWConnection] = []
    var requests: [String] = []
    var closed = 0
    var port: UInt16?

    init() throws {
        listener = try NWListener(using: .tcp, on: .any)
        listener.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                if case .ready = state { self?.port = self?.listener.port?.rawValue }
            }
        }
        listener.newConnectionHandler = { [weak self] socket in
            Task { @MainActor in
                guard let self else { return }
                self.sockets.append(socket)
                socket.start(queue: .main)
                self.read(socket, request: Data())
            }
        }
        listener.start(queue: .main)
    }

    private func read(_ socket: NWConnection, request: Data) {
        socket.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, end, error in
            Task { @MainActor in
                guard let self else { return }
                let accumulated = request + (data ?? Data())
                if request.count < 24 && accumulated.count >= 24 {
                    self.requests.append(String(decoding: accumulated, as: UTF8.self))
                }
                if end || error != nil { self.closed += 1; socket.cancel() }
                else { self.read(socket, request: accumulated) }
            }
        }
    }

    func send(_ text: String, to index: Int) {
        sockets[index].send(content: Data(text.utf8), completion: .contentProcessed { _ in })
    }

    func stop() { listener.cancel(); for socket in sockets { socket.cancel() } }
}

@main
struct AdbDeviceStreamTest {
    @MainActor
    static func wait(_ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while !condition() && Date() < deadline { try? await Task.sleep(nanoseconds: 10_000_000) }
        precondition(condition(), "timed out waiting for tracking socket")
    }

    @MainActor
    static func main() async throws {
        typealias Endpoint = AdbDeviceStream.Endpoint
        precondition(Endpoint.configured([:]) == Endpoint(host: "127.0.0.1", port: 5037))
        precondition(Endpoint.configured(["ADB_SERVER_SOCKET": "tcp:localhost:5040"]) == Endpoint(host: "localhost", port: 5040))
        precondition(Endpoint.configured(["ADB_SERVER_SOCKET": "tcp:5041"]) == Endpoint(host: "127.0.0.1", port: 5041))
        precondition(Endpoint.configured(["ADB_SERVER_SOCKET": "tcp:[::1]:5041"]) == Endpoint(host: "::1", port: 5041))
        precondition(Endpoint.configured(["ADB_SERVER_SOCKET": ""]) == nil)
        precondition(Endpoint.configured(["ADB_SERVER_SOCKET": "localfilesystem:/tmp/adb"]) == nil)
        precondition(Endpoint.configured(["ANDROID_ADB_SERVER_PORT": "0"]) == nil)
        precondition(Endpoint.configured(["ANDROID_ADB_SERVER_ADDRESS": "localhost", "ANDROID_ADB_SERVER_PORT": "5042"]) == Endpoint(host: "localhost", port: 5042))
        var frames = AdbDeviceStream.Frames()
        let first = try frames.append(Data("OK".utf8))
        let second = try frames.append(Data("AY0003ab".utf8))
        let third = try frames.append(Data("c0000".utf8))
        precondition(first.isEmpty && second.isEmpty && third == ["abc", ""])
        for malformed in ["FAIL", "OKAYzzzz", "OKAY-001"] {
            var invalid = AdbDeviceStream.Frames()
            do { _ = try invalid.append(Data(malformed.utf8)); preconditionFailure("accepted malformed frame") }
            catch { }
        }

        let server = try TrackingServer()
        defer { server.stop() }
        await wait { server.port != nil }
        let stream = AdbDeviceStream(endpoint: Endpoint(host: "127.0.0.1", port: server.port!))
        var received: [String] = []
        var disconnected = 0
        stream.onFrame = { received.append($0) }
        stream.onDisconnect = { disconnected += 1 }
        stream.start(); stream.start()
        await wait { server.requests.count == 1 }
        precondition(server.requests[0] == "0014host:track-devices-l")
        server.send("OK", to: 0)
        server.send("AY0003abc0000", to: 0)
        await wait { received == ["abc", ""] }
        server.sockets[0].cancel()
        await wait { server.requests.count == 2 }
        precondition(disconnected == 1)
        server.send("OKAY0001x", to: 1)
        await wait { received.last == "x" }
        stream.stop()
        await wait { server.closed >= 1 }
        try await Task.sleep(nanoseconds: 1_200_000_000)
        precondition(server.requests.count == 2, "stop started another watcher")
        stream.start()
        await wait { server.requests.count == 3 }
        server.send("FAIL0004nope", to: 2)
        await wait { server.requests.count == 4 }
        await wait { server.requests.count == 5 } // No handshake reply: bounded retry.
        stream.stop()
        print("AdbDeviceStreamTest ok: framing, live socket, reconnect, stop/restart, failure")
    }
}
