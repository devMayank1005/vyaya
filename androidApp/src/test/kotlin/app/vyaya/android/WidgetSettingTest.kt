package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.QuickLookStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Settings: the widget's amounts, hidden by default (the user's decision), and never while the app lock is on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSettingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-18T06:30:00Z")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private val label = "Show amounts on the home-screen widget"

    private fun open(lockEnabled: Boolean, changed: () -> Unit = {}) {
        compose.setContent {
            VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {},
                lockEnabled = lockEnabled, onWidgetChanged = changed)
        }
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun off_by_default_and_turning_it_on_redraws_the_widget() {
        var redrawn = 0
        open(lockEnabled = false) { redrawn++ }
        compose.onNodeWithText(label).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { runBlocking { QuickLookStore(db).showAmounts() } }
        compose.waitUntil(10_000) { redrawn == 1 }
        compose.onNodeWithText(label).assertIsOn()
        assertEquals(1, redrawn)
    }

    @Test
    fun with_the_app_lock_on_it_cant_be_turned_on_and_says_why() {
        open(lockEnabled = true)
        compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Hidden while the app lock is on", substring = true).assertExists()
    }
}
