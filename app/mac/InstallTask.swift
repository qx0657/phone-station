import Foundation

struct InstallTaskReceipt {
    let jobID: String
    let state: String
    let verified: Bool
    let completed: Bool

    static func validID(_ id: String) -> Bool {
        id.range(of: #"^[0-9a-f]{32}$"#, options: .regularExpression) != nil
    }
    static func parse(_ output: String) -> InstallTaskReceipt? {
        for line in output.split(separator: "\n").reversed() {
            guard let data = String(line).data(using: .utf8),
                  let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let id = value["jobId"] as? String, validID(id) else { continue }
            return InstallTaskReceipt(jobID: id, state: value["state"] as? String ?? "result_unknown",
                                      verified: value["verified"] as? Bool == true,
                                      completed: value["completed"] as? Bool == true)
        }
        return nil
    }
    var notice: String {
        if completed { return "已远程安装，应用启动与连接恢复已确认。" }
        switch state {
        case "running", "recovering": return "原安装任务仍在处理，可稍后查询。"
        case "installed_restart_failed": return "安装已完成，但应用启动失败。保留原任务，不要重复安装。"
        case "failed": return "原安装任务失败，请查看安装记录。"
        default: return verified ? "安装已完成，应用恢复尚未确认。请查询原任务。"
            : "安装结果未知，请保留任务编号查询，避免重复安装。"
        }
    }
}

struct InstallTaskStore {
    let defaults: UserDefaults
    private let key = "phoneStationInstallJobID"
    var jobID: String? {
        get { defaults.string(forKey: key).flatMap { InstallTaskReceipt.validID($0) ? $0 : nil } }
        nonmutating set {
            if let newValue, InstallTaskReceipt.validID(newValue) { defaults.set(newValue, forKey: key) }
            else { defaults.removeObject(forKey: key) }
        }
    }
}
