package app.vyaya.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.vyaya.android.ui.BackupScreen
import app.vyaya.android.ui.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** reports/android-lock-encryption-2026-11-06.md #1–#4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockAndPasswordTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun flaw_001_one_lock_for_the_app_so_a_recreated_screen_doesnt_lock() {
        val app = ApplicationProvider.getApplicationContext<VyayaApp>()
        assertSame("every screen uses the same lock", app.appLock, app.appLock)
        var now = 1_000_000L
        val store = mutableMapOf<String, Boolean>("app_lock" to true)
        val lock = AppLock({ store[it] ?: false }, { k, v -> store[k] = v }, { now })
        lock.onStart(); lock.unlocked()
        // Rotation: the old activity stops, the new one starts a moment later, with the same lock.
        lock.onStop(); now += 200; lock.onStart()
        assertFalse(lock.locked)
    }

    @Test
    fun sec_002_common_or_guessable_passwords_are_refused() {
        for (weak in listOf("password1", "Password123", "12345678", "1234567890", "qwertyuiop", "aaaaaaaa", "iloveyou", "vyayavyaya", "abcd1234")) {
            assertNotNull(weak, PasswordCheck.problem(weak))
        }
        assertEquals("At least 8 characters", PasswordCheck.problem("short"))
        for (ok in listOf("correct horse battery staple", "tea-mango-river-cloud", "Xk7#pq9!Lm")) assertNull(ok, PasswordCheck.problem(ok))
    }

    @Test
    fun sec_002_a_suggested_passphrase_is_five_words_and_passes() {
        val a = PasswordCheck.passphrase(Random(1)); val b = PasswordCheck.passphrase(Random(2))
        assertEquals(5, a.split("-").size)
        assertTrue(a != b)
        assertNull(PasswordCheck.problem(a))
        assertTrue(PasswordCheck.WORDS.size >= 512)
        assertEquals(PasswordCheck.WORDS.size, PasswordCheck.WORDS.toSet().size)
    }

    @Test
    fun the_backup_screen_refuses_a_weak_password_offers_a_passphrase_and_says_it_cant_be_recovered() {
        val exported = mutableListOf<String?>()
        compose.setContent { BackupScreen(BackupStatus.Idle, onExport = { exported += it?.concatToString() }, onRestore = {}, onRestoreAnyway = {}) }
        compose.onNodeWithText("no way to recover", substring = true).assertExists()
        compose.onNodeWithText("Password").performTextInput("password1")
        compose.onNodeWithText("Repeat the password").performTextInput("password1")
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("common", substring = true).assertExists()
        compose.onNodeWithText("Suggest a passphrase").performScrollTo().performClick()
        compose.onNodeWithText("Export backup").performScrollTo().performClick()
        assertEquals(5, exported.single()!!.split("-").size)
    }

    @Test
    fun flaw_004_without_a_screen_lock_the_switch_says_so() {
        compose.setContent { BackupScreen(BackupStatus.Idle, onExport = {}, onRestore = {}, onRestoreAnyway = {}, deviceSecure = false) }
        compose.onNodeWithText("no screen lock", substring = true).performScrollTo().assertExists()
    }
}
