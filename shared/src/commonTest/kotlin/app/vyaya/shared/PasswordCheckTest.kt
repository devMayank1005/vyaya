package app.vyaya.shared

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SEC-002 (lock report): passwords for protected backups (runs on Android and iOS). */
class PasswordCheckTest {

    @Test
    fun common_or_guessable_passwords_are_refused() {
        for (weak in listOf("password1", "Password123", "12345678", "1234567890", "qwertyuiop", "aaaaaaaa", "iloveyou", "vyayavyaya", "abcd1234")) {
            assertNotNull(PasswordCheck.problem(weak), weak)
        }
        assertEquals("At least 8 characters", PasswordCheck.problem("short"))
        for (ok in listOf("correct horse battery staple", "tea-mango-river-cloud", "Xk7#pq9!Lm")) assertNull(PasswordCheck.problem(ok), ok)
    }

    @Test
    fun a_suggested_passphrase_is_five_words_and_passes() {
        val a = PasswordCheck.passphrase(Random(1)); val b = PasswordCheck.passphrase(Random(2))
        assertEquals(5, a.split("-").size)
        assertTrue(a != b)
        assertNull(PasswordCheck.problem(a))
        assertTrue(PasswordCheck.WORDS.size >= 512)
        assertEquals(PasswordCheck.WORDS.size, PasswordCheck.WORDS.toSet().size)
    }

    @Test
    fun the_platforms_secure_random_gives_different_passphrases() {
        assertTrue(PasswordCheck.passphrase() != PasswordCheck.passphrase())
    }
}
