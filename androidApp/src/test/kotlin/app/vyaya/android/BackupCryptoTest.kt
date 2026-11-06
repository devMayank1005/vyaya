package app.vyaya.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Encrypted backups (SEC-002 of the MVP report; docs/encrypted-backup-v1.md). The vectors come from an independent
 * implementation (Python `cryptography`), so Android and iOS can both be checked against them.
 */
class BackupCryptoTest {

    private val password = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val prefix = ByteArray(7) { (0xA0 + it).toByte() }

    private val vectors = mapOf(
        "hello, vyaya\n".toByteArray() to "5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001088aa1e4322ad9d7d057c7135ccab005126213401c213aa215b6a9c961e",
        ByteArray(40) { it.toByte() } to "5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001078eb5069957089abb257e78470ff249f50385353b604595e70f9034426aee8bb3ea27c4e3e0ebd9e2844093b05b8bae0d7c5e1c974f2e6efd03b5e7f1088d176099dfba1b8ec2e384895e0856307c03c4043af29efd6a717",
        ByteArray(32) { it.toByte() } to "5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001078eb5069957089abb257e78470ff249f50385353b604595e70f9034426aee8bb3ea27c4e3e0ebd9e2844093b05b8bae0d7c5e1c974f2e6efd03b5e7f1088d17694c600a8d35873e07c0c2913e5b6db34",
        ByteArray(0) to "5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6000000104bba3f9c78507cafe5296892e2181491",
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun encrypt(plain: ByteArray, iterations: Int = 1_000, chunk: Int = 16): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, password, iterations, chunk, salt, prefix).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(file: ByteArray, pw: CharArray = password): ByteArray =
        BackupCrypto.decryptingStream(ByteArrayInputStream(file), pw).use { it.readBytes() }

    private fun refused(file: ByteArray, pw: CharArray = password): String =
        try { decrypt(file, pw); fail("expected a refusal"); "" } catch (e: IOException) { e.message.orEmpty() }

    @Test
    fun the_independent_vectors_are_written_and_read_byte_for_byte() {
        for ((plain, file) in vectors) {
            assertEquals(file, hex(encrypt(plain)))
            assertArrayEquals(plain, decrypt(unhex(file)))
        }
    }

    @Test
    fun the_header_says_it_is_encrypted() {
        assertTrue(BackupCrypto.isEncrypted(unhex(vectors.values.first()).copyOf(8)))
        assertFalse(BackupCrypto.isEncrypted("{\"t\":\"header\"".toByteArray()))
    }

    @Test
    fun a_wrong_password_is_refused() {
        assertTrue(refused(unhex(vectors.values.first()), "Correct horse battery staple".toCharArray()).startsWith("Wrong password"))
    }

    @Test
    fun any_changed_byte_is_refused() {
        val file = unhex(vectors.entries.elementAt(1).value)
        for (i in file.indices) {
            val changed = file.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            try { decrypt(changed); fail("byte $i changed but the file was accepted") } catch (e: IOException) { }
        }
    }

    @Test
    fun cut_reordered_dropped_or_extended_files_are_refused() {
        val file = unhex(vectors.entries.elementAt(1).value) // header 41 + chunks of 32, 32, 24 bytes
        val header = file.copyOf(41)
        val c0 = file.copyOfRange(41, 73); val c1 = file.copyOfRange(73, 105); val last = file.copyOfRange(105, file.size)
        assertTrue(refused(header + c0 + c1).contains("cut off")) // ends after a full chunk
        assertTrue(refused(header + c0 + last).isNotEmpty()) // a chunk dropped
        assertTrue(refused(header + c1 + c0 + last).isNotEmpty()) // reordered
        assertTrue(refused(file + c0).isNotEmpty()) // something after the last chunk
        assertTrue(refused(file.copyOf(file.size - 1)).isNotEmpty())
        assertTrue(refused(header).contains("cut off"))
    }

    @Test
    fun a_crafted_header_cant_ask_for_huge_work_or_memory() {
        val file = unhex(vectors.values.first())
        fun withInt(at: Int, v: Int) = file.copyOf().also { b -> for (k in 0..3) b[at + k] = (v ushr (24 - 8 * k)).toByte() }
        assertTrue(refused(withInt(10, 2_000_000_000)).contains("not supported"))  // iterations
        assertTrue(refused(withInt(37, 1 shl 30)).contains("not supported"))  // chunk size
        assertTrue(refused(file.copyOf().also { it[8] = 2 }).contains("not supported")) // version
    }

    @Test
    fun a_large_backup_streams_through_in_chunks() {
        val plain = ByteArray(1_000_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, password, iterations = 1_000).use { s -> plain.asList().chunked(7_777).forEach { s.write(it.toByteArray()) } }
        assertArrayEquals(plain, decrypt(out.toByteArray()))
    }
}
