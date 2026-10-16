import Foundation
import Security

@main
enum DeviceKeyTest {
    static func main() {
        let arguments = Array(CommandLine.arguments.dropFirst())
        if arguments.first == "login" {
            login(label: arguments.count > 1 ? arguments[1] : "")
            return
        }
        if arguments.first == "delete" {
            delete(label: arguments.count > 1 ? arguments[1] : "")
            return
        }
        fileKeychain()
        print("device key ok")
    }

    private static func fileKeychain() {
        let keychain = TestKeychain()
        defer { keychain.close() }
        let label = "phone-station-selftest-\(UUID().uuidString)"
        let skipped = try! DeviceKey.make(label: label, store: .file, keychain: keychain.ref, trySecureEnclave: true, secureEnclave: { _, _ in (nil, -34018) })
        precondition(skipped.origin == .software && skipped.store == .file && skipped.secureEnclaveStatus == -34018, "\(skipped.origin) \(skipped.secureEnclaveStatus)")
        exercise(skipped)
        try! skipped.delete()
        precondition(!skipped.stored())

        let created = try! DeviceKey.make(label: "phone-station-selftest-\(UUID().uuidString)", store: .file, keychain: keychain.ref, trySecureEnclave: false)
        precondition(created.origin == .software && created.secureEnclaveStatus == errSecUnimplemented)
        exercise(created)
        precondition(created.stored())
        try! created.delete()
        precondition(!created.stored())
    }

    private static func login(label: String) {
        precondition(label.hasPrefix("phone-station-selftest-") && label.count >= "phone-station-selftest-".count + 36, "label")
        let keychain = try! defaultKeychain()
        let key = try! DeviceKey.make(label: label, store: .login, keychain: keychain, trySecureEnclave: true)
        defer { try? key.delete() }
        exercise(key)
        precondition(key.stored())
        print("device key path=\(key.origin.rawValue) keychain=login secureEnclaveStatus=\(key.secureEnclaveStatus)")
        try! key.delete()
        precondition(!key.stored())
    }

    private static func delete(label: String) {
        let keychain = try! defaultKeychain()
        try! DeviceKey.deleteSelfTest(label: label, keychain: keychain)
    }

    private static func exercise(_ key: DeviceKey) {
        let message = Data("phone-station link-v2 auth".utf8)
        let signature = try! key.sign(message)
        precondition(key.verifies(message, signature: signature), "verify")
        var tampered = signature
        tampered[0] ^= 0x01
        precondition(!key.verifies(message, signature: tampered), "tamper")
        let spki = try! key.subjectPublicKeyInfo()
        precondition(spki.count == 91 && spki.starts(with: [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
        ]), "spki \(spki.count)")
        let identifier = DeviceKey.deviceID(spki: spki)
        precondition(identifier.count == 32 && identifier.unicodeScalars.allSatisfy { "0123456789abcdef".unicodeScalars.contains($0) }, identifier)
    }

    private static func defaultKeychain() throws -> SecKeychain {
        var keychain: SecKeychain?
        let status = SecKeychainCopyDefault(&keychain)
        guard status == errSecSuccess, let keychain else { throw DeviceKeyError.status("default", status) }
        return keychain
    }
}

private final class TestKeychain {
    let ref: SecKeychain
    private let url: URL

    init() {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("phone-station-selftest-\(UUID().uuidString).keychain-db")
        url = file
        var created: SecKeychain?
        let password = Array(UUID().uuidString.utf8)
        let path = file.path
        let status = password.withUnsafeBytes { raw in
            SecKeychainCreate(path, UInt32(password.count), raw.baseAddress, false, nil, &created)
        }
        precondition(status == errSecSuccess && created != nil, "keychain \(status)")
        ref = created!
    }

    func close() {
        SecKeychainDelete(ref)
        try? FileManager.default.removeItem(at: url)
    }
}
