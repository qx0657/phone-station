import Foundation
import AppKit

@MainActor
final class SetupSession: ObservableObject {
    @Published var pairCode = ""
    @Published private(set) var installJobID: String?
    private let installStore: InstallTaskStore

    var onFinished: () -> Void = {}
    var installBlocker: () -> RemotePermissionBlocker? = { nil }
    var queryInstallBlocker: () -> RemotePermissionBlocker? = { nil }

    private let feedback: StationFeedback

    init(feedback: StationFeedback, defaults: UserDefaults = .standard) {
        self.feedback = feedback
        installStore = InstallTaskStore(defaults: defaults)
        installJobID = installStore.jobID
    }

    private func remember(_ id: String?) {
        installStore.jobID = id
        installJobID = installStore.jobID
    }

    func copyInstallJobID() {
        guard let installJobID else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(installJobID, forType: .string)
    }

    func queryInstall() {
        guard feedback.activity == nil, let id = installJobID else { return }
        if let blocker = queryInstallBlocker() {
            feedback.notice = blocker.detail + " 原任务编号：\(id)。授权后可查询原任务。"
            return
        }
        feedback.activity = "正在查询原安装任务…"
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("install-apk.sh", ["--status", id], timeout: 90)
            await MainActor.run {
                self.feedback.activity = nil
                if let receipt = InstallTaskReceipt.parse(result.output) {
                    self.remember(receipt.jobID)
                    self.feedback.notice = receipt.notice + " 任务：" + receipt.jobID
                    if receipt.completed { self.onFinished() }
                } else {
                    self.feedback.notice = "原任务暂时无法查询。编号：\(id)。请保留编号，避免重复安装。"
                }
            }
        }
    }

    func openPermissions(serial: String?) {
        guard let serial, let adb = StationRunner.executable("adb") else {
            feedback.notice = "请在手机「设置」打开「权限与检查」，按提示启动或授权 Shizuku。"
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
                    : "手机权限页未能打开，请从手机「设置」进入「权限与检查」。"
            }
        }
    }

    func openFeatures(serial: String?) {
        guard let serial, let adb = StationRunner.executable("adb"), feedback.activity == nil else {
            feedback.notice = "请在手机「设置」打开「功能管理」。"; return
        }
        feedback.activity = "正在打开手机功能管理…"
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.capture(adb, ["-s", serial, "shell", "am", "start", "-n",
                "dev.phonestation.adbkeep/.MainActivity", "--es", "dev.phonestation.adbkeep.PAGE", "features"], timeout: 10)
            await MainActor.run {
                self.feedback.activity = nil
                self.feedback.notice = result.succeeded ? "已打开手机功能管理，请在手机上选择。" : "手机页面未能打开，请从手机「设置 → 功能管理」进入。"
            }
        }
    }

    func openRemotePermissions(serial: String?, scope: String?, modernPage: Bool) {
        guard let serial, let adb = StationRunner.executable("adb") else {
            feedback.notice = "请在手机「权限与检查 → 远程访问范围」中按需授权。"
            return
        }
        guard feedback.activity == nil else { return }
        feedback.activity = "正在打开手机远程访问范围页…"
        Task.detached(priority: .userInitiated) {
            let args = modernPage ? ["--es", "dev.phonestation.adbkeep.PAGE", "remotePermissions", "--es", "dev.phonestation.adbkeep.REMOTE_SCOPE", scope ?? ""]
                : ["--es", "dev.phonestation.adbkeep.PAGE", "permissions"]
            let result = StationRunner.capture(adb, ["-s", serial, "shell", "am", "start", "-n", "dev.phonestation.adbkeep/.MainActivity"] + args, timeout: 10)
            await MainActor.run {
                self.feedback.activity = nil
                self.feedback.notice = result.succeeded ? "已打开手机页面，请在手机上手动选择授权。" : "手机页面未能打开，请在手机上进入远程访问范围。"
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
        if let blocker = installBlocker() {
            feedback.notice = blocker.title + "。尚未开始安装。" + blocker.detail
            return
        }
        let previousID = installJobID
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        remember(id) // 执行前落盘，进程中断或窗口关闭也不丢任务编号。
        feedback.activity = "正在安装手机上的「手机工位」…"
        feedback.notice = nil
        Task.detached(priority: .userInitiated) {
            let result = StationRunner.scriptResult("android.sh", ["--job-id", id], timeout: 600)
            await MainActor.run {
                self.feedback.activity = nil
                if let receipt = InstallTaskReceipt.parse(result.output) {
                    self.remember(receipt.jobID)
                    self.feedback.notice = receipt.notice + " 任务：" + receipt.jobID
                    if receipt.verified { self.onFinished() }
                } else if result.output.contains("尚未提交新操作") {
                    self.remember(previousID)
                    self.feedback.notice = StationText.reason(result.output, fallback: "远程安装未授权，尚未开始安装。")
                } else if result.succeeded {
                    self.remember(nil) // 本地 adb 安装没有远程任务。
                    self.feedback.notice = "已安装。"
                    self.onFinished()
                } else {
                    self.feedback.notice = "安装结果未确认。任务：\(id)。请查询原任务，避免重复安装。"
                }
            }
        }
    }
}
