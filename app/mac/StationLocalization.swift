import AppKit
import Combine
import Foundation

/// Display preferences are independent of connection, feature and access settings.
@MainActor final class StationAppearance: ObservableObject {
    enum Language: String, CaseIterable { case system, zh, en }
    enum Theme: String, CaseIterable { case system, light, dark }
    static let shared = StationAppearance()
    private let defaults: UserDefaults
    private var localeObserver: NSObjectProtocol?
    @Published var language: Language { didSet { defaults.set(language.rawValue, forKey: "displayLanguage") } }
    @Published var theme: Theme { didSet { defaults.set(theme.rawValue, forKey: "displayTheme"); applyTheme() } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        language = Language(rawValue: defaults.string(forKey: "displayLanguage") ?? "") ?? .system
        theme = Theme(rawValue: defaults.string(forKey: "displayTheme") ?? "") ?? .system
        localeObserver = NotificationCenter.default.addObserver(forName: NSLocale.currentLocaleDidChangeNotification,
            object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.objectWillChange.send() }
            }
    }
    deinit { if let localeObserver { NotificationCenter.default.removeObserver(localeObserver) } }
    var locale: Locale { Locale(identifier: StationL10n.english(language: language.rawValue) ? "en" : "zh-Hans") }
    func applyTheme() {
        NSApp?.appearance = theme == .system ? nil : NSAppearance(named: theme == .dark ? .darkAqua : .aqua)
    }
}

enum StationL10n {
    static func english(language: String? = nil, system: String? = nil) -> Bool {
        let selected = language ?? UserDefaults.standard.string(forKey: "displayLanguage") ?? "system"
        if selected == "zh" { return false }
        if selected == "en" { return true }
        return !(system ?? Locale.preferredLanguages.first ?? "en").lowercased().hasPrefix("zh")
    }
    private static let catalog: DisplayCopyCatalog = {
        guard let url = Bundle.main.url(forResource: "ui-en", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let entries = try? JSONDecoder().decode([String: String].self, from: data) else {
            return DisplayCopyCatalog(entries: [:])
        }
        return DisplayCopyCatalog(entries: entries)
    }()
    static func text(_ source: String) -> String { english() ? catalog.translate(source) : source }
    /// Arguments are inserted after translation, preserving names, paths and user content.
    static func format(_ source: String, _ arguments: String...) -> String {
        let canonical = DisplayCopyCatalog.substitute(source, arguments)
        let template = english() ? catalog.exact(canonical) ?? text(source) : source
        return DisplayCopyCatalog.substitute(template, arguments)
    }
}

/// Canonical copy stays in state models; only app-owned presentation is translated.
struct DisplayCopyCatalog {
    private let entries: [String: String]
    private let phrases: [String]
    private let templates: [(NSRegularExpression, String)]
    private static let slots = try! NSRegularExpression(pattern: "\\{([0-9]+)\\}")
    func exact(_ source: String) -> String? { entries[source] }
    static func substitute(_ template: String, _ arguments: [String]) -> String {
        var result = "", offset = template.startIndex
        for match in slots.matches(in: template, range: NSRange(template.startIndex..., in: template)) {
            let range = Range(match.range, in: template)!
            let index = Int(template[Range(match.range(at: 1), in: template)!])!
            result += template[offset..<range.lowerBound]
            result += index < arguments.count ? arguments[index] : String(template[range])
            offset = range.upperBound
        }
        return result + template[offset...]
    }
    init(entries: [String: String]) {
        self.entries = entries
        phrases = entries.keys.sorted { $0.count > $1.count }
        let slots = try! NSRegularExpression(pattern: "\\{[0-9]+\\}")
        templates = phrases.compactMap { source in
            let whole = NSRange(source.startIndex..., in: source)
            let matches = slots.matches(in: source, range: whole)
            guard !matches.isEmpty else { return nil }
            var pattern = "^", offset = source.startIndex
            for match in matches {
                let range = Range(match.range, in: source)!
                pattern += NSRegularExpression.escapedPattern(for: String(source[offset..<range.lowerBound])) + "(.*?)"
                offset = range.upperBound
            }
            pattern += NSRegularExpression.escapedPattern(for: String(source[offset...])) + "$"
            return (try! NSRegularExpression(pattern: pattern, options: .dotMatchesLineSeparators), entries[source]!)
        }
    }
    func translate(_ source: String) -> String {
        if let exact = entries[source] { return exact }
        for (pattern, translation) in templates {
            guard let match = pattern.firstMatch(in: source, range: NSRange(source.startIndex..., in: source)) else { continue }
            let arguments = (1..<match.numberOfRanges).map { String(source[Range(match.range(at: $0), in: source)!]) }
            return Self.substitute(translation, arguments)
        }
        let indices = Array(source.indices) + [source.endIndex]
        var suffix = [String?](repeating: nil, count: indices.count)
        suffix[suffix.count - 1] = ""
        for position in indices.dropLast().indices.reversed() {
            let offset = indices[position]
            for phrase in phrases where !phrase.isEmpty && !phrase.contains("{") && source[offset...].hasPrefix(phrase) {
                guard let rest = suffix[position + phrase.count] else { continue }
                let first = entries[phrase]!
                let needsSpace = first.last.map { $0.isLetter || $0.isNumber || $0 == "." } == true
                    && rest.first?.isLetter == true
                suffix[position] = first + (needsSpace ? " " : "") + rest
                break
            }
            if suffix[position] == nil {
                let character = source[offset]
                if character.unicodeScalars.contains(where: { (0x3400...0x9FFF).contains($0.value) }) { continue }
                guard let rest = suffix[position + 1] else { continue }
                let punctuation: [Character: String] = ["。": ".", "，": ", ", "；": "; ", "：": ": ", "「": "\"", "」": "\""]
                suffix[position] = (punctuation[character] ?? String(character)) + rest
            }
        }
        return suffix[0] ?? source
    }
}
