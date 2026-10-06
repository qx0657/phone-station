import SwiftUI

enum StationDialogKind: Hashable { case language, theme, removeRemote }

struct StationDialogChoice: Identifiable {
    let id: String
    let title: String
    let symbol: String
}

/// Shared app-owned content. Kept inside the menu-bar panel so outside-click
/// handling and page drafts remain owned by the existing popover.
struct StationDialogSurface: View {
    let title: String
    let symbol: String
    var message: String? = nil
    var choices: [StationDialogChoice] = []
    var selected: String = ""
    var actionTitle: String? = nil
    var destructive = false
    var select: (String) -> Void = { _ in }
    var confirm: () -> Void = {}
    let cancel: () -> Void
    @FocusState private var focused: String?

    private var accent: Color { destructive ? StationPalette.recording : StationPalette.connected }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(accent)
                    .frame(width: 42, height: 42)
                    .background(accent.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)
                Text(StationL10n.text(title))
                    .font(.system(size: 17, weight: .semibold))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
            }
            if let message {
                Text(StationL10n.text(message)).font(.system(size: 13))
                    .foregroundStyle(.primary).lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !choices.isEmpty {
                VStack(spacing: 8) {
                    ForEach(choices) { choice in
                        Button { select(choice.id) } label: {
                            HStack(spacing: 10) {
                                Image(systemName: choice.symbol).frame(width: 20)
                                    .foregroundStyle(selected == choice.id ? accent : Color.secondary)
                                    .accessibilityHidden(true)
                                Text(StationL10n.text(choice.title))
                                    .font(.system(size: 14, weight: selected == choice.id ? .medium : .regular))
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 8)
                                Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(accent).frame(width: 16)
                                    .opacity(selected == choice.id ? 1 : 0).accessibilityHidden(true)
                            }.padding(.horizontal, 12).padding(.vertical, 12)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                .background(selected == choice.id ? accent.opacity(0.08) : Color.primary.opacity(0.04),
                                            in: RoundedRectangle(cornerRadius: 10))
                                .overlay { HoverWash(radius: 10) }
                                .overlay {
                                    RoundedRectangle(cornerRadius: 10)
                                        .strokeBorder(selected == choice.id || focused == choice.id ? accent : Color.clear, lineWidth: 1)
                                }.contentShape(RoundedRectangle(cornerRadius: 10))
                        }.buttonStyle(PressFadeStyle()).focused($focused, equals: choice.id)
                            .accessibilityAddTraits(selected == choice.id ? .isSelected : [])
                    }
                }
            }
            VStack(spacing: 8) {
                if let actionTitle {
                    Button(role: destructive ? .destructive : nil, action: confirm) {
                        Text(StationL10n.text(actionTitle))
                            .frame(maxWidth: .infinity, minHeight: 42)
                            .foregroundStyle(Color(nsColor: dialogForeground))
                            .background(accent, in: RoundedRectangle(cornerRadius: 10))
                            .overlay { HoverWash(radius: 10) }
                    }.buttonStyle(PressFadeStyle()).focused($focused, equals: "confirm")
                        .overlay { focusOutline("confirm") }
                }
                Button(action: cancel) {
                    Text(StationL10n.text("取消"))
                        .frame(maxWidth: .infinity, minHeight: 42)
                        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 10))
                        .overlay { HoverWash(radius: 10) }
                }.buttonStyle(PressFadeStyle()).focused($focused, equals: "cancel")
                    .overlay { focusOutline("cancel") }.keyboardShortcut(.cancelAction)
            }.font(.system(size: 13, weight: .medium))
        }
        .foregroundStyle(.primary)
        .padding(20)
        .frame(maxWidth: 328)
        .background(StationPalette.tile, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.primary.opacity(0.08)) }
        .shadow(color: .black.opacity(0.16), radius: 20, y: 8)
        .accessibilityElement(children: .contain).accessibilityLabel(StationL10n.text(title))
        .onAppear { focused = choices.isEmpty ? "cancel" : selected }
        .onExitCommand(perform: cancel)
    }

    private var dialogForeground: NSColor {
        NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                ? NSColor(srgbRed: 0.06, green: 0.07, blue: 0.08, alpha: 1) : .white
        }
    }
    private func focusOutline(_ id: String) -> some View {
        RoundedRectangle(cornerRadius: 10).strokeBorder(focused == id ? Color.primary.opacity(0.7) : Color.clear, lineWidth: 2)
            .padding(-3).allowsHitTesting(false)
    }
}

struct StationDialogContent: View {
    let kind: StationDialogKind
    @ObservedObject var appearance: StationAppearance
    let removeRemote: () -> Void
    let cancel: () -> Void

    var body: some View {
        switch kind {
        case .language:
            StationDialogSurface(title: "语言 / Language", symbol: "character.bubble",
                choices: [.init(id: "system", title: "跟随系统", symbol: "desktopcomputer"),
                          .init(id: "zh", title: "中文", symbol: "globe"),
                          .init(id: "en", title: "English", symbol: "globe")],
                selected: appearance.language.rawValue, select: { value in
                    guard let language = StationAppearance.Language(rawValue: value) else { return }
                    cancel()
                    appearance.language = language
                }, cancel: cancel)
        case .theme:
            StationDialogSurface(title: "主题", symbol: "circle.lefthalf.filled",
                choices: [.init(id: "system", title: "跟随系统", symbol: "desktopcomputer"),
                          .init(id: "light", title: "浅色", symbol: "sun.max"),
                          .init(id: "dark", title: "深色", symbol: "moon")],
                selected: appearance.theme.rawValue, select: { value in
                    guard let theme = StationAppearance.Theme(rawValue: value) else { return }
                    cancel()
                    appearance.theme = theme
                }, cancel: cancel)
        case .removeRemote:
            StationDialogSurface(title: "清除此 Mac 的远程配置？", symbol: "trash",
                message: "此 Mac 将停止使用远程中继。手机配置保留，本地 adb 仍可使用。",
                actionTitle: "清除 Mac 配置", destructive: true,
                confirm: { cancel(); removeRemote() }, cancel: cancel)
        }
    }
}
