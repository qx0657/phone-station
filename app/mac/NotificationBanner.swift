import AppKit
import SwiftUI
import ImageIO

struct NotificationBannerView: View {
    let event: PhoneNotification
    var dismiss: () -> Void
    var open: () -> Void
    var hover: (Bool) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Button(action: open) {
                HStack(alignment: .top, spacing: 12) {
                    Group {
                        if let icon = NotificationBannerImages.decode(event.iconPNG) {
                            Image(nsImage: icon).resizable().interpolation(.high)
                        } else {
                            Image(systemName: "app.fill").resizable().foregroundStyle(.secondary)
                        }
                    }.scaledToFit().frame(width: 42, height: 42).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(event.app) · 手机").font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
                            .lineLimit(1)
                        Text(event.title.isEmpty ? event.app : event.title)
                            .font(.system(size: 14, weight: .semibold)).lineLimit(2)
                        if !event.text.isEmpty {
                            Text(event.text).font(.system(size: 13)).lineSpacing(2).lineLimit(3)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityHint("打开手机通知设置")
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.secondary).frame(width: 24, height: 24).contentShape(Rectangle())
            }.buttonStyle(.plain).help("关闭这条横幅").accessibilityLabel("关闭这条横幅")
        }.padding(14).onHover(perform: hover)
    }
}

enum NotificationBannerImages {
    static func decode(_ encoded: String?) -> NSImage? {
        guard let encoded, encoded.utf8.count <= 87_384, let data = Data(base64Encoded: encoded),
              data.count <= 65_536, data.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 256, height <= 256,
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: width, height: height))
    }
}

@MainActor
enum NotificationBannerSurface {
    static let cornerRadius: CGFloat = 12
    // CALayer clipping does not mask a behind-window material. AppKit also uses
    // this alpha mask for the window shadow, so both follow the same silhouette.
    static let materialMask: NSImage = {
        let side = cornerRadius * 2 + 1
        let image = NSImage(size: NSSize(width: side, height: side), flipped: false) { bounds in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: bounds, xRadius: cornerRadius, yRadius: cornerRadius).fill()
            return true
        }
        image.capInsets = NSEdgeInsets(top: cornerRadius, left: cornerRadius,
                                      bottom: cornerRadius, right: cornerRadius)
        image.resizingMode = .stretch
        return image
    }()

    static func make<Content: View>(host: NSHostingView<Content>, size: NSSize) -> NSView {
        host.wantsLayer = true
        host.layer?.backgroundColor = NSColor.clear.cgColor
        host.layer?.cornerRadius = cornerRadius
        host.layer?.masksToBounds = true
        host.frame = NSRect(origin: .zero, size: size)
        host.autoresizingMask = [.width, .height]
        if #available(macOS 26.0, *) {
            // Make native clear glass the window surface itself. A frosted
            // material beneath it occludes the background before glass samples
            // it, and reducing the glass alpha also loses its optical response.
            let glass = NSGlassEffectView(frame: host.frame)
            glass.style = .clear
            glass.cornerRadius = cornerRadius
            glass.contentView = host
            return glass
        }
        return makeLegacy(host: host, size: size)
    }

    static func makeLegacy<Content: View>(host: NSHostingView<Content>, size: NSSize) -> NSVisualEffectView {
        let material = NSVisualEffectView(frame: NSRect(origin: .zero, size: size))
        material.material = .popover
        material.blendingMode = .behindWindow
        material.state = .active
        material.maskImage = materialMask
        material.wantsLayer = true
        material.layer?.backgroundColor = NSColor.clear.cgColor
        material.layer?.cornerRadius = cornerRadius
        material.layer?.masksToBounds = true
        // maskImage masks the material, not its subviews. Clip the hosting layer
        // separately and keep its backing transparent at all four corners.
        material.addSubview(host)
        return material
    }
}

private final class NotificationBannerPanel: NSPanel {
    override var canBecomeMain: Bool { false }
    override var canBecomeKey: Bool { true }
}

@MainActor
final class NotificationBannerCenter {
    static let shared = NotificationBannerCenter()
    var onOpen: (() -> Void)?
    private var queue = NotificationBannerQueue()
    private var panels: [String: NSPanel] = [:]
    private var timer: Timer?
    private var screen: NSScreen?
    private var observers: [NSObjectProtocol] = []
    private var displayAwake = true
    private var sessionActive = true

    init() {
        let workspace = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            observers.append(workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                MainActor.assumeIsolated {
                    if note.name == NSWorkspace.screensDidSleepNotification { self?.displayAwake = false }
                    else { self?.sessionActive = false }
                    self?.dismissAll()
                }
            })
        }
        for name in [NSWorkspace.screensDidWakeNotification, NSWorkspace.sessionDidBecomeActiveNotification] {
            observers.append(workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                MainActor.assumeIsolated {
                    if note.name == NSWorkspace.screensDidWakeNotification { self?.displayAwake = true }
                    else { self?.sessionActive = true }
                }
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification,
            object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.dismissAll() } })
    }

    @discardableResult func show(_ event: PhoneNotification) -> Bool {
        guard displayAwake, sessionActive else { return false }
        screen = NSScreen.screens.first(where: { $0.frame.contains(NSEvent.mouseLocation) }) ?? NSScreen.main
        guard screen != nil else { return false }
        queue.show(event, now: Date())
        render()
        if timer == nil {
            let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let before = self.queue.entries.count
                    self.queue.expire(now: Date())
                    if self.queue.entries.count != before { self.render() }
                }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        }
        return true
    }

    func dismissAll() { queue.clear(); render() }

    private func dismiss(_ id: String) { queue.dismiss(id); render() }
    private func render() {
        let ids = Set(queue.entries.map { $0.event.sourceID })
        for id in Array(panels.keys) where !ids.contains(id) {
            panels.removeValue(forKey: id)?.close()
        }
        guard !queue.entries.isEmpty, let screen else { timer?.invalidate(); timer = nil; return }
        let visible = screen.visibleFrame
        let width = min(360, max(240, visible.width - 32))
        var top = visible.maxY - 12
        for entry in queue.entries {
            let event = entry.event
            let id = event.sourceID
            let existing = panels[id]
            let panel = existing ?? makePanel()
            let root = NotificationBannerView(event: event,
                dismiss: { [weak self] in self?.dismiss(id) },
                open: { [weak self] in self?.dismiss(id); self?.onOpen?() },
                hover: { [weak self] on in self?.queue.hover(id, on: on, now: Date()) })
                .frame(width: width)
            let host = NSHostingView(rootView: root)
            let height = max(94, host.fittingSize.height)
            panel.contentView = NotificationBannerSurface.make(host: host, size: NSSize(width: width, height: height))
            let frame = NSRect(x: visible.maxX - width - 16, y: top - height, width: width, height: height)
            top = frame.minY - 8
            panel.setFrame(frame, display: true)
            panel.invalidateShadow()
            if frame.minY < visible.minY + 12 {
                panel.orderOut(nil)
                continue
            }
            if existing == nil {
                panels[id] = panel
                panel.orderFrontRegardless()
                if !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion {
                    panel.setFrameOrigin(NSPoint(x: frame.origin.x + 12, y: frame.origin.y))
                    NSAnimationContext.runAnimationGroup { context in
                        context.duration = 0.18
                        panel.animator().setFrameOrigin(frame.origin)
                    }
                }
            } else if !panel.isVisible { panel.orderFrontRegardless() }
        }
    }
    private func makePanel() -> NSPanel {
        let panel = NotificationBannerPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered, defer: false)
        panel.title = "手机通知横幅"
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .statusBar
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient, .ignoresCycle]
        return panel
    }
}
