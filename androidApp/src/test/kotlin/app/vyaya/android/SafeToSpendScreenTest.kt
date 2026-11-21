package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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

    private fun open() =
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }

    @Test
    fun safe_flaw_002_the_salary_still_to_come_is_counted_and_said() {
        runBlocking {
            RulesStore(db).add(EntryType.INCOME, Money(60_000_00), 30, null, "Salary", Instant.parse("2026-11-01T06:00:00Z"), ist)
            RulesStore(db).add(EntryType.EXPENSE, Money(15_000_00), 20, null, "Rent", Instant.parse("2026-11-01T06:00:00Z"), ist)
            AppOpen(db).run(now)
            EntryStore(db).save(listOf(entry(EntryType.EXPENSE, 10_000, "2026-11-05T06:00:00Z")))
        }
        open()
        waitFor("Safe to spend ₹1,842 a day")
        waitFor("and ₹60,000 of income expected")
    }

    @Test
    fun safe_flaw_003_today_says_what_is_left_of_its_amount() {
        runBlocking { EntryStore(db).save(listOf(entry(EntryType.EXPENSE, 500, "2026-11-12T05:00:00Z"))) }
        app(income = 50_000, spent = 20_000, rent = 15_000)
        waitFor("Safe to spend ₹789 a day")
        waitFor("₹289 left for today")
    }

    @Test
    fun safe_flaw_004_a_budget_nearly_used_up_is_noted_under_the_line() {
        runBlocking {
            AppOpen(db).run(now)
            val food = app.vyaya.core.data.CatalogStore(db).categories().first { it.name == "Food & dining" }
            app.vyaya.core.data.BudgetStore(db).set(food, Money(5_000_00), now)
            EntryStore(db).save(listOf(entry(EntryType.EXPENSE, 4_500, "2026-11-05T06:00:00Z").copy(categoryId = food.id)))
        }
        app(income = 50_000, spent = 1_000, rent = 1_000)
        waitFor("Food & dining is nearly used up")
    }

    @Test
    fun safe_flaw_002_the_salary_day_is_offered_as_the_month_start() {
        runBlocking {
            AppOpen(db).run(now)
            EntryStore(db).save(listOf(entry(EntryType.INCOME, 60_000, "2026-09-30T06:00:00Z"), entry(EntryType.INCOME, 60_000, "2026-10-29T06:00:00Z")))
        }
        open()
        waitFor("Your salary comes around the 29th")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Start on the 29th")) // below the summary on a small screen
        compose.onNodeWithText("Start on the 29th").performClick()
        compose.waitUntil(10_000) { runBlocking { app.vyaya.core.data.MonthStartStore(db).startDay(kotlinx.datetime.LocalDate(2027, 1, 15)) } == 29 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Your salary comes around", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun safe_flaw_002_not_now_puts_the_suggestion_away() {
        runBlocking {
            AppOpen(db).run(now)
            EntryStore(db).save(listOf(entry(EntryType.INCOME, 60_000, "2026-09-30T06:00:00Z"), entry(EntryType.INCOME, 60_000, "2026-10-29T06:00:00Z")))
        }
        open()
        waitFor("Your salary comes around the 29th")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Not now")) // below the summary on a small screen
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntil(10_000) { runBlocking { app.vyaya.core.data.MonthStartStore(db).salarySuggestion(kotlinx.datetime.LocalDate(2026, 11, 12)) } == null }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Your salary comes around", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun safe_flaw_002_the_month_start_can_be_set_in_settings() {
        runBlocking { AppOpen(db).run(now) }
        open()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Your month starts on the 1st", substring = true).performScrollTo()
        compose.onNodeWithText("Change start day").performScrollTo().performClick()
        compose.onNodeWithText("Start day (1–31)").performTextInput("25")
        compose.onNodeWithText("Save start day").performClick()
        compose.waitUntil(10_000) { runBlocking { app.vyaya.core.data.MonthStartStore(db).startDay(kotlinx.datetime.LocalDate(2027, 1, 30)) } == 25 }
    }
}
