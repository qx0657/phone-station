import Combine
import Foundation

enum StationPage {
    case main
    case connection
    case commands
    case editCommand
    case more
    case files
    case about
    case mcp
    case remoteRelay
    case clipboard
    case notifications
}

@MainActor
final class StationFeedback: ObservableObject {
    @Published var activity: String?
    @Published var notice: String?
}
