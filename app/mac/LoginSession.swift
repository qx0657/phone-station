import AppKit
import Foundation
import ServiceManagement

@MainActor
final class LoginSession: ObservableObject {
    @Published private(set) var opensAtLogin = false
    @Published private(set) var needsApproval = false
    @Published var note: String?

    init() {
        refresh()
    }

    var subtitle: String {
        if let note, !note.isEmpty { return note }
        return "登录这台 Mac 后自动打开"
    }

    var dependencyDetails: [(String, Bool)] {
        [("adb", StationRunner.executable("adb") != nil),
         ("scrcpy", StationRunner.executable("scrcpy") != nil),
         ("python3", StationRunner.executable("python3") != nil)]
    }

    func refresh() {
        switch SMAppService.mainApp.status {
        case .enabled:
            opensAtLogin = true
            needsApproval = false
            note = nil
        case .requiresApproval:
            opensAtLogin = true
            needsApproval = true
            note = "登录后自动打开。请在「系统设置 › 通用 › 登录项与扩展」里允许。"
        case .notRegistered, .notFound:
            opensAtLogin = false
            needsApproval = false
        @unknown default:
            opensAtLogin = false
            needsApproval = false
        }
    }

    func setOpensAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            refresh()
            if !needsApproval {
                note = Self.failure(error)
            }
            return
        }
        note = nil
        refresh()
        if enabled && !opensAtLogin && !needsApproval {
            note = Self.failure(nil)
        }
    }

    func openSettings() {
        let candidates = [
            "x-apple.systempreferences:com.apple.LoginItems-Settings.extension",
            "x-apple.systempreferences:com.apple.preference.users"
        ]
        for raw in candidates {
            guard let url = URL(string: raw) else { continue }
            if NSWorkspace.shared.open(url) { return }
        }
    }

    func openGuide() {
        guard let file = Bundle.main.resourceURL?.appendingPathComponent("phone-station/README.md") else { return }
        NSWorkspace.shared.open(file)
    }

    private static func failure(_ error: Error?) -> String {
        let location = Bundle.main.bundlePath
        let homeApps = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications").path
        let installed = location.hasPrefix("/Applications/") || location.hasPrefix(homeApps + "/")
        if !installed {
            return "请先把「手机工位.app」放进「应用程序」文件夹，再打开那个副本。"
        }
        if let error {
            let text = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return String(text.prefix(140)) }
        }
        return "系统没有接受这项设置。"
    }
}
