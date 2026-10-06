import Foundation

/// The stream carries invalidations only. Existing RPCs own content, cursors and conflict resolution.
@MainActor
final class RemoteEventSession: NSObject, URLSessionTaskDelegate {
    var route: () -> (String, String)? = { nil }
    var clients: () -> (String, String) = { ("", "") }
    var onSubscription: (Bool, Bool) -> Void = { _, _ in }
    var onEvent: (String) -> Void = { _ in }
    private let sendOverride: (([String: String]) -> Void)?
    private var timer: Timer?
    private var transport: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var reader: Task<Void, Never>?
    private var writer: Task<Void, Never>?
    private var outgoing: [String] = []
    private var generation = 0
    private var currentRoute: (String, String)?
    private var requested = ("", "")
    private var requestID = ""
    private var subscriptionID = ""
    private var ready = false
    private var receivedAt = Date.distantPast
    private var retryAt = Date.distantPast
    private var backoff: TimeInterval = 1

    init(startTimer: Bool = true, send: (([String: String]) -> Void)? = nil) {
        sendOverride = send
        super.init()
        if startTimer {
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
            RunLoop.main.add(timer, forMode: .common); self.timer = timer
        }
    }
    func refresh() {
        let desired = clients()
        guard let route = route(), !desired.0.isEmpty || !desired.1.isEmpty else {
            if socket != nil { stop() }; return
        }
        if let currentRoute, currentRoute != route { stop() }
        if socket != nil, Date().timeIntervalSince(receivedAt) > 45 { failed(); return }
        if socket == nil {
            guard Date() >= retryAt else { return }
            start(route)
        }
        if ready, desired != requested { subscribe(desired) }
    }
    private func start(_ route: (String, String)) {
        guard var parts = URLComponents(string: route.0), parts.scheme == "http", parts.host == "127.0.0.1",
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil else { return }
        parts.scheme = "ws"; parts.path = "/__subscription"
        guard let url = parts.url else { return }
        var request = URLRequest(url: url); request.setValue("Bearer \(route.1)", forHTTPHeaderField: "Authorization")
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 15
        let transport = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.transport = transport
        let socket = transport.webSocketTask(with: request); socket.maximumMessageSize = 4096
        self.socket = socket; currentRoute = route; receivedAt = Date(); generation += 1
        let version = generation
        socket.resume()
        reader = Task { [weak self] in
            do {
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    guard let self, self.generation == version else { return }
                    guard case let .string(text) = message, let data = text.data(using: .utf8),
                          let frame = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw EventStreamError.invalid }
                    try self.receive(frame)
                }
            } catch { if let self, self.generation == version { self.failed() } }
        }
    }
    func receive(_ frame: [String: Any]) throws {
        guard let type = frame["type"] as? String else { throw EventStreamError.invalid }
        receivedAt = Date()
        switch type {
        case "ready":
            guard !ready, frame["protocol"] as? String == "events-v1" else { throw EventStreamError.invalid }
            ready = true; backoff = 1; subscribe(clients())
        case "heartbeat": send(["type": "heartbeat"])
        case "subscribing":
            guard frame["requestId"] as? String == requestID else { return }
            subscriptionID = frame["subscriptionId"] as? String ?? ""
        case "subscribed":
            guard !subscriptionID.isEmpty, frame["subscriptionId"] as? String == subscriptionID else { return }
            onSubscription(!requested.0.isEmpty && frame["clipboard"] as? Bool == true,
                           !requested.1.isEmpty && frame["notifications"] as? Bool == true)
        case "event":
            guard !subscriptionID.isEmpty, frame["subscriptionId"] as? String == subscriptionID,
                  let topic = frame["topic"] as? String, ["clipboard", "notifications"].contains(topic) else { return }
            if (topic == "clipboard" && !requested.0.isEmpty) || (topic == "notifications" && !requested.1.isEmpty) { onEvent(topic) }
        case "reset": onSubscription(false, false); onEvent("clipboard"); onEvent("notifications")
        default: throw EventStreamError.invalid
        }
    }
    func subscribe(_ desired: (String, String)) {
        requested = desired; requestID = UUID().uuidString; subscriptionID = ""
        onSubscription(false, false)
        send(["type": "subscribe", "requestId": requestID, "clipboard": desired.0, "notifications": desired.1])
    }
    private func send(_ frame: [String: String]) {
        if let sendOverride { sendOverride(frame); return }
        guard let socket, let data = try? JSONSerialization.data(withJSONObject: frame), let text = String(data: data, encoding: .utf8) else { return }
        guard outgoing.count < 16 else { failed(); return }
        outgoing.append(text)
        guard writer == nil else { return }
        let version = generation
        // Subscription changes and heartbeats share a single writer, preserving cancellation order.
        writer = Task {
            defer { if generation == version { writer = nil } }
            do {
                while generation == version, !Task.isCancelled, !outgoing.isEmpty {
                    let next = outgoing.removeFirst()
                    try await socket.send(.string(next))
                }
            } catch { if generation == version { failed() } }
        }
    }
    private func failed() {
        stop(); retryAt = Date().addingTimeInterval(backoff); backoff = min(30, backoff * 2)
    }
    func stop() {
        generation += 1; reader?.cancel(); reader = nil
        writer?.cancel(); writer = nil; outgoing.removeAll()
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        transport?.invalidateAndCancel(); transport = nil
        ready = false; currentRoute = nil; requested = ("", ""); subscriptionID = ""; requestID = ""
        onSubscription(false, false)
    }
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
private enum EventStreamError: Error { case invalid }
