import AppKit
import Foundation

guard CommandLine.arguments.count == 2 else {
    fputs("用法: MakeIcon <输出.png>\n", stderr)
    exit(1)
}

let side = 1024
guard let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
    isPlanar: false, colorSpaceName: .deviceRGB,
    bytesPerRow: 0, bitsPerPixel: 0
), let context = NSGraphicsContext(bitmapImageRep: bitmap) else {
    fputs("无法创建图标画布。\n", stderr)
    exit(1)
}

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
context.imageInterpolation = .high
NSColor.clear.setFill()
NSRect(x: 0, y: 0, width: side, height: side).fill()

let background = NSBezierPath(roundedRect: NSRect(x: 64, y: 64, width: 896, height: 896),
                              xRadius: 208, yRadius: 208)
NSColor(calibratedRed: 0.12, green: 0.37, blue: 0.79, alpha: 1).setFill()
background.fill()

let phone = NSBezierPath(roundedRect: NSRect(x: 335, y: 140, width: 354, height: 744),
                         xRadius: 61, yRadius: 61)
NSColor.white.setFill()
phone.fill()

let screen = NSBezierPath(roundedRect: NSRect(x: 358, y: 221, width: 308, height: 573),
                          xRadius: 25, yRadius: 25)
NSColor(calibratedRed: 0.07, green: 0.27, blue: 0.63, alpha: 1).setFill()
screen.fill()

let speaker = NSBezierPath(roundedRect: NSRect(x: 466, y: 825, width: 92, height: 13),
                           xRadius: 7, yRadius: 7)
NSColor(calibratedRed: 0.20, green: 0.43, blue: 0.78, alpha: 1).setFill()
speaker.fill()

let homeIndicator = NSBezierPath(roundedRect: NSRect(x: 467, y: 178, width: 90, height: 12),
                                 xRadius: 6, yRadius: 6)
NSColor(calibratedRed: 0.20, green: 0.43, blue: 0.78, alpha: 1).setFill()
homeIndicator.fill()

NSGraphicsContext.restoreGraphicsState()

guard let data = bitmap.representation(using: .png, properties: [:]) else {
    fputs("无法编码图标。\n", stderr)
    exit(1)
}
try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
