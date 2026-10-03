import Foundation
import AppKit

@main enum NotificationBannerTest {
    @MainActor static func main() {
        let now = Date(timeIntervalSince1970: 100)
        var queue = NotificationBannerQueue()
        func event(_ key: String, _ text: String = "message") -> PhoneNotification {
            PhoneNotification(id: key + text, key: key, packageName: "chat", app: "Chat", title: "title", text: text)
        }
        queue.show(event("1"), now: now)
        queue.show(event("1", "updated"), now: now.addingTimeInterval(1))
        expect(queue.entries.count == 1 && queue.entries[0].event.text == "updated", "same phone notification updates in place")
        queue.hover(event("1").sourceID, on: true, now: now)
        queue.expire(now: now.addingTimeInterval(30))
        expect(queue.entries.count == 1, "hover keeps notification readable")
        queue.hover(event("1").sourceID, on: false, now: now.addingTimeInterval(30))
        queue.expire(now: now.addingTimeInterval(35))
        expect(queue.entries.count == 1, "leaving hover grants six seconds")
        queue.expire(now: now.addingTimeInterval(36))
        expect(queue.entries.isEmpty, "auto-dismiss releases text")
        for i in 1...4 { queue.show(event("\(i)"), now: now) }
        expect(queue.entries.count == 3 && queue.entries.last?.event.key == "2", "bursts keep newest three")
        queue.dismiss(event("3").sourceID)
        expect(queue.entries.count == 2, "close one banner")
        queue.clear()
        expect(queue.entries.isEmpty, "pause and disconnect purge banners")
        expect(event("same").deliveryID == event("same", "changed").deliveryID, "system history updates the same source")
        let missing = try! JSONDecoder().decode(NotificationIcon.self, from: Data("{\"available\":false}".utf8))
        expect(missing.png == nil, "missing icon degrades safely")
        let invalid = NotificationIcon(available: true, packageName: "chat", app: "Chat", mimeType: "image/png", base64: "not an image")
        expect(invalid.png == nil, "invalid icon is rejected")
        checkMaterialMask()
        print("Mac 横幅替换、数量上限、悬停、到期、关闭、图标与圆角透明边界通过")
    }
    @MainActor static func checkMaterialMask() {
        // Rasterize the production stretchable mask at both display scales.
        // Exposed square corners are the regression reported on the real panel.
        for scale in [1, 2] {
            let width = 360 * scale, height = 102 * scale
            let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
                bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            bitmap.size = NSSize(width: 360, height: 102)
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
            NotificationBannerSurface.materialMask.draw(in: NSRect(x: 0, y: 0, width: 360, height: 102))
            NSGraphicsContext.restoreGraphicsState()
            for x in [0, width - 1] {
                for y in [0, height - 1] {
                    expect(bitmap.colorAt(x: x, y: y)!.alphaComponent < 0.01, "material corners stay transparent")
                }
            }
            expect(bitmap.colorAt(x: width / 2, y: height / 2)!.alphaComponent > 0.99, "material interior stays solid")
            expect(bitmap.colorAt(x: width / 2, y: 0)!.alphaComponent > 0.99, "straight edge remains covered")
        }
    }
    static func expect(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
}
