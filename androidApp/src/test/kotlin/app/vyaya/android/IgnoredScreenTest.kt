package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
        waitFor("Ignored (1)") // the count loads after the screen
        compose.onNodeWithText("Ignored (1)").performClick()
        waitFor("Failed or reversed: no money moved")
        waitFor("₹2,000 · VM-HDFCBK-S")
        waitFor("Only the sender, amount and reason are kept, for 30 days")
    }

    @Test
    fun ignored_this_was_a_payment_adds_it_and_takes_it_off_the_list() {
        open()
        compose.onNodeWithText("This was a payment").performScrollTo().performClick()
        waitFor("Add entry") // opens once the already-logged check has run
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

    private fun capture(sender: String, text: String, at: Instant = now) = runBlocking {
        CaptureStore(db).process(RawMessage(Source.SMS, sender, text, CapturedAt(at, TimeZone.of("Asia/Kolkata"))), now)
    }

    private fun openIgnored(count: Int) {
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("Detected").performClick()
        waitFor("Ignored ($count)") // the count loads after the screen
        compose.onNodeWithText("Ignored ($count)").performClick()
    }

    @Test
    fun ignored_sec_001_a_phone_number_gets_a_warning_not_a_one_tap_add() {
        runBlocking { AppOpen(db).run(now) }
        capture("+919876543210", "Rs 5,000 credited to your account. Click to claim")
        openIgnored(1)
        waitFor("Banks never send from phone numbers")
        compose.onNodeWithText("This was a payment").assertDoesNotExist()
        compose.onNodeWithText("Not a payment").assertExists()
    }

    @Test
    fun ignored_data_002_a_payment_already_logged_is_offered_instead() {
        runBlocking { AppOpen(db).run(now) }
        capture("VM-HDFCBK-S", "Txn of Rs.2,000.00 debited from A/c XX1234 failed. Amount will be reversed")
        capture("VM-HDFCBK-S", "Rs.2,000.00 debited from A/c XX1234 to Swiggy. UPI Ref 429100000077", now + kotlin.time.Duration.parse("1m"))
        openIgnored(1)
        compose.onNodeWithText("This was a payment").performScrollTo().performClick()
        waitFor("This looks already logged")
        compose.onNodeWithText("Open it").performClick()
        waitFor("Edit entry")
        compose.waitUntil(10_000) { runBlocking { IgnoredStore(db).items(now) }.isEmpty() }
        assertEquals("nothing added twice", 1, runBlocking { EntryStore(db).all() }.size)
    }

    @Test
    fun ignored_flaw_003_promotions_are_folded_away() {
        runBlocking { AppOpen(db).run(now) }
        capture("VM-HDFCBK-S", "Txn of Rs.2,000.00 debited from A/c XX1234 failed. Amount will be reversed")
        capture("VM-AMAZON-P", "Get Rs.500 cashback on your next order! Shop now")
        openIgnored(1)
        waitFor("₹2,000")
        compose.onNodeWithText("₹500", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show promotions and codes (1)").performClick()
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("₹500 · VM-AMAZON-P")) // below the fold
    }

    @Test
    fun ignored_flaw_004_005_money_in_starts_as_income_and_times_are_in_the_home_zone() {
        runBlocking {
            AppOpen(db).run(now)
            // A home zone other than the phone's, so the shown time proves which one is used.
            val s = app.vyaya.core.data.SnapshotStore(db).loadRecords().settings
            app.vyaya.core.data.SnapshotStore(db).save(app.vyaya.core.backup.Snapshot.empty().copy(settings = s.copy(homeTimeZone = TimeZone.of("America/New_York"))))
        }
        capture("VM-HDFCBK-S", "Rs 5,000 will be credited to your A/c XX1234 on 20 Nov")
        openIgnored(1)
        waitFor("2026-11-16 01:00") // 06:00 UTC in New York, the home zone
        compose.onNodeWithText("This was a payment").performScrollTo().performClick()
        waitFor("Add entry") // opens once the already-logged check has run
        compose.onNodeWithText("Income").assertIsSelected()
    }
}
