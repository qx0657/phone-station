import Foundation

/// 工位邀请文本。无线调试的 WIFI:T:ADB 二维码不走这里。
struct StationInvite: Equatable {
    var endpoint: String
    var pin: String
    var id: String
    var key: String
    var spki: String
    var kind: String

    var text: String {
        "ps-invite-v1\n\(endpoint)\n\(pin)\n\(id)\n\(key)\n\(spki)\n\(kind)\n"
    }

    static func parse(_ text: String) -> StationInvite? {
        guard text.count <= 1200, text.hasSuffix("\n") else { return nil }
        let lines = text.dropLast().split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.count == 7, lines[0] == "ps-invite-v1" else { return nil }
        let item = StationInvite(endpoint: lines[1], pin: lines[2], id: lines[3], key: lines[4], spki: lines[5], kind: lines[6])
        return item.valid ? item : nil
    }

    static func manual(id: String, key: String) -> String? {
        guard hex(id, 32), hex(key, 32) else { return nil }
        return String(id.prefix(6) + key.prefix(6))
    }

    static func explain(_ code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 12, trimmed.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else {
            return "邀请码是 12 位小写十六进制。"
        }
        return "邀请码格式正确。加入前要在手机上比对 6 位安全码。中继 6 还没部署，这一页不拨公网。"
    }

    private var valid: Bool {
        endpoint.hasPrefix("https://") && endpoint.count >= "https://a".count && endpoint.count <= 200
            && endpoint.unicodeScalars.allSatisfy { $0.value > 32 }
            && Self.hex(pin, 64) && Self.hex(id, 32) && Self.hex(key, 32)
            && (kind == "phone" || kind == "mac") && !spki.isEmpty && spki.count <= 512
            && spki.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "+" || $0 == "/" || $0 == "=" }
    }

    private static func hex(_ value: String, _ count: Int) -> Bool {
        value.count == count && value.unicodeScalars.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }
}
