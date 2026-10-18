import Foundation

/// 拖放和分享的路径规则，与 `lib/link/share.go` 一致。发送走网关的 `/v2/share`。
enum FileShare {
    static let phoneHome = "/storage/emulated/0"
    static let maxDropBytes = 128 * 1024

    static func shareURL(endpoint: String) -> URL? {
        guard var parts = URLComponents(string: endpoint),
              parts.scheme == "http" || parts.scheme == "https" else { return nil }
        parts.path = "/v2/share"
        parts.query = nil
        parts.fragment = nil
        return parts.url
    }

    static func send(name: String, data: Data, endpoint: String, token: String) async -> Bool {
        guard phoneDownload(name) != nil, data.count <= maxDropBytes, shareText(name),
              let url = shareURL(endpoint: endpoint), !token.isEmpty else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(name, forHTTPHeaderField: "Phone-Station-Name")
        request.httpBody = data
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 204
        } catch {
            return false
        }
    }

    private static func shareText(_ name: String) -> Bool {
        !name.isEmpty && name.unicodeScalars.allSatisfy { scalar in
            let value = scalar.value
            return value >= 0x20 && value <= 0x7e && scalar != "\"" && scalar != "\\"
        }
    }

    static func phoneDownload(_ name: String) -> String? {
        guard fileName(name) else { return nil }
        return phoneHome + "/Download/" + name
    }

    static func macName(_ phonePath: String) -> String? {
        guard let normal = phoneFile(phonePath) else { return nil }
        return (normal as NSString).lastPathComponent
    }

    private static func fileName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".."
            && !name.contains("/") && !name.contains("\\") && !name.contains("\0")
    }

    private static func phoneFile(_ input: String) -> String? {
        if input.isEmpty || input.contains("\0") || input.contains("\\") { return nil }
        let normal = (input as NSString).standardizingPath
        let prefix = phoneHome + "/"
        guard normal.hasPrefix(prefix), normal != phoneHome else { return nil }
        let rel = String(normal.dropFirst(prefix.count))
        if rel == "Android" || rel.hasPrefix("Android/") { return nil }
        let base = (normal as NSString).lastPathComponent
        if base == "." || base == "/" || base.isEmpty { return nil }
        return normal
    }
}
