import Foundation

@MainActor
final class SetupSession: ObservableObject {
    @Published var pairCode = ""

    var onFinished: () -> Void = {}

    private let feedback: StationFeedback

    init(feedback: StationFeedback) {
        self.feedback = feedback
    }

    func openPermissions(serial: String?) {
        guard let serial, let adb = StationRunner.executable("adb") else {
            feedback.notice = "请在手机首页打开「权限」，按提示启动或授权 Shizuku。"
            return
        }
        guard feedback.activity == nil else { return }
        feedback.activity = "正在打开手机权限页…"
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.capture(adb, ["-s", serial, "shell", "am", "start", "-n",
                "dev.phonestation.adbkeep/.MainActivity", "--es", "dev.phonestation.adbkeep.PAGE", "permissions"], timeout: 10)
            await MainActor.run {
                self.feedback.activity = nil
                self.feedback.notice = result.succeeded ? "已打开手机权限页，请在手机上按提示处理。"
                    : "手机权限页未能打开，请从手机首页进入「权限」。"
            }
        }
    }

    func pair() {
        guard feedback.activity == nil else { return }
        let code = pairCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard code.range(of: #"^\d{6}$"#, options: .regularExpression) != nil else {
            feedback.notice = "配对码是 6 位数字。"
            return
        }
        feedback.activity = "正在配对…"
        feedback.notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("pair-code.sh", [code], timeout: 40)
            await MainActor.run {
                self.feedback.activity = nil
                if result.succeeded {
                    self.pairCode = ""
                    self.feedback.notice = "已配对，并尝试连接。"
                    self.onFinished()
                } else {
                    self.feedback.notice = result.timedOut
                        ? "配对超时。回到手机上重新打开配对码页面。"
                        : StationText.reason(result.output, fallback: "配对失败。配对码每次打开页面都会变。")
                }
            }
        }
    }

    func install() {
        guard feedback.activity == nil else { return }
        feedback.activity = "正在安装手机上的「手机工位」…"
        feedback.notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("android.sh", timeout: 600)
            await MainActor.run {
                self.feedback.activity = nil
                let tail = result.output.split(separator: "\n").suffix(4).joined(separator: "\n")
                let remote = result.output.split(separator: "\n").reversed().compactMap { line -> [String: Any]? in
                    guard let data = String(line).data(using: .utf8),
                          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                          value["jobId"] is String else { return nil }
                    return value
                }.first
                if result.succeeded && remote?["completed"] as? Bool == true {
                    self.feedback.notice = "已远程安装，应用已启动，远程连接已恢复。"
                    self.onFinished()
                } else if remote?["verified"] as? Bool == true {
                    let failed = remote?["state"] as? String == "installed_restart_failed"
                    self.feedback.notice = failed
                        ? "安装已完成，但应用启动失败。先查询原安装任务，不要重复安装。"
                        : "安装已完成，应用启动尚未确认。先查询原安装任务，不要重复安装。"
                    self.onFinished()
                } else if result.succeeded {
                    self.feedback.notice = tail.isEmpty ? "已安装。" : String(tail.prefix(220))
                    self.onFinished()
                } else {
                    self.feedback.notice = result.timedOut
                        ? "安装结果未确认。先查询原安装任务，避免重复安装。"
                        : StationText.reason(result.output, fallback: "安装失败。")
                }
            }
        }
    }
}
