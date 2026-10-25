import Combine
import Foundation

enum StationPage: Equatable {
    case main
    case connection
    case commands
    case editCommand
    case more
    case files
    case about
    case mcp
    case remoteRelay
    case stationPair
    case remotePermissions
    case features
    case clipboard
    case notifications

    var title: String {
        switch self {
        case .main: return "手机工位"
        case .connection: return "连接设置"
        case .commands: return "命令与终端"
        case .editCommand: return "编辑命令"
        case .more: return "设置"
        case .files: return "最近文件"
        case .about: return "关于"
        case .mcp: return "MCP 服务"
        case .remotePermissions: return "远程访问范围"
        case .features: return "手机功能管理"
        case .remoteRelay: return "远程接入"
        case .stationPair: return "工位配对"
        case .clipboard: return "共享剪贴板"
        case .notifications: return "手机通知"
        }
    }

    var backFallback: StationPage {
        switch self {
        case .editCommand: return .commands
        case .commands, .about, .features, .remotePermissions: return .more
        case .remoteRelay: return .mcp
        case .stationPair: return .connection
        default: return .main
        }
    }
}

enum PhoneFeatureGroup: String, CaseIterable {
    case sharing, files, commands, screen
    var title: String {
        switch self {
        case .sharing: return "通知与剪贴板"
        case .files: return "文件访问"
        case .commands: return "命令与安装"
        case .screen: return "屏幕与手机控制"
        }
    }
    var keys: [String] {
        switch self {
        case .sharing: return ["clipboard", "notifications", "alerts"]
        case .files: return ["files.read", "files.write", "open"]
        case .commands: return ["shell"]
        case .screen: return ["capture", "screen", "awake", "torch"]
        }
    }
}

/// Return to the actual entry page; selecting an ancestor unwinds the path.
struct StationNavigation {
    private var history: [StationPage] = []
    mutating func changed(from old: StationPage, to next: StationPage) {
        guard old != next else { return }
        if next == .main { history.removeAll() }
        else if let index = history.lastIndex(of: next) { history.removeSubrange(index...) }
        else { history.append(old) }
    }
    func destination(fallback: StationPage) -> StationPage { history.last ?? fallback }
}

@MainActor
final class StationFeedback: ObservableObject {
    @Published var activity: String?
    @Published var notice: String?
}
