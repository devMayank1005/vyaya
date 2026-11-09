import CryptoKit
import Foundation

/// AES-256-GCM for encrypted backups (docs/encrypted-backup-v1.md), with CryptoKit: ciphertext followed by the
/// 16-byte tag. No Kotlin here, so it is tested on its own (iosApp/Tests, scripts/test-ios-crypto.sh).
enum VyayaAesGcm {
    static func seal(key: Data, nonce: Data, aad: Data, plain: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
        // A fresh Data: CryptoKit's ciphertext is a slice whose indices don't start at 0.
        return Data(box.ciphertext) + Data(box.tag)
    }

    /// The plain text, or nil when the tag doesn't verify (wrong key, changed bytes) or the input is malformed.
    static func open(key: Data, nonce: Data, aad: Data, sealed: Data) -> Data? {
        guard sealed.count >= 16 else { return nil }
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: sealed.prefix(sealed.count - 16), tag: sealed.suffix(16))
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        } catch {
            return nil
        }
    }
}
