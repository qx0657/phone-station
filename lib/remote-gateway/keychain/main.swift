import Foundation
import Security

private let service = "com.qx0657.phone-station.remote-relay"
private let account: String = {
    guard (2...3).contains(CommandLine.arguments.count) else {
        fail("usage: phone-relay-keychain get|set|delete [profile-id]")
    }
    guard CommandLine.arguments.count == 3 else { return "desktop-bearer" }
    let profile = CommandLine.arguments[2]
    guard profile.range(of: "^[0-9a-f]{16}$", options: .regularExpression) != nil else {
        fail("invalid remote profile id")
    }
    return "desktop-bearer-\(profile)"
}()

func fail(_ message: String, _ status: OSStatus? = nil) -> Never {
    if let status {
        fputs("\(message) (Keychain status \(status))\n", stderr)
    } else {
        fputs("\(message)\n", stderr)
    }
    exit(1)
}

func baseQuery() -> [String: Any] {
    [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account,
    ]
}

_ = account

switch CommandLine.arguments[1] {
case "get":
    var query = baseQuery()
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else {
        fail("remote credential unavailable", status)
    }
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data([0x0a]))

case "set":
    let data = FileHandle.standardInput.readDataToEndOfFile()
    guard let token = String(data: data, encoding: .utf8),
          token.trimmingCharacters(in: .whitespacesAndNewlines).range(
            of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil else {
        fail("invalid remote credential")
    }
    let value = Data(token.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().utf8)
    let query = baseQuery()
    let update: [String: Any] = [kSecValueData as String: value]
    let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
    if status == errSecItemNotFound {
        var item = query
        item[kSecValueData as String] = value
        // The ad-hoc-signed CLI helper uses the user's macOS login keychain.
        // The Data Protection keychain requires an access-group entitlement.
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { fail("cannot store remote credential", added) }
    } else if status != errSecSuccess {
        fail("cannot update remote credential", status)
    }

case "delete":
    let status = SecItemDelete(baseQuery() as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else {
        fail("cannot remove remote credential", status)
    }

default:
    fail("usage: phone-relay-keychain get|set|delete")
}
