import Foundation
import Security

private let label = "phone-station-mac"

func fail(_ message: String) -> Never {
    fputs(message + "\n", stderr)
    exit(1)
}

func openKey() throws -> DeviceKey {
    var keychain: SecKeychain?
    let status = SecKeychainCopyDefault(&keychain)
    guard status == errSecSuccess, let keychain else {
        throw DeviceKeyError.status("default", status)
    }
    return try DeviceKey.loadOrMake(label: label, store: .login, keychain: keychain, trySecureEnclave: false)
}

guard CommandLine.arguments.count == 2 else {
    fail("usage: phone-device-key public|sign")
}

switch CommandLine.arguments[1] {
case "public":
    let key: DeviceKey
    do { key = try openKey() } catch { fail("device key unavailable") }
    let spki: Data
    do { spki = try key.subjectPublicKeyInfo() } catch { fail("device key unavailable") }
    let id = DeviceKey.deviceID(spki: spki)
    FileHandle.standardOutput.write(Data((id + "\n").utf8))
    FileHandle.standardOutput.write(spki.base64EncodedData())
    FileHandle.standardOutput.write(Data([0x0a]))
case "sign":
    let message = FileHandle.standardInput.readDataToEndOfFile()
    guard !message.isEmpty, message.count <= 4096 else { fail("device key unavailable") }
    let key: DeviceKey
    let signature: Data
    do {
        key = try openKey()
        signature = try key.sign(message)
    } catch { fail("device key unavailable") }
    FileHandle.standardOutput.write(signature)
default:
    fail("usage: phone-device-key public|sign")
}
