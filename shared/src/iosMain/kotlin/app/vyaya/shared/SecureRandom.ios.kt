package app.vyaya.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.random.Random

/** Every value comes from SecRandomCopyBytes (the system's cryptographic generator). */
actual fun secureRandom(): Random = object : Random() {
    @OptIn(ExperimentalForeignApi::class)
    override fun nextBits(bitCount: Int): Int {
        val bytes = ByteArray(4)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 4u, it.addressOf(0)) }
        check(status == errSecSuccess) { "the system's random generator failed ($status)" }
        val v = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
        return if (bitCount == 0) 0 else v ushr (32 - bitCount)
    }
}
