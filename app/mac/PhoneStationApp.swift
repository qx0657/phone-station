import AppKit
import OSLog
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
    private let station = StationModel()
    private var statusItem: NSStatusItem?
    private var popover: NSPopover?
    private var menuBarShowsConnected: Bool?
    /// Clicks in other apps never reach an `LSUIElement` popover, so `.transient` stays open.
    private var outsideClickMonitor: Any?
    private var localDismissMonitor: Any?

    static func main() {
        let app = NSApplication.shared
        app.delegate = retainedDelegate
        app.setActivationPolicy(.accessory)
        app.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        repairPreferredPositionIfNeeded()
        installStatusItem()
        schedulePlacementLog()
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
        statusItem = item
        station.onLinkChange = { [weak self] connected in
            self?.applyMenuBar(connected: connected)
        }
        applyMenuBar(connected: false)

        let panel = NSPopover()
        panel.behavior = .transient
        panel.animates = false
        panel.delegate = self
        let host = NSHostingController(rootView: StationView(model: station))
        // Hug whichever page is showing. A fixed height leaves a gap under the shorter ones.
        host.sizingOptions = .preferredContentSize
        panel.contentSize = NSSize(width: 360, height: 420)
        panel.contentViewController = host
        popover = panel
        station.startLinkWatch()
    }

    private func applyMenuBar(connected: Bool) {
        guard let button = statusItem?.button else { return }
        if menuBarShowsConnected != connected {
            menuBarShowsConnected = connected
            button.image = menuBarImage(connected: connected)
            logger.notice("Menu bar icon tracks link, connected: \(connected, privacy: .public), status: \(self.station.statusLabel, privacy: .public)")
        }
        let tip = "手机工位：\(station.statusLabel)"
        if button.toolTip != tip {
            button.toolTip = tip
        }
    }

    /// Do not assign `size` on this image. A 12×12 box clips the symbol and the slot looks empty.
    /// Both symbols share a point size so the status item does not jump when the link changes.
    private func menuBarImage(connected: Bool) -> NSImage {
        let symbolName = connected ? "iphone" : "iphone.slash"
        let description = connected ? "手机已连接" : "手机未连接"
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
        guard let button = statusItem?.button, let popover else { return }
        if popover.isShown {
            popover.performClose(nil)
        } else {
            station.refresh()
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            NSApplication.shared.activate(ignoringOtherApps: true)
        }
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
        installDismissMonitors()
    }

    func popoverDidClose(_ notification: Notification) {
        removeDismissMonitors()
        station.dismissRecentPreview()
        station.page = .main
    }
}
