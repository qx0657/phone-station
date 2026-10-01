import Foundation

@MainActor
final class SetupSession: ObservableObject {
    @Published var pairCode = ""

    var onFinished: () -> Void = {}

    private let feedback: StationFeedback

    init(feedback: StationFeedback) {
        self.feedback = feedback
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
            let result = StationRunner.scriptResult("android.sh", timeout: 180)
            await MainActor.run {
                self.feedback.activity = nil
                let tail = result.output.split(separator: "\n").suffix(4).joined(separator: "\n")
                if result.succeeded {
                    self.feedback.notice = tail.isEmpty ? "已安装。" : String(tail.prefix(220))
                    self.onFinished()
                } else {
                    self.feedback.notice = result.timedOut
                        ? "安装超时。手机若停在设置页，回到「手机工位」再试一次。"
                        : StationText.reason(result.output, fallback: "安装失败。")
                }
            }
        }
    }
}
