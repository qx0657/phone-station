import SwiftUI

struct AppearanceSettings: View {
    @ObservedObject var appearance: StationAppearance
    var open: (StationDialogKind) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            StationRows.sectionTitle(StationL10n.text("语言与外观"))
            VStack(spacing: 0) {
                StationRows.navigationRow("语言 / Language", symbol: "character.bubble", detail: languageLabel) { open(.language) }
                StationRows.groupDivider
                StationRows.navigationRow("主题", symbol: "circle.lefthalf.filled", detail: themeLabel) { open(.theme) }
            }.elevatedGroup()
        }
    }
    private var languageLabel: String {
        switch appearance.language { case .system: return "跟随系统"; case .zh: return "中文"; case .en: return "English" }
    }
    private var themeLabel: String {
        switch appearance.theme { case .system: return "跟随系统"; case .light: return "浅色"; case .dark: return "深色" }
    }
}
