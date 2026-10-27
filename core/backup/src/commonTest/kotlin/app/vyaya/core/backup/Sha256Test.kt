package app.vyaya.core.backup

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    private fun hex(text: String) = Sha256().apply { update(text) }.hexDigest()

    @Test
    fun nist_and_well_known_test_vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        assertEquals("d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
            hex("The quick brown fox jumps over the lazy dog"))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex("a".repeat(1_000_000)))
    }

    @Test
    fun text_is_hashed_as_utf8() {
        // "₹" is E2 82 B9 in UTF-8.
        assertEquals(Sha256().apply { update(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xB9.toByte())) }.hexDigest(), hex("₹"))
    }

    @Test
    fun the_digest_is_final() {
        val sha = Sha256().apply { update("abc") }
        assertEquals(sha.hexDigest(), sha.hexDigest())
        kotlin.test.assertFailsWith<IllegalStateException> { sha.update("more") }
    }

    @Test
    fun feeding_in_pieces_gives_the_same_digest() = runTest {
        checkAll(200, Arb.string(0..300), Arb.int(0..300)) { text, cut ->
            val at = minOf(cut, text.length)
            val pieces = Sha256().apply { update(text.substring(0, at)); update(text.substring(at)) }.hexDigest()
            assertEquals(hex(text), pieces)
        }
    }
}
