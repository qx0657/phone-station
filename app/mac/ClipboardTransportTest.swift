import Foundation

/// Real HTTP queue/timeout regression against a local fixture, never a phone.
@main enum ClipboardTransportTest {
    @MainActor static func main() async throws {
        let mock = Process(), output = Pipe()
        mock.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        mock.arguments = ["python3", "-u", "-c", """
import json, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    exchanges = 0
    redirected = 0
    def log_message(self, *args): pass
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        name = request['params']['name']
        if self.path == '/redirected': Handler.redirected += 1
        if name == 'station_test_redirect' and self.path == '/mcp':
            self.send_response(307)
            self.send_header('Location', '/redirected')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if name == 'station_test_redirect_count' or self.path == '/redirected':
            value = {'redirected': Handler.redirected}
        elif name == 'station_device_status':
            time.sleep(9)
            value = {'health': {'issues': []}}
        else:
            if name == 'station_clipboard_exchange': Handler.exchanges += 1
            newer = Handler.exchanges >= 2
            value = {'shared': True, 'automatic': True, 'macOnline': True,
                     'phoneVersion': 'p2' if newer else 'p1', 'sessionId': 'fixture',
                     'phone': {'kind': 'text', 'text': 'fixture-new' if newer else 'fixture-old'}}
        reply = json.dumps({'jsonrpc': '2.0', 'id': request['id'],
                            'result': {'content': [{'type': 'text', 'text': json.dumps(value)}]}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
print(server.server_port, flush=True)
server.serve_forever()
"""]
        mock.standardOutput = output
        try mock.run()
        defer { mock.terminate(); mock.waitUntilExit() }
        var port = Data()
        while let byte = try output.fileHandleForReading.read(upToCount: 1), !byte.isEmpty, byte != Data([10]) { port.append(byte) }
        let endpoint = "http://127.0.0.1:\(String(data: port, encoding: .utf8)!)/mcp"
        do {
            _ = try await ClipboardRPC.call(endpoint: endpoint, token: "fixture", name: "station_test_redirect", arguments: [:])
            preconditionFailure("HTTP redirect accepted")
        } catch {}
        let count = try await ClipboardRPC.call(endpoint: endpoint, token: "fixture", name: "station_test_redirect_count", arguments: [:])
        let redirectInfo = try JSONSerialization.jsonObject(with: count) as! [String: Int]
        precondition(redirectInfo["redirected"] == 0, "redirect must not leak the bearer or replay a mutation")
        for bad in [endpoint + "?token=fixture", endpoint + "#fragment", endpoint.replacingOccurrences(of: "http://", with: "http://user@"),
                    endpoint.replacingOccurrences(of: "http://", with: "https://")] {
            do {
                _ = try await ClipboardRPC.call(endpoint: bad, token: "fixture", name: "station_test_redirect_count", arguments: [:])
                preconditionFailure("invalid MCP endpoint accepted")
            } catch {}
        }
        var local = ClipboardLocalState(count: 1, clip: .empty)
        var writes: [String] = []
        let clipboard = ClipboardSession(startTimer: false, readLocal: { local }, writeLocal: {
            writes.append($0); local.count += 1; local.clip = ClipboardClip(kind: "text", text: $0)
        })
        clipboard.route = { (endpoint, "fixture") }
        clipboard.refresh()
        await waitFor { clipboard.connected }
        var dispatched = false
        let diagnostic = Task {
            try await ClipboardRPC.call(endpoint: endpoint, token: "fixture", name: "station_device_status",
                arguments: [:], timeout: 12, onStart: { await MainActor.run { dispatched = true } })
        }
        await waitFor { dispatched }
        clipboard.refresh()
        try await Task.sleep(nanoseconds: 8_200_000_000)
        clipboard.refresh()
        precondition(clipboard.connected && !clipboard.checking && clipboard.summary == "自动双向同步",
                     "eight seconds in the App queue must not consume HTTP timeout or disconnect clipboard")
        _ = try await diagnostic.value
        await waitFor { writes == ["fixture-new"] }
        precondition(clipboard.connected && clipboard.lastSyncDirection == "手机 → Mac",
                     "queued clipboard exchange must start after diagnostics and still deliver the new copy")
        print("ClipboardTransportTest passed: 9-second queue wait, no false disconnect or HTTP timeout")
    }
    @MainActor static func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<5000 {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        preconditionFailure("local transport fixture did not finish")
    }
}
