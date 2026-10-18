import Foundation
import ServiceManagement

enum StationService {
    static let plistName = "dev.phonestation.stationd.plist"

    static func register() -> String {
        let service = SMAppService.agent(plistName: plistName)
        switch service.status {
        case .enabled:
            return "enabled"
        case .requiresApproval:
            return "approval"
        default:
            break
        }
        do {
            try service.register()
        } catch {
            return "error"
        }
        switch service.status {
        case .enabled:
            return "enabled"
        case .requiresApproval:
            return "approval"
        default:
            return "registered"
        }
    }
}
