import CryptoKit
import Foundation
import Security

/// Link v2 device key. The app, gateway, and current channels do not call this.
struct DeviceKey {
    enum Origin: String {
        case secureEnclave = "secure-enclave"
        case software = "software"
    }

    enum Store: String {
        case login
        case file
    }

    let origin: Origin
    let store: Store
    /// Status from the Secure Enclave attempt. Zero when that key was created.
    let secureEnclaveStatus: OSStatus
    let label: String
    private let privateKey: SecKey
    private let tag: Data
    private let keychain: SecKeychain

    static func make(
        label: String,
        store: Store,
        keychain: SecKeychain,
        trySecureEnclave: Bool,
        secureEnclave: (_ label: String, _ tag: Data) -> (SecKey?, OSStatus) = DeviceKey.createSecureEnclaveKey
    ) throws -> DeviceKey {
        let tag = Data(label.utf8)
        if trySecureEnclave {
            let (key, status) = secureEnclave(label, tag)
            if let key {
                return DeviceKey(origin: .secureEnclave, store: store, secureEnclaveStatus: status, label: label, privateKey: key, tag: tag, keychain: keychain)
            }
            return try software(label: label, tag: tag, store: store, keychain: keychain, secureEnclaveStatus: status)
        }
        return try software(label: label, tag: tag, store: store, keychain: keychain, secureEnclaveStatus: errSecUnimplemented)
    }

    func sign(_ message: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, message as CFData, &error) as Data? else {
            throw DeviceKeyError.status("sign", DeviceKey.status(error))
        }
        return signature
    }

    func verifies(_ message: Data, signature: Data) -> Bool {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { return false }
        return SecKeyVerifySignature(publicKey, .ecdsaSignatureMessageX962SHA256, message as CFData, signature as CFData, nil)
    }

    func subjectPublicKeyInfo() throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { throw DeviceKeyError.status("public", errSecParam) }
        var error: Unmanaged<CFError>?
        guard let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?, point.count == 65, point.first == 0x04 else {
            throw DeviceKeyError.status("public", DeviceKey.status(error))
        }
        var encoded = Data([
            0x30, 0x59,
            0x30, 0x13,
            0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07,
            0x03, 0x42, 0x00,
        ])
        encoded.append(point)
        return encoded
    }

    static func deviceID(spki: Data) -> String {
        SHA256.hash(data: spki).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    func stored() -> Bool {
        var result: CFTypeRef?
        return SecItemCopyMatching(lookup() as CFDictionary, &result) == errSecSuccess
    }

    func delete() throws {
        guard label.contains("phone-station-selftest") else {
            throw DeviceKeyError.status("label", errSecParam)
        }
        let status = SecItemDelete(lookup() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DeviceKeyError.status("delete", status)
        }
    }

    static func deleteSelfTest(label: String, keychain: SecKeychain) throws {
        guard label.hasPrefix("phone-station-selftest-"), label.count >= "phone-station-selftest-".count + 36 else {
            throw DeviceKeyError.status("label", errSecParam)
        }
        let tag = Data(label.utf8)
        let queries: [[String: Any]] = [
            secureLookup(tag: tag, label: label),
            softwareLookup(tag: tag, label: label, keychain: keychain),
        ]
        for query in queries {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw DeviceKeyError.status("delete", status)
            }
        }
    }

    static func createSecureEnclaveKey(label: String, tag: Data) -> (SecKey?, OSStatus) {
        var error: Unmanaged<CFError>?
        guard let control = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, [.privateKeyUsage], &error) else {
            return (nil, status(error))
        }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrLabel as String: label,
                kSecAttrAccessControl as String: control,
            ] as [String: Any],
        ]
        error = nil
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            return (nil, status(error))
        }
        return (key, errSecSuccess)
    }

    private static func software(label: String, tag: Data, store: Store, keychain: SecKeychain, secureEnclaveStatus: OSStatus) throws -> DeviceKey {
        var accessError: Unmanaged<CFError>?
        guard let access = SecAccessCreateWithOwnerAndACL(getuid(), 0, SecAccessOwnerType(kSecUseOnlyUID), nil, &accessError) else {
            throw DeviceKeyError.status("access", status(accessError))
        }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecUseDataProtectionKeychain as String: false,
            kSecUseKeychain as String: keychain,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrIsExtractable as String: false,
                kSecAttrApplicationTag as String: tag,
                kSecAttrLabel as String: label,
                kSecAttrAccess as String: access,
            ] as [String: Any],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw DeviceKeyError.status("software", status(error))
        }
        return DeviceKey(origin: .software, store: store, secureEnclaveStatus: secureEnclaveStatus, label: label, privateKey: key, tag: tag, keychain: keychain)
    }

    private func lookup() -> [String: Any] {
        if origin == .secureEnclave {
            return DeviceKey.secureLookup(tag: tag, label: label)
        }
        return DeviceKey.softwareLookup(tag: tag, label: label, keychain: keychain)
    }

    private static func secureLookup(tag: Data, label: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecAttrApplicationTag as String: tag,
            kSecAttrLabel as String: label,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
    }

    private static func softwareLookup(tag: Data, label: String, keychain: SecKeychain) -> [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag as String: tag,
            kSecAttrLabel as String: label,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: false,
            kSecUseKeychain as String: keychain,
            kSecMatchSearchList as String: [keychain] as CFArray,
        ]
    }

    static func status(_ error: Unmanaged<CFError>?) -> OSStatus {
        guard let error else { return errSecParam }
        return OSStatus((error.takeRetainedValue() as Error as NSError).code)
    }
}

enum DeviceKeyError: Error, CustomStringConvertible {
    case status(String, OSStatus)

    var description: String {
        switch self {
        case .status(let operation, let status):
            return "\(operation) \(status)"
        }
    }
}
