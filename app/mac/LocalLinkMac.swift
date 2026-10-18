import CryptoKit
import Foundation
import Security

/// In-memory local handshake. The Mac signature comes from DeviceKey.
/// The running app does not call this.
enum LocalLinkMac {
    static func message(peerID: Data, peerNonce: Data, ownID: Data) -> Data {
        precondition(peerID.count == 16 && peerNonce.count == 32 && ownID.count == 16)
        var out = Data("phone-station link-v2 local-auth".utf8)
        out.append(peerID)
        out.append(peerNonce)
        out.append(ownID)
        return out
    }

    static func exchange(_ mac: DeviceKey) throws {
        let sample = message(
            peerID: Data(repeating: 0x11, count: 16),
            peerNonce: Data(repeating: 0x22, count: 32),
            ownID: Data(repeating: 0x33, count: 16))
        let digest = SHA256.hash(data: sample).map { String(format: "%02x", $0) }.joined()
        guard digest == "43d6e3b24dbca9a048816e036a08a793a0705f7d97cdaaa4172aedfc1b4c0b40" else {
            throw LocalLinkMacError.rejected
        }
        let phone = try EphemeralP256()
        let macNonce = try random(32)
        let phoneNonce = try random(32)
        let macSPKI = try mac.subjectPublicKeyInfo()
        let phoneSPKI = try phone.subjectPublicKeyInfo()
        let macID = Data(SHA256.hash(data: macSPKI).prefix(16))
        let phoneID = Data(SHA256.hash(data: phoneSPKI).prefix(16))
        let phoneSigned = message(peerID: macID, peerNonce: macNonce, ownID: phoneID)
        let phoneSig = try phone.sign(phoneSigned)
        guard phone.verifies(phoneSigned, signature: phoneSig) else { throw LocalLinkMacError.rejected }
        let macSigned = message(peerID: phoneID, peerNonce: phoneNonce, ownID: macID)
        let macSig = try mac.sign(macSigned)
        guard EphemeralP256.verifies(spki: macSPKI, message: macSigned, signature: macSig) else {
            throw LocalLinkMacError.rejected
        }
        var broken = macSig
        broken[0] ^= 0x01
        if EphemeralP256.verifies(spki: macSPKI, message: macSigned, signature: broken) {
            throw LocalLinkMacError.rejected
        }
    }

    private static func random(_ count: Int) throws -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { throw LocalLinkMacError.rejected }
        return data
    }
}

private enum LocalLinkMacError: Error {
    case rejected
}

private struct EphemeralP256 {
    let privateKey: SecKey

    init() throws {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw LocalLinkMacError.rejected
        }
        privateKey = key
    }

    func sign(_ message: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, message as CFData, &error) as Data? else {
            throw LocalLinkMacError.rejected
        }
        return signature
    }

    func verifies(_ message: Data, signature: Data) -> Bool {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { return false }
        return SecKeyVerifySignature(publicKey, .ecdsaSignatureMessageX962SHA256, message as CFData, signature as CFData, nil)
    }

    func subjectPublicKeyInfo() throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { throw LocalLinkMacError.rejected }
        return try EphemeralP256.spki(publicKey)
    }

    static func verifies(spki: Data, message: Data, signature: Data) -> Bool {
        guard spki.count == 91, let key = publicKey(spki) else { return false }
        return SecKeyVerifySignature(key, .ecdsaSignatureMessageX962SHA256, message as CFData, signature as CFData, nil)
    }

    private static func publicKey(_ spki: Data) -> SecKey? {
        let point = Data(spki.suffix(65))
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: 256,
        ]
        return SecKeyCreateWithData(point as CFData, attributes as CFDictionary, nil)
    }

    private static func spki(_ publicKey: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?, point.count == 65, point.first == 0x04 else {
            throw LocalLinkMacError.rejected
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
}
