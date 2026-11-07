package app.vyaya.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.android.ui.Setup
import app.vyaya.android.ui.VyayaRoot
import app.vyaya.core.data.VyayaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** SEC-003 of the MVP report: the app lock, and confirming it's you before a backup leaves or replaces the data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockTest {

    @get:Rule val compose = createComposeRule()
    private var nowMs = 1_000_000L
    private val store = mutableMapOf<String, Boolean>()
    private fun lock() = AppLock(get = { store[it] ?: false }, set = { k, v -> store[k] = v }, clockMs = { nowMs })

    @Test
    fun off_by_default_and_then_never_locked() {
        val l = lock()
        assertFalse(l.enabled)
        l.onStart()
        assertFalse(l.locked)
    }

    @Test
    fun when_on_a_new_start_and_30_seconds_away_lock_it_and_a_short_switch_doesnt() {
        lock().enabled = true
        val l = lock() // a new process: what was saved says it's on
        l.onStart()
        assertTrue("a fresh start is locked", l.locked)
        l.unlocked()
        assertFalse(l.locked)
        l.onStop(); nowMs += 10_000; l.onStart()
        assertFalse("10 s away: still open", l.locked)
        l.onStop(); nowMs += 31_000; l.onStart()
        assertTrue("31 s away: locked", l.locked)
    }

    @Test
    fun export_and_restore_run_only_after_the_user_confirms() {
        val ran = mutableListOf<String>()
        val yes = Authenticator { _, done -> done(true) }
        val no = Authenticator { _, done -> done(false) }
        AppLock.confirmThen(no, "Export a backup") { ran += "export" }
        assertEquals(emptyList<String>(), ran)
        AppLock.confirmThen(yes, "Export a backup") { ran += "export" }
        assertEquals(listOf("export"), ran)
    }

    @Test
    fun while_locked_nothing_of_the_money_is_shown() {
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(ApplicationProvider.getApplicationContext()).setDriver(AndroidSQLiteDriver()).build()
        var asked = 0
        compose.setContent {
            VyayaRoot(db, Setup(true, false, false), clock = { Instant.parse("2026-11-06T10:00:00Z") }, opened = 1,
                onNotificationAccess = {}, onSms = {}, onImport = {}, locked = true, onUnlock = { asked++ })
        }
        compose.onNodeWithText("Vyaya is locked").assertExists()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Left")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Home")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Unlock").performClick()
        assertEquals(1, asked)
        db.close()
    }

    @Test
    fun the_lock_can_be_turned_on_from_the_backup_tab() {
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(ApplicationProvider.getApplicationContext()).setDriver(AndroidSQLiteDriver()).build()
        val switched = mutableListOf<Boolean>()
        compose.setContent {
            VyayaRoot(db, Setup(true, false, false), clock = { Instant.parse("2026-11-06T10:00:00Z") }, opened = 1,
                onNotificationAccess = {}, onSms = {}, onImport = {}, lockEnabled = false, onLockEnabled = { switched += it })
        }
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("Ask to unlock Vyaya").performScrollTo().performClick()
        assertEquals(listOf(true), switched)
        db.close()
    }
}
