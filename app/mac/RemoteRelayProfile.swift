import Foundation

struct RemoteRelayProfile: Codable, Equatable {
    var endpoint = ""
    var pin = ""
    var configured = false
}

struct RemoteRelayDraft {
    var endpoint = ""
    var pin = ""
    var phoneToken = ""
    var desktopToken = ""

    var normalized: RemoteRelayDraft {
        RemoteRelayDraft(endpoint: endpoint.trimmingCharacters(in: .whitespacesAndNewlines),
                         pin: pin.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
                         phoneToken: phoneToken.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
                         desktopToken: desktopToken.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }

    var validationMessage: String? { validationMessage(configurePhone: true) }

    func validationMessage(configurePhone: Bool) -> String? {
        let value = normalized
        guard value.endpoint.hasPrefix("https://") else { return "中继地址要以 https:// 开头。" }
        guard Self.isCredential(value.pin) else { return "证书指纹要填 64 位十六进制的 SPKI SHA-256。" }
        guard Self.isCredential(value.desktopToken) else { return "电脑令牌需要 64 位十六进制字符。" }
        if configurePhone {
            guard Self.isCredential(value.phoneToken) else { return "手机令牌需要 64 位十六进制字符。" }
            guard value.phoneToken != value.desktopToken else { return "手机和电脑要用两枚不同的令牌。" }
        }
        return nil
    }

    var credentialInput: Data {
        Data("\(normalized.phoneToken)\n\(normalized.desktopToken)\n".utf8)
    }

    var desktopCredentialInput: Data { Data("\(normalized.desktopToken)\n".utf8) }

    private static func isCredential(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy {
            (48...57).contains($0) || (97...102).contains($0)
        }
    }
}
