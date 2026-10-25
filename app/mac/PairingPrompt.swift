import Foundation

/// 无线调试二维码的内容。格式与 Android「使用二维码配对设备」一致。
struct WirelessPairingCode: Equatable {
    var name: String
    var password: String
    var payload: String { "WIFI:T:ADB;S:\(name);P:\(password);;" }

    static let alphabet = Array("abcdefghijklmnopqrstuvwxyz0123456789")

    static func make(draw: () -> UInt8 = { UInt8.random(in: 0...255) }) -> WirelessPairingCode {
        func token(_ count: Int) -> String {
            String((0..<count).map { _ in alphabet[Int(draw()) % alphabet.count] })
        }
        return WirelessPairingCode(name: "studio-" + token(10), password: token(12))
    }
}

/// 只有「已经看到无线调试、但这台电脑还没配对」才出示二维码。
enum WirelessConnectFailure {
    static func needsQrPairing(_ output: String) -> Bool {
        if output.contains("没有可连接的无线地址") { return false }
        let transport = ["No route to host", "Network is unreachable", "Connection refused",
                         "Connection timed out", "protocol fault"]
        return output.split(separator: "\n").contains { line in
            line.contains("failed to connect") && !transport.contains { line.contains($0) }
        }
    }

    /// 这次打开后已经连上过，或本机留有无线配对记录时，同样的失败只说明这条端口暂时连不上。
    static func pausesAutomaticReconnect(_ output: String, alreadyPaired: Bool) -> Bool {
        !alreadyPaired && needsQrPairing(output)
    }

    static func rememberedWirelessPeerPath(home: String = NSHomeDirectory()) -> String {
        (home as NSString).appendingPathComponent(".android/adb_known_hosts.pb")
    }

    /// 只看配对记录在不在，不读取里面的设备。
    static func hasRememberedWirelessPeer(at path: String = rememberedWirelessPeerPath(), fileManager: FileManager = .default) -> Bool {
        guard let size = (try? fileManager.attributesOfItem(atPath: path))?[.size] as? NSNumber else { return false }
        return size.int64Value > 0
    }
}
