package app.vyaya.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** iOS's PBKDF2 (CommonCrypto) against the published PBKDF2-HMAC-SHA256 vectors (RFC 7914 §11 style, 32 bytes). */
class IosPrimitivesTest {

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun pbkdf2_matches_the_published_vectors() {
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(IosPrimitives.pbkdf2("password".encodeToByteArray(), "salt".encodeToByteArray(), 1)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(IosPrimitives.pbkdf2("password".encodeToByteArray(), "salt".encodeToByteArray(), 4096)))
    }

    @Test
    fun without_cryptokit_registered_it_refuses_rather_than_guess() {
        IosCrypto.aesGcm = null
        assertFailsWith<IllegalStateException> { IosPrimitives.seal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(1)) }
    }
}
