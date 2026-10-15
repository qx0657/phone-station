import AppKit
import CoreImage
import Foundation

/// 在连接页出示二维码，并等待手机扫码后完成 adb pair。
@MainActor
final class WirelessPairingSession: ObservableObject {
    enum Phase: Equatable {
        case idle
        case waiting
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var image: NSImage?
    var onPaired: () -> Void = {}

    private var generation = 0
    private var process: Process?

    func startIfIdle() {
        if case .waiting = phase { return }
        start()
    }

    func start() {
        stopProcess()
        generation += 1
        let token = generation
        let code = WirelessPairingCode.make()
        image = WirelessPairingImage.make(code.payload)
        guard let python = StationRunner.executable("python3") else {
            phase = .failed("找不到 python3，无法等待手机扫描。")
            return
        }
        let script = Bundle.main.resourceURL!
            .appendingPathComponent("phone-station/lib/pair_qr.py")
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("phone-pair-\(UUID().uuidString).log")
        FileManager.default.createFile(atPath: log.path, contents: nil)
        guard let writer = try? FileHandle(forWritingTo: log) else {
            phase = .failed("配对没有完成。重新生成二维码后再扫描。")
            return
        }
        let input = Pipe()
        let process = Process()
        process.executableURL = python
        process.arguments = [script.path, "listen", code.name]
        process.environment = StationRunner.environment
        process.standardInput = input
        process.standardOutput = writer
        process.standardError = writer
        process.terminationHandler = { [weak self] process in
            let output = (try? String(contentsOf: log, encoding: .utf8)) ?? ""
            try? FileManager.default.removeItem(at: log)
            Task { @MainActor in
                self?.finished(token: token, code: process.terminationStatus, output: output)
            }
        }
        phase = .waiting
        if let data = (code.password + "\n").data(using: .utf8) {
            input.fileHandleForWriting.write(data)
        }
        try? input.fileHandleForWriting.close()
        do {
            try process.run()
        } catch {
            try? writer.close()
            phase = .failed("配对没有完成。重新生成二维码后再扫描。")
            return
        }
        try? writer.close()
        self.process = process
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 200_000_000_000)
            self?.expire(token)
        }
    }

    func stop() {
        generation += 1
        stopProcess()
        phase = .idle
        image = nil
    }

    private func expire(_ token: Int) {
        guard token == generation, case .waiting = phase else { return }
        stopProcess()
    }

    private func finished(token: Int, code: Int32, output: String) {
        guard token == generation else { return }
        process = nil
        if code == 0 || output.contains("Successfully paired") {
            phase = .idle
            image = nil
            onPaired()
            return
        }
        phase = .failed(Self.failureText(output))
    }

    private func stopProcess() {
        guard let process else { return }
        self.process = nil
        guard process.isRunning else { return }
        let pid = process.processIdentifier
        if getpgid(pid) == pid && pid != getpgrp() {
            kill(-pid, SIGTERM)
        } else {
            process.terminate()
        }
    }

    private static func failureText(_ output: String) -> String {
        if output.contains("没有扫到") || output.contains("配对参数无效") {
            return "二维码已失效。重新生成后，再用手机扫描。"
        }
        if output.contains("没有可用的局域网地址") {
            return "没有可用的局域网地址。请让手机和这台电脑连接同一个 Wi-Fi。"
        }
        return "配对没有完成。重新生成二维码后再扫描。"
    }
}

enum WirelessPairingImage {
    static func make(_ payload: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(payload.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = 336 / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: NSSize(width: 168, height: 168))
        image.addRepresentation(rep)
        return image
    }
}
