package app.vyaya.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * AES-256-GCM on iOS: CryptoKit, which only Swift can call, so the Swift app registers it at launch
 * (iosApp/iosApp/CryptoKitAesGcm.swift). Ciphertext followed by the 16-byte tag; null from [open] when it doesn't verify.
 */
interface IosAesGcm {
    fun seal(key: NSData, nonce: NSData, aad: NSData, plain: NSData): NSData
    fun open(key: NSData, nonce: NSData, aad: NSData, sealed: NSData): NSData?
}

/** Where the Swift app puts its CryptoKit AES-GCM. */
object IosCrypto {
    var aesGcm: IosAesGcm? = null
}

/** iOS's primitives for encrypted backups: PBKDF2 from CommonCrypto, AES-GCM from CryptoKit ([IosCrypto]). */
@OptIn(ExperimentalForeignApi::class)
object IosPrimitives : BackupCryptoPrimitives {

    override fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val out = ByteArray(32)
        val status = password.usePinned { p ->
            salt.usePinned { s ->
                out.usePinned { o ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2, if (password.isEmpty()) null else p.addressOf(0).reinterpret(), password.size.convert(),
                        if (salt.isEmpty()) null else s.addressOf(0).reinterpret(), salt.size.convert(),
                        kCCPRFHmacAlgSHA256, iterations.convert(), o.addressOf(0).reinterpret(), out.size.convert(),
                    )
                }
            }
        }
        check(status == kCCSuccess) { "PBKDF2 failed ($status)" }
        return out
    }

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        gcm().seal(key.toNSData(), nonce.toNSData(), aad.toNSData(), plain.toNSData()).toByteArray()

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        gcm().open(key.toNSData(), nonce.toNSData(), aad.toNSData(), sealed.toNSData())?.toByteArray()

    private fun gcm() = checkNotNull(IosCrypto.aesGcm) { "CryptoKit's AES-GCM wasn't registered by the app" }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
