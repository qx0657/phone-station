import SwiftUI

struct StationView: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch station.page {
            case .main:
                MainPage(station: station)
            case .connection:
                ConnectionPage(station: station)
            case .commands:
                CommandsPage(station: station)
            case .editCommand:
                CommandEditor(commands: station.commands)
                    .id(station.commands.commandDraftToken)
            case .more:
                MorePage(station: station)
            case .files:
                FilesPage(station: station)
            case .about:
                AboutPage(station: station)
            case .mcp:
                McpPage(station: station)
            case .remotePermissions:
                RemotePermissionsPage(station: station)
            case .features:
                FeaturesPage(station: station)
            case .remoteRelay:
                RemoteRelayPage(station: station, mcp: station.mcp)
            case .clipboard:
                ClipboardPage(station: station, clipboard: station.clipboard)
            case .notifications:
                NotificationPage(station: station, notifications: station.notifications)
            }
        }
        .frame(width: 360, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(StationPalette.background)
        .task { station.refresh() }
    }
}
