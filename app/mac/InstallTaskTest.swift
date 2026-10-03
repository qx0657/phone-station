import Foundation

@main
struct InstallTaskTest {
    static func main() throws {
        let id = String(repeating: "a", count: 32)
        let receipt = InstallTaskReceipt.parse("build output\n{\"jobId\":\"\(id)\",\"state\":\"result_unknown\",\"verified\":false}\n")!
        precondition(receipt.jobID == id && !receipt.completed && receipt.notice.contains("未知"))
        precondition(InstallTaskReceipt.parse("{\"jobId\":\"invalid\"}") == nil)
        let suite = "install-task-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = InstallTaskStore(defaults: defaults)
        store.jobID = id
        precondition(InstallTaskStore(defaults: UserDefaults(suiteName: suite)!).jobID == id)
        store.jobID = "invalid"
        precondition(store.jobID == nil)
        print("InstallTaskTest ok")
    }
}
