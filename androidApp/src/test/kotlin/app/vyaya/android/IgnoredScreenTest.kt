package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.IgnoredStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The Ignored tab: what capture skipped (a summary), with "This was a payment" and "Not a payment". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IgnoredScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-16T06:00:00Z")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun open() {
        runBlocking {
            AppOpen(db).run(now)
            CaptureStore(db).process(RawMessage(Source.SMS, "VM-HDFCBK-S", "Txn of Rs.2,000.00 debited from A/c XX1234 failed. Amount will be reversed",
                CapturedAt(now, TimeZone.of("Asia/Kolkata"))), now)
        }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("Detected").performClick()
        compose.onNodeWithText("Ignored (1)").performClick()
        waitFor("Failed or reversed: no money moved")
        waitFor("₹2,000 · VM-HDFCBK-S")
        waitFor("Only the sender, amount and reason are kept, for 30 days")
    }

    @Test
    fun ignored_this_was_a_payment_adds_it_and_takes_it_off_the_list() {
        open()
        compose.onNodeWithText("This was a payment").performScrollTo().performClick()
        compose.onNodeWithText("Add entry").assertExists()
        compose.onNodeWithText("2000").assertExists() // the amount, filled in
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { runBlocking { EntryStore(db).all() }.any { it.amount == Money(2_000_00) } }
        compose.waitUntil(10_000) { runBlocking { IgnoredStore(db).items(now) }.isEmpty() }
    }

    @Test
    fun ignored_not_a_payment_takes_it_off_the_list() {
        open()
        compose.onNodeWithText("Not a payment").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { IgnoredStore(db).items(now) }.isEmpty() }
        waitFor("Nothing skipped in the last 30 days")
        assertEquals(0, runBlocking { EntryStore(db).all() }.size)
    }
}
