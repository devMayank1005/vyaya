package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.RulesStore
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

/** Safe to spend per day on Home: the current month only, after the bills still due. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeToSpendScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun entry(type: EntryType, rupees: Long, at: String) = Entry(EntityId.random(), type, Money(rupees * 100), CapturedAt(Instant.parse(at), ist),
        null, null, null, "x", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, now, now, false)

    private fun app(income: Long, spent: Long, rent: Long) {
        runBlocking {
            RulesStore(db).add(EntryType.EXPENSE, Money(rent * 100), 20, null, "Rent", Instant.parse("2026-11-01T06:00:00Z"), ist)
            AppOpen(db).run(now)
            EntryStore(db).save(listOf(entry(EntryType.INCOME, income, "2026-11-01T06:00:00Z"), entry(EntryType.EXPENSE, spent, "2026-11-05T06:00:00Z")))
        }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
    }

    @Test
    fun safe_to_spend_shows_a_daily_amount_after_the_bills_still_due() {
        app(income = 50_000, spent = 20_000, rent = 15_000)
        // (30,000 − 15,000) / 19 days (12 to 30 November) = 789.47
        waitFor("Safe to spend ₹789 a day")
        waitFor("for 19 days, after ₹15,000 of bills still due")
    }

    @Test
    fun safe_to_spend_says_when_the_bills_are_more_than_whats_left() {
        app(income = 10_000, spent = 9_000, rent = 1_500)
        waitFor("Bills still due (₹1,500) are ₹500 more than what's left")
    }

    @Test
    fun safe_to_spend_is_only_for_the_current_month() {
        app(income = 50_000, spent = 20_000, rent = 15_000)
        waitFor("Safe to spend")
        compose.onNodeWithContentDescription("Previous month").performClick()
        waitFor("October 2026")
        compose.onNodeWithText("Safe to spend", substring = true).assertDoesNotExist()
    }
}
