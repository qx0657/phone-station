import SwiftUI

struct StationView: View {
    @ObservedObject var station: Station

    var body: some View {
        ZStack {
            pageContent
                .disabled(station.dialog != nil)
                .allowsHitTesting(station.dialog == nil)
                .accessibilityHidden(station.dialog != nil)
            if let dialog = station.dialog {
                Color.black.opacity(0.24).onTapGesture { station.dialog = nil }
                    .accessibilityHidden(true)
                StationDialogContent(kind: dialog, appearance: station.appearance,
                    removeRemote: { station.mcp.unpairRemote() }, cancel: { station.dialog = nil })
                    .id(dialog).padding(16)
            }
        }
        .frame(width: 360, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(StationPalette.background)
        .environment(\.locale, station.appearance.locale)
        .task { station.refresh() }
    }

    private var pageContent: some View {
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
    }
}
