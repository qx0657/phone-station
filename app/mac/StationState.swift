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
    case remotePermissions
    case features
    case clipboard
    case notifications
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
