import Foundation

@main enum StationLocalizationTest {
    @MainActor static func main() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: "app/i18n/en.json"))
        let entries = try JSONDecoder().decode([String: String].self, from: data)
        let catalog = DisplayCopyCatalog(entries: entries)
        precondition(catalog.translate("设置") == "Settings")
        precondition(catalog.translate("已允许 2/6 项") == "Allowed: 2/6 capabilities")
        precondition(catalog.translate("正在执行「设置」…") == "Running “设置”…")
        precondition(catalog.translate("未知的用户草稿") == "未知的用户草稿")
        precondition(DisplayCopyCatalog.substitute("{0} / {1}", ["用户 {1}", "文件"]) == "用户 {1} / 文件")
        precondition(catalog.translate("本地：运行中；远程：连接中。") == "Local: Running; remote: Connecting.")
        precondition(catalog.translate("远程连接中") == "Remote Connecting")
        precondition(catalog.translate("远程未连接") == "Remote disconnected")
        precondition(StationL10n.english(language: "system", system: "en-US"))
        precondition(!StationL10n.english(language: "system", system: "zh-Hant-TW"))
        precondition(StationL10n.english(language: "system", system: "fr-FR"))
        precondition(!StationL10n.english(language: "zh", system: "en-US"))
        precondition(StationL10n.english(language: "en", system: "zh-CN"))
        let suite = "PhoneStation.AppearanceTest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = StationAppearance(defaults: defaults)
        precondition(first.language == .system && first.theme == .system)
        first.language = .en
        // Exercise persistence without changing the running app's appearance.
        defaults.set("dark", forKey: "displayTheme")
        let restored = StationAppearance(defaults: defaults)
        precondition(restored.language == .en && restored.theme == .dark)
        defaults.set("unsupported", forKey: "displayLanguage")
        defaults.set("unsupported", forKey: "displayTheme")
        let fallback = StationAppearance(defaults: defaults)
        precondition(fallback.language == .system && fallback.theme == .system)
        print("StationLocalizationTest passed")
    }
}
