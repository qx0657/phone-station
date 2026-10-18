import Foundation

@main
enum StationInviteTest {
    static func main() {
        let item = StationInvite(
            endpoint: "https://192.0.2.8:24443",
            pin: "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
            id: "11111111111111111111111111111111",
            key: "22222222222222222222222222222222",
            spki: "AQID",
            kind: "phone")
        guard let parsed = StationInvite.parse(item.text), parsed == item else { fatalError("round trip") }
        guard StationInvite.parse(item.text + "\n") == nil else { fatalError("extra") }
        guard StationInvite.manual(id: item.id, key: item.key) == "111111222222" else { fatalError("manual") }
        guard StationInvite.explain("111111222222").contains("格式正确") else { fatalError("explain") }
        guard StationInvite.explain("11111122222G").contains("12 位") else { fatalError("bad") }
        print("station invite ok")
    }
}
