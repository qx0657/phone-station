import AppKit
import ApplicationServices
import Combine
import OSLog
import ServiceManagement
import SwiftUI

@main
@MainActor
final class PhoneStationApp: NSObject, NSApplicationDelegate {
    // NSApplication does not retain its delegate. Keep this instance alive for the app's lifetime.
    private static let retainedDelegate = PhoneStationApp()
    /// Visible status items on this Mac use preferred positions around 350–570.
    /// Hidden Bar's separator is 608 and its collapsed control is near 5624. This app had
    /// saved 5656, which leaves the window off the menu bar while `isVisible` stays true.
    /// Control Center reads the value when the process creates its first status item.
    private static let preferredPositionKey = "NSStatusItem Preferred Position Item-0"
    private static let visiblePreferredPosition = 400.0
    private let logger = Logger(subsystem: "com.qx0657.phonestation", category: "lifecycle")
    private let station = Station()
    private var statusItem: NSStatusItem?
    private var popover: NSPopover?
    private var settingsWindow: NSWindow?
    private var pageSubscription: AnyCancellable?
    private var menuBarShowsConnected: Bool?
    private var appearanceSubscription: AnyCancellable?
    /// Clicks in other apps never reach an `LSUIElement` popover, so `.transient` stays open.
    private var outsideClickMonitor: Any?
    private var localDismissMonitor: Any?

    static func main() {
        if CommandLine.arguments.dropFirst().contains("--register-station") {
            let result = StationService.register()
            fputs(result + "\n", stdout)
            exit(result == "enabled" || result == "registered" || result == "approval" ? 0 : 1)
        }
        if CommandLine.arguments.dropFirst().contains("--unregister-station") {
            do {
                try SMAppService.agent(plistName: StationService.plistName).unregister()
                fputs("unregistered\n", stdout)
                exit(0)
            } catch {
                fputs("error\n", stderr)
                exit(1)
            }
        }
        let app = NSApplication.shared
        app.delegate = retainedDelegate
        app.setActivationPolicy(.accessory)
        exposeToAccessibility()
        app.run()
    }

    /// 菜单栏应用没有 Dock 图标。不打开这项时，辅助功能看不到面板和设置窗口。
    static func exposeToAccessibility() {
        let element = AXUIElementCreateApplication(getpid())
        AXUIElementSetAttributeValue(element, "AXManualAccessibility" as CFString, kCFBooleanTrue)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        Self.exposeToAccessibility()
        station.appearance.applyTheme()
        repairPreferredPositionIfNeeded()
        installStatusItem()
        appearanceSubscription = station.appearance.objectWillChange.sink { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                let connected = self.menuBarShowsConnected ?? false
                self.menuBarShowsConnected = nil
                self.applyMenuBar(connected: connected)
                self.settingsWindow?.title = self.station.windowTitle
                NotificationBannerCenter.shared.refreshAppearance()
                for window in NSApp.windows where ["手机工位 · 远程投屏", "Phone Station · Remote mirroring"].contains(window.title) {
                    window.title = StationL10n.text("手机工位 · 远程投屏")
                }
            }
        }
        schedulePlacementLog()
    }

    func applicationWillTerminate(_ notification: Notification) {
        station.link.stopWatch()
        station.mcp.stopWatch()
        station.stopLocalEvents()
    }

    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        guard let button = item.button else {
            fputs("无法创建菜单栏图标。\n", stderr)
            NSApplication.shared.terminate(nil)
            return
        }
        button.imagePosition = .imageOnly
        button.target = self
        button.action = #selector(togglePopover)
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        let openSettings = NSAccessibilityCustomAction(name: StationL10n.text("设置")) { [weak self] in
            guard let self else { return false }
            self.station.page = .more
            return true
        }
        button.setAccessibilityCustomActions([openSettings])
        statusItem = item
        station.link.onMenuIcon = { [weak self] connected in
            self?.applyMenuBar(connected: connected)
        }
        applyMenuBar(connected: false)

        let panel = NSPopover()
        panel.behavior = .transient
        panel.animates = false
        panel.delegate = self
        let host = NSHostingController(rootView: MainPage(station: station))
        // Hug whichever page is showing. A fixed height leaves a gap under the shorter ones.
        host.sizingOptions = .preferredContentSize
        panel.contentSize = NSSize(width: 360, height: 420)
        panel.contentViewController = host
        popover = panel
        pageSubscription = station.$page.sink { [weak self] page in
            DispatchQueue.main.async { self?.syncSettingsWindow(page) }
        }
        NotificationBannerCenter.shared.onOpen = { [weak self] in
            self?.station.page = .notifications
        }
        station.link.startWatch()
        station.mcp.startWatch()
        station.startLocalEvents()
    }

    private func applyMenuBar(connected: Bool) {
        guard let button = statusItem?.button else { return }
        if menuBarShowsConnected != connected {
            menuBarShowsConnected = connected
            button.image = menuBarImage(connected: connected)
            logger.notice("Menu bar icon tracks link, connected: \(connected, privacy: .public), status: \(self.station.link.statusLabel, privacy: .public)")
        }
        let line = StationL10n.text(station.link.connectionLine)
        let name = StationL10n.text(station.link.headline)
        let tip = StationL10n.format("手机工位：{0}", StationL10n.text("\(station.link.statusLabel)")) + "\n" + name + (line.isEmpty ? "" : "\n\(line)")
        if button.toolTip != tip {
            button.toolTip = tip
        }
    }

    /// Do not assign `size` on this image. A 12×12 box clips the symbol and the slot looks empty.
    /// Both symbols share a point size so the status item does not jump when the link changes.
    private func menuBarImage(connected: Bool) -> NSImage {
        let symbolName = connected ? "iphone" : "iphone.slash"
        let description = connected ? StationL10n.text("手机已连接") : StationL10n.text("手机未连接")
        let config = NSImage.SymbolConfiguration(pointSize: 16, weight: .medium)
        if let symbol = NSImage(systemSymbolName: symbolName, accessibilityDescription: description)?
            .withSymbolConfiguration(config) {
            symbol.isTemplate = true
            return symbol
        }
        let side: CGFloat = 18
        let image = NSImage(size: NSSize(width: side, height: side), flipped: false) { _ in
            NSColor.black.setStroke()
            let body = NSBezierPath(roundedRect: NSRect(x: 4.5, y: 1, width: 9, height: 16),
                                    xRadius: 2.2, yRadius: 2.2)
            body.lineWidth = 1.6
            body.stroke()
            if !connected {
                let slash = NSBezierPath()
                slash.move(to: NSPoint(x: 3, y: 16))
                slash.line(to: NSPoint(x: 15, y: 2))
                slash.lineWidth = 1.6
                slash.stroke()
            }
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = description
        return image
    }

    private func repairPreferredPositionIfNeeded() {
        let defaults = UserDefaults.standard
        let value = (defaults.object(forKey: Self.preferredPositionKey) as? NSNumber)?.doubleValue
        let usable = value.map { $0.isFinite && $0 >= 80 && $0 <= 600 } ?? false
        if !usable {
            defaults.set(Self.visiblePreferredPosition, forKey: Self.preferredPositionKey)
        }
    }

    private func schedulePlacementLog() {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 800_000_000)
            let frame = self.statusItem?.button?.window?.frame ?? .zero
            let onBar = NSScreen.screens.contains { screen in
                let band = NSRect(x: screen.frame.minX, y: screen.frame.maxY - 36,
                                  width: screen.frame.width, height: 36)
                return band.contains(NSPoint(x: frame.midX, y: frame.midY))
            }
            self.logger.notice(
                "Menu bar status item settled, on bar: \(onBar, privacy: .public), window: \(NSStringFromRect(frame), privacy: .public)")
        }
    }

    @objc private func togglePopover() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            showPhoneMenu()
            return
        }
        guard let popover else { return }
        if popover.isShown {
            popover.performClose(nil)
        } else {
            showMainPanel(offerPairing: true)
        }
    }

    private func showPhoneMenu() {
        guard let button = statusItem?.button else { return }
        let phones = station.link.phones
        guard phones.count > 1 else {
            togglePopoverFromMenu()
            return
        }
        let menu = NSMenu()
        let header = NSMenuItem(title: StationL10n.text("切换手机"), action: nil, keyEquivalent: "")
        header.isEnabled = false
        menu.addItem(header)
        for seat in phones {
            let title = StationL10n.text(seat.transport) + " · " + seat.identity
            let item = NSMenuItem(title: title, action: #selector(choosePhone(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = seat.serial
            let current = station.link.serial
            item.state = current == seat.serial || seat.aliases.contains(current ?? "") ? .on : .off
            menu.addItem(item)
        }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: button.bounds.height), in: button)
    }

    @objc private func choosePhone(_ sender: NSMenuItem) {
        guard let serial = sender.representedObject as? String else { return }
        station.link.selectPhone(serial)
    }

    private func togglePopoverFromMenu() {
        showMainPanel(offerPairing: true)
    }

    private func showMainPanel(offerPairing: Bool) {
        guard let button = statusItem?.button, let popover else { return }
        if popover.isShown { return }
        if station.page != .main { station.page = .main }
        // popoverDidShow refreshes the page once it is visible.
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        NSApplication.shared.activate(ignoringOtherApps: true)
        if offerPairing && station.link.needsWirelessPairing { station.page = .connection }
    }

    private func syncSettingsWindow(_ page: StationPage) {
        // Published page changes are delivered asynchronously. Ignore an obsolete route.
        guard page == station.page else { return }
        if page == .main {
            // Explicit window close clears settingsOpen before resetting the route.
            // Returning from a detail page keeps it set until home is visible again.
            let returningHome = station.settingsOpen
            station.settingsOpen = false
            settingsWindow?.orderOut(nil)
            if popover?.isShown != true { statusItem?.button?.setAccessibilityChildren(nil) }
            if returningHome { showMainPanel(offerPairing: false) }
            return
        }
        let window = ensureSettingsWindow()
        window.title = station.windowTitle
        station.settingsOpen = true
        window.level = .floating
        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
        NSApplication.shared.activate(ignoringOtherApps: true)
        NSAccessibility.post(element: window, notification: .created)
        if popover?.isShown == true { popover?.performClose(nil) }
    }

    private func ensureSettingsWindow() -> NSWindow {
        if let settingsWindow { return settingsWindow }
        let host = NSHostingController(rootView: StationView(station: station))
        host.sizingOptions = .preferredContentSize
        let window = StationDetailWindow(contentViewController: host)
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.level = .floating
        window.hidesOnDeactivate = false
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.setContentSize(NSSize(width: 360, height: 560))
        window.title = station.windowTitle
        window.isReleasedWhenClosed = false
        window.delegate = self
        settingsWindow = window
        return window
    }

    private func installDismissMonitors() {
        removeDismissMonitors()
        let clicks: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown]
        outsideClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: clicks) { [weak self] _ in
            let point = NSEvent.mouseLocation
            MainActor.assumeIsolated {
                self?.closeFromOutsideClick(at: point)
            }
        }
        localDismissMonitor = NSEvent.addLocalMonitorForEvents(matching: [clicks, .keyDown]) { [weak self] event in
            let escape = event.type == .keyDown && event.keyCode == 53
            let mouse = event.type == .leftMouseDown || event.type == .rightMouseDown
            let point = NSEvent.mouseLocation
            let swallow = MainActor.assumeIsolated { () -> Bool in
                self?.handleLocalDismiss(escape: escape, mouse: mouse, at: point) ?? false
            }
            return swallow ? nil : event
        }
    }

    private func removeDismissMonitors() {
        if let outsideClickMonitor {
            NSEvent.removeMonitor(outsideClickMonitor)
            self.outsideClickMonitor = nil
        }
        if let localDismissMonitor {
            NSEvent.removeMonitor(localDismissMonitor)
            self.localDismissMonitor = nil
        }
    }

    /// The status item handles its own toggle. Closing here as well would reopen on the same click.
    private func closeFromOutsideClick(at point: NSPoint) {
        guard popover?.isShown == true else { return }
        if clickHitsStatusItem(point) || clickHitsPopover(point) { return }
        logger.notice("Menu bar panel closed from an outside click")
        popover?.performClose(nil)
    }

    /// Escape is swallowed. An outside click still goes through, so the window under it is reached.
    private func handleLocalDismiss(escape: Bool, mouse: Bool, at point: NSPoint) -> Bool {
        guard popover?.isShown == true else { return false }
        if escape {
            if station.dialog != nil {
                station.dialog = nil
                return true
            }
            logger.notice("Menu bar panel closed from Escape")
            popover?.performClose(nil)
            return true
        }
        if mouse, !clickHitsStatusItem(point), !clickHitsPopover(point) {
            logger.notice("Menu bar panel closed from an outside click")
            popover?.performClose(nil)
        }
        return false
    }

    private func clickHitsStatusItem(_ point: NSPoint) -> Bool {
        guard let button = statusItem?.button, let window = button.window else { return false }
        let rect = window.convertToScreen(button.convert(button.bounds, to: nil))
        return rect.contains(point)
    }

    private func clickHitsPopover(_ point: NSPoint) -> Bool {
        guard let window = popover?.contentViewController?.view.window else { return false }
        return window.frame.contains(point)
    }
}

extension PhoneStationApp: NSPopoverDelegate {
    func popoverDidShow(_ notification: Notification) {
        Self.exposeToAccessibility()
        if let view = popover?.contentViewController?.view {
            view.setAccessibilityElement(true)
            view.setAccessibilityRole(.group)
            view.setAccessibilityLabel(StationL10n.text("手机工位"))
            view.window?.setAccessibilityElement(true)
            view.window?.setAccessibilityRole(.window)
            view.window?.setAccessibilityLabel(StationL10n.text("手机工位"))
            statusItem?.button?.setAccessibilityChildren([view])
        }
        station.panelVisible = true
        station.remoteControls.monitoring = true
        station.refresh()
        station.remoteControls.refresh()
        installDismissMonitors()
    }

    func popoverDidClose(_ notification: Notification) {
        if !station.settingsOpen {
            statusItem?.button?.setAccessibilityChildren(nil)
        }
        let keepSettings = station.settingsOpen
        station.panelVisible = false
        removeDismissMonitors()
        if keepSettings { return }
        station.pairing.stop()
        station.dialog = nil
        station.remoteControls.monitoring = false
        station.files.dismissPreview()
        station.page = .main
    }
}

extension PhoneStationApp: NSWindowDelegate {
    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow, window === settingsWindow else { return }
        station.settingsOpen = false
        if station.page != .main { station.page = .main }
    }
}
