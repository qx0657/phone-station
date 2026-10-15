import Foundation

@main
enum PairingPromptTest {
    static func main() {
        var step = 0
        let code = WirelessPairingCode.make { step += 1; return UInt8(step) }
        precondition(code.name == "studio-bcdefghijk")
        precondition(code.password == "lmnopqrstuvw")
        precondition(code.payload == "WIFI:T:ADB;S:studio-bcdefghijk;P:lmnopqrstuvw;;")

        let unpaired = """
        failed to connect to 192.168.0.101:37547
        没连上。若是还没配对：scripts/pair-qr.sh
        """
        precondition(WirelessConnectFailure.needsQrPairing(unpaired))
        precondition(!WirelessConnectFailure.needsQrPairing("没有可连接的无线地址。USB 接上并允许调试即可使用。"))
        precondition(!WirelessConnectFailure.needsQrPairing("跳过 172.19.0.1（路由走 utun0）\n没连上。"))
        precondition(WirelessConnectFailure.needsQrPairing("跳过 172.19.0.1（路由走 utun0）\nfailed to connect to 192.168.0.101:1\n没连上。"))
        precondition(!WirelessConnectFailure.needsQrPairing("failed to connect to '192.168.0.101:37547': No route to host\n没连上。"))
        precondition(!WirelessConnectFailure.needsQrPairing("connected to 192.168.0.101:37547"))
        print("pairing prompt ok")
    }
}
