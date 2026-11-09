package app.vyaya.shared

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared parts of the encrypted backup format that need no crypto (runs on Android and iOS). */
class EncryptedBackupTest {

    @Test
    fun the_password_is_utf8_like_the_standard_encoder_without_a_string() {
        for (p in listOf("correct horse battery staple", "पासवर्ड १२३", "café ☕ ₹", "emoji 🔐🙂", "")) {
            assertContentEquals(p.encodeToByteArray(), EncryptedBackup.utf8(p.toCharArray()), p)
        }
    }

    @Test
    fun a_lone_surrogate_is_the_replacement_character_on_every_platform() {
        // The JVM's encoder writes "?" here and Swift U+FFFD; the spec fixes U+FFFD so a password gives one key everywhere.
        assertContentEquals(byteArrayOf(0x78, 0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(), 0x79), EncryptedBackup.utf8("x\uD800y".toCharArray()))
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(), 0x74), EncryptedBackup.utf8("\uDC00t".toCharArray()))
    }

    @Test
    fun an_encrypted_file_is_told_by_its_first_bytes() {
        assertTrue(EncryptedBackup.isEncrypted("VYAYAENC\u0001".encodeToByteArray()))
        assertFalse(EncryptedBackup.isEncrypted("{\"t\":\"header\"".encodeToByteArray()))
        assertFalse(EncryptedBackup.isEncrypted("VYAYA".encodeToByteArray()))
    }
}
