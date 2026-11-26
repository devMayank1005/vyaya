package app.vyaya.android

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The widget's Add and the tile while a form is already open (widget report FLAW-002). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickAddFormTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-18T06:30:00Z")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun widget_flaw_002_an_edit_in_progress_is_kept_when_add_is_tapped() {
        runBlocking {
            AppOpen(db).run(now)
            EntryStore(db).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(450_00), CapturedAt(now, TimeZone.of("Asia/Kolkata")), null, null,
                null, "Chai Point", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, now, now, false)))
        }
        val requests = mutableIntStateOf(0)
        compose.setContent {
            VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {},
                addRequests = requests.intValue)
        }
        waitFor("Chai Point")
        compose.onNodeWithText("Chai Point", useUnmergedTree = true).performClick()
        waitFor("Edit entry")
        compose.onNodeWithText("450").performTextReplacement("475")
        requests.intValue++ // Add tapped on the widget
        compose.waitForIdle()
        compose.onNodeWithText("Edit entry").assertExists()
        compose.onNodeWithText("475").assertExists()
    }

    @Test
    fun add_opens_the_form_when_none_is_open() {
        runBlocking { AppOpen(db).run(now) }
        val requests = mutableIntStateOf(0)
        compose.setContent {
            VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {},
                addRequests = requests.intValue)
        }
        waitFor("Left")
        requests.intValue++
        waitFor("Add entry")
    }
}
