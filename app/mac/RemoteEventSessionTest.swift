import Foundation

@main
enum RemoteEventSessionTest {
    @MainActor static func main() throws {
        var sent: [[String: String]] = [], subscriptions: [(Bool, Bool)] = [], events: [String] = []
        let session = RemoteEventSession(startTimer: false, send: { sent.append($0) })
        session.clients = { ("clipboard-client", "notification-client") }
        session.onSubscription = { subscriptions.append(($0, $1)) }
        session.onEvent = { events.append($0) }
        try session.receive(["type":"ready", "protocol":"events-v1"])
        precondition(sent.last?["clipboard"] == "clipboard-client")
        let first = sent.last!["requestId"]!
        try session.receive(["type":"subscribing", "requestId":first, "subscriptionId":"old"])
        try session.receive(["type":"subscribed", "subscriptionId":"old", "clipboard":true, "notifications":true])
        precondition(subscriptions.last! == (true,true))
        session.subscribe(("clipboard-client", ""))
        let second = sent.last!["requestId"]!
        try session.receive(["type":"subscribing", "requestId":first, "subscriptionId":"old"])
        try session.receive(["type":"subscribed", "subscriptionId":"old", "clipboard":true, "notifications":true])
        precondition(subscriptions.last! == (false,false), "late grant cannot revive a removed subscription")
        try session.receive(["type":"subscribing", "requestId":second, "subscriptionId":"new"])
        try session.receive(["type":"subscribed", "subscriptionId":"new", "clipboard":true, "notifications":true])
        precondition(subscriptions.last! == (true,false), "ack cannot expand requested topics")
        try session.receive(["type":"event", "subscriptionId":"old", "topic":"clipboard"])
        precondition(events.isEmpty, "old connection events must not refresh or receive content")
        try session.receive(["type":"event", "subscriptionId":"new", "topic":"clipboard"])
        precondition(events == ["clipboard"])
        try session.receive(["type":"heartbeat"])
        precondition(sent.last?["type"] == "heartbeat")
        try session.receive(["type":"reset"])
        precondition(subscriptions.last! == (false,false), "phone reconnection revokes confirmed subscription")
        session.stop()
        print("RemoteEventSessionTest passed")
    }
}
