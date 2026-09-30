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
        button.image = menuBarImage()
        button.imagePosition = .imageOnly
        button.toolTip = "手机工位"
        button.target = self
        button.action = #selector(togglePopover)
        statusItem = item

        let panel = NSPopover()
        panel.behavior = .transient
        panel.animates = false
        let host = NSHostingController(rootView: StationView(model: station))
        // Hug whichever page is showing. A fixed height leaves a gap under the shorter ones.
        host.sizingOptions = .preferredContentSize
        panel.contentSize = NSSize(width: 360, height: 420)
        panel.contentViewController = host
        popover = panel
    }

    /// Do not assign `size` on this image. A 12×12 box clips the symbol and the slot looks empty.
    private func menuBarImage() -> NSImage {
        let config = NSImage.SymbolConfiguration(pointSize: 16, weight: .medium)
        if let symbol = NSImage(systemSymbolName: "iphone", accessibilityDescription: "手机工位")?
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
            return true
        }
        image.isTemplate = true
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
            station.page = .main
        } else {
            station.refresh()
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            NSApplication.shared.activate(ignoringOtherApps: true)
        }
    }
}
