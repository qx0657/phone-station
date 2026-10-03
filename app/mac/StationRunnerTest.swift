import Foundation
import Darwin

@main enum StationRunnerTest {
    static func main() throws {
        let ordinary = StationRunner.capture(URL(fileURLWithPath: "/bin/sh"), ["-c", "printf ok; exit 7"])
        precondition(ordinary.code == 7 && ordinary.output == "ok" && !ordinary.timedOut)
        let payload = Data(repeating: 65, count: 1024 * 1024)
        let copy = StationRunner.capture(URL(fileURLWithPath: "/usr/bin/wc"), ["-c"], input: payload)
        precondition(copy.succeeded && Int(copy.output) == payload.count)
        let refuses = StationRunner.capture(URL(fileURLWithPath: "/bin/sh"), ["-c", "exec 0<&-; sleep 0.1; exit 0"], timeout: 2, input: payload)
        precondition(refuses.succeeded, "early stdin close must not send SIGPIPE to the app")
        let start = Date()
        let blocked = StationRunner.capture(URL(fileURLWithPath: "/bin/sleep"), ["20"], timeout: 1, input: payload)
        precondition(blocked.timedOut && Date().timeIntervalSince(start) < 6, "stdin must not defeat timeout")

        let unrelated = Process()
        unrelated.executableURL = URL(fileURLWithPath: "/bin/sleep")
        unrelated.arguments = ["30"]
        try unrelated.run()
        defer { unrelated.terminate(); unrelated.waitUntilExit() }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let result = StationRunner.capture(URL(fileURLWithPath: "/bin/sh"),
            ["-c", "sh -c 'trap \"\" TERM; echo $$ > \"$1\"; while :; do sleep 1; done' child \"$1\" & wait", "parent", file.path], timeout: 1)
        precondition(result.timedOut)
        let child = Int32(try String(contentsOf: file, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines))!
        defer { if kill(child, 0) == 0 { kill(child, SIGKILL) } }
        for _ in 0..<100 {
            if kill(child, 0) != 0 { break }
            usleep(20_000)
        }
        precondition(kill(child, 0) != 0, "TERM-resistant descendant survived timeout")
        precondition(unrelated.isRunning, "timeout touched an unrelated process")
        print("StationRunnerTest ok")
    }
}
