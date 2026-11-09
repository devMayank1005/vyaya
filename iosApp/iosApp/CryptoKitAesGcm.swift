import Foundation
import Shared

/// Gives the shared Kotlin code CryptoKit's AES-GCM (IosAesGcm in shared/src/iosMain, IosPrimitives.kt).
final class CryptoKitAesGcm: NSObject, IosAesGcm {
    // Kotlin/Native names the last parameters plain_ and sealed_ in Swift (the labels the protocol requires).
    func seal(key: Data, nonce: Data, aad: Data, plain_ plain: Data) -> Data {
        // Only a malformed key or nonce makes sealing fail; the format always passes 32 and 12 bytes.
        try! VyayaAesGcm.seal(key: key, nonce: nonce, aad: aad, plain: plain)
    }

    func open(key: Data, nonce: Data, aad: Data, sealed_ sealed: Data) -> Data? {
        VyayaAesGcm.open(key: key, nonce: nonce, aad: aad, sealed: sealed)
    }
}
