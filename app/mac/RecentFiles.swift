import AppKit
import AVFoundation
import ImageIO

enum RecentFileStyle {
    static var date: DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: StationL10n.english() ? "en" : "zh-Hans")
        formatter.setLocalizedDateFormatFromTemplate("MMMdHHmm")
        return formatter
    }

    static func kind(for url: URL) -> String {
        ["mp4", "mkv"].contains(url.pathExtension.lowercased()) ? "录屏" : "截图"
    }
}

enum RecentFileThumbnail {
    /// Small enough for the row and the hover card. Built off the main thread.
    nonisolated static func cgImage(for url: URL, maxPixel: Int) -> CGImage? {
        let ext = url.pathExtension.lowercased()
        if ext == "mp4" || ext == "mkv" {
            return videoFrame(url, maxPixel: maxPixel)
        }
        return stillImage(url, maxPixel: maxPixel)
    }

    private nonisolated static func stillImage(_ url: URL, maxPixel: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    private nonisolated static func videoFrame(_ url: URL, maxPixel: Int) -> CGImage? {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixel, height: maxPixel)
        for time in [CMTime(seconds: 0.2, preferredTimescale: 600), .zero] {
            if let image = try? generator.copyCGImage(at: time, actualTime: nil) {
                return image
            }
        }
        return nil
    }
}

/// Hover preview sits beside the menu panel. The panel is 360pt wide; a phone
/// screenshot drawn inside it would cover the row and change the panel height.
final class RecentPreviewPanel: NSPanel {
    private let body = RecentPreviewContent()

    init() {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 180, height: 240),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false)
        contentView = body
        isFloatingPanel = true
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        level = .popUpMenu
        animationBehavior = .none
        isMovable = false
        isReleasedWhenClosed = false
        hidesOnDeactivate = false
        ignoresMouseEvents = true
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
    }

    func present(name: String, image: NSImage?, row: NSRect, host: NSWindow) {
        body.caption.stringValue = name
        body.imageView.image = image
        let size = body.preferredSize(for: image)
        let frame = Self.frame(size: size, row: row, host: host.frame)
        setFrame(frame, display: true)
        if parent !== host {
            parent?.removeChildWindow(self)
            host.addChildWindow(self, ordered: .above)
        }
        orderFrontRegardless()
        invalidateShadow()
    }

    func hide() {
        parent?.removeChildWindow(self)
        orderOut(nil)
    }

    private static func frame(size: NSSize, row: NSRect, host: NSRect) -> NSRect {
        let gap: CGFloat = 8
        let screen = NSScreen.screens.first { $0.frame.contains(NSPoint(x: row.midX, y: row.midY)) }
            ?? NSScreen.main
        let limit = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        var x = host.minX - gap - size.width
        if x < limit.minX + 8 {
            x = host.maxX + gap
        }
        if x + size.width > limit.maxX - 8 {
            x = max(limit.minX + 8, limit.maxX - 8 - size.width)
        }
        var y = row.midY - size.height / 2
        y = min(max(y, limit.minY + 8), limit.maxY - 8 - size.height)
        return NSRect(x: x, y: y, width: size.width, height: size.height)
    }
}

private final class RecentPreviewContent: NSView {
    let imageView = NSImageView()
    let caption = NSTextField(labelWithString: "")

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.cornerRadius = 12
        layer?.cornerCurve = .continuous
        layer?.masksToBounds = true
        imageView.imageScaling = .scaleProportionallyUpOrDown
        imageView.wantsLayer = true
        imageView.layer?.cornerRadius = 8
        imageView.layer?.cornerCurve = .continuous
        imageView.layer?.masksToBounds = true
        caption.font = .systemFont(ofSize: 11)
        caption.textColor = .secondaryLabelColor
        caption.alignment = .center
        caption.lineBreakMode = .byTruncatingMiddle
        caption.maximumNumberOfLines = 1
        addSubview(imageView)
        addSubview(caption)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        applyChrome()
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        applyChrome()
    }

    func preferredSize(for image: NSImage?) -> NSSize {
        let fitted = Self.fit(image?.size ?? NSSize(width: 160, height: 240))
        return NSSize(width: fitted.width + 16, height: fitted.height + 16 + 6 + 16)
    }

    override func layout() {
        super.layout()
        let bounds = bounds.insetBy(dx: 8, dy: 8)
        caption.frame = NSRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: 16)
        let top = caption.frame.maxY + 6
        imageView.frame = NSRect(x: bounds.minX, y: top, width: bounds.width, height: bounds.maxY - top)
    }

    private func applyChrome() {
        let dark = effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let fill = dark
            ? NSColor(srgbRed: 50 / 255, green: 50 / 255, blue: 52 / 255, alpha: 1)
            : NSColor.white
        layer?.backgroundColor = fill.cgColor
        layer?.borderWidth = 1
        layer?.borderColor = NSColor.labelColor.withAlphaComponent(0.08).cgColor
    }

    private static func fit(_ size: NSSize) -> NSSize {
        let width = max(size.width, 1)
        let height = max(size.height, 1)
        let scale = min(240 / width, 320 / height)
        return NSSize(width: (width * scale).rounded(), height: (height * scale).rounded())
    }
}
