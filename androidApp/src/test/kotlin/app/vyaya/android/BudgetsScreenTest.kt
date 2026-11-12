package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.BudgetStore
import app.vyaya.core.data.CatalogStore
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Budgets from the screens: set in Settings (with a suggestion), shown on Home against the month's spending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BudgetsScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-12T06:00:00Z")

    // The database closes after the screen is gone (outer rule), so a reload after the last check can't read it closed.
    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun food() = runBlocking { CatalogStore(db).categories() }.first { it.name == "Food & dining" }

    private fun spent(rupees: Long, at: String) = Entry(EntityId.random(), EntryType.EXPENSE, Money(rupees * 100),
        CapturedAt(Instant.parse(at), kotlinx.datetime.TimeZone.of("Asia/Kolkata")), null, null, food().id, "Cafe", "", emptyList(),
        EntrySource.MANUAL, null, emptyList(), null, now, now, false)

    private fun app() =
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }

    private fun openBudgets() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Budgets").performScrollTo().performClick()
    }

    @Test
    fun budgets_a_budget_is_set_from_the_suggestion() {
        runBlocking { AppOpen(db).run(now); EntryStore(db).save(listOf(spent(3_000, "2026-09-10T06:00:00Z"), spent(5_000, "2026-10-10T06:00:00Z"))) }
        app()
        openBudgets()
        compose.onNodeWithText("Add budget", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        waitFor("Suggested ₹4,000: your average over the last 2 months")
        compose.onNodeWithText("Use ₹4,000").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { runBlocking { BudgetStore(db).budgets() }.isNotEmpty() }
        assertEquals(Money(4_000_00), runBlocking { BudgetStore(db).budgets() }.single().limit)
        waitFor("₹4,000 a month"); compose.waitForIdle()
    }

    @Test
    fun budgets_an_amount_can_be_typed_and_a_budget_removed() {
        runBlocking { AppOpen(db).run(now) }
        app()
        openBudgets()
        waitFor("No budgets yet")
        compose.onNodeWithText("Add budget", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Monthly limit").performTextInput("2500")
        compose.onNodeWithText("Save").performClick()
        waitFor("₹2,500 a month")
        compose.onNodeWithText("Food & dining").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(10_000) { runBlocking { BudgetStore(db).budgets() }.isEmpty() }
        waitFor("No budgets yet"); compose.waitForIdle()
    }

    @Test
    fun budgets_home_shows_each_budget_against_the_months_spending() {
        runBlocking {
            AppOpen(db).run(now)
            BudgetStore(db).set(food(), Money(5_000_00), now)
            EntryStore(db).save(listOf(spent(4_200, "2026-11-05T06:00:00Z"), spent(9_000, "2026-10-05T06:00:00Z")))
        }
        app()
        waitFor("Out ₹4,200")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("₹4,200 of ₹5,000"))
        waitFor("₹800 left")
        compose.onNodeWithText("Budgets").assertExists()
    }

    @Test
    fun budgets_home_says_how_far_over() {
        runBlocking {
            AppOpen(db).run(now)
            BudgetStore(db).set(food(), Money(4_000_00), now)
            EntryStore(db).save(listOf(spent(4_300, "2026-11-05T06:00:00Z")))
        }
        app()
        waitFor("Out ₹4,300")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("₹300 over"))
    }
}
