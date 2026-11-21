package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.MonthStartStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Branch review 2026-11-18 #7: an action that fails says so; it never crashes the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private var now = Instant.parse("2026-11-12T06:00:00Z")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun setStartDay(day: String) {
        compose.onNodeWithText("Change start day").performScrollTo().performClick()
        compose.onNodeWithText("Start day (1–31)").performTextInput(day)
        compose.onNodeWithText("Save start day").performClick()
    }

    @Test
    fun review_pen_007_a_change_the_data_refuses_shows_a_message_instead_of_crashing() {
        runBlocking { AppOpen(db).run(now) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("Settings").performClick()
        setStartDay("25") // from 1 December
        compose.waitUntil(10_000) { runBlocking { MonthStartStore(db).startDay(LocalDate(2027, 1, 30)) } == 25 }
        now = Instant.parse("2026-10-15T06:00:00Z") // the phone's clock set back a month
        setStartDay("10") // would take effect before the change already made: refused by the schedule
        waitFor("That didn't work")
        compose.onNodeWithText("OK").performClick()
        assertEquals("nothing changed", 25, runBlocking { MonthStartStore(db).startDay(LocalDate(2027, 1, 30)) })
        compose.onNodeWithText("Home").performClick() // the app is still running
        waitFor("Left")
    }
}
