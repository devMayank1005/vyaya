package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.LedgerStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.Money
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
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

/** Split bills from the entry form: across your categories, and with friends (their shares go to People). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SplitBillsScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-18T14:00:00Z")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun category(name: String) = runBlocking { CatalogStore(db).categories() }.first { it.name == name }

    private fun addEntry(rupees: String) {
        runBlocking { AppOpen(db).run(now) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Amount").performTextInput(rupees)
    }

    @Test
    fun split_a_dinner_with_two_friends() {
        addEntry("1200")
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Split with friends").performScrollTo().performClick()
        compose.onNodeWithText("New person's name").performScrollTo().performTextInput("Rahul")
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        waitFor("Rahul's share")
        compose.onNodeWithText("New person's name").performScrollTo().performTextInput("Priya")
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        waitFor("Priya's share")
        waitFor("Your share: ₹400")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { EntryStore(db).all() }.isNotEmpty() }
        val entry = runBlocking { EntryStore(db).all() }.single()
        assertEquals(listOf(Split(category("Food & dining").id, Money(400_00)), Split(SystemCategories.LENT.id, Money(800_00))), entry.splits)
        assertEquals(Money(800_00), runBlocking { LedgerStore(db).balances() }.owedToYou)
        waitFor("Out ₹400"); compose.waitForIdle()
    }

    @Test
    fun split_a_bill_across_categories() {
        addEntry("2000")
        compose.onNodeWithText("Split across categories").performScrollTo().performClick()
        compose.onNodeWithText("Groceries").performScrollTo().performClick()
        compose.onNodeWithText("Shopping").performScrollTo().performClick()
        compose.onNodeWithText("Groceries ₹").performScrollTo().performTextInput("1500")
        compose.onNodeWithText("Shopping ₹").performScrollTo().performTextInput("400")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor("Your parts add up to ₹1,900; they should be ₹2,000")
        compose.onNodeWithText("400").performScrollTo().performTextReplacement("500")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { EntryStore(db).all() }.isNotEmpty() }
        assertEquals(listOf(Split(category("Groceries").id, Money(1_500_00)), Split(category("Shopping").id, Money(500_00))),
            runBlocking { EntryStore(db).all() }.single().splits)
        waitFor("Out ₹2,000"); compose.waitForIdle()
    }

    /** A ₹1,200 dinner, ₹400 each with Rahul and Priya, saved before the app opens. */
    private fun dinnerSplit(): app.vyaya.core.model.Entry = runBlocking {
        AppOpen(db).run(now)
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", now); val priya = ledger.addPerson("Priya", now)
        val dinner = app.vyaya.core.model.Entry(app.vyaya.core.model.EntityId.random(), app.vyaya.core.model.EntryType.EXPENSE, Money(1_200_00),
            app.vyaya.core.model.CapturedAt(now, kotlinx.datetime.TimeZone.of("Asia/Kolkata")), null, null, category("Food & dining").id,
            "Barbeque Nation", "", emptyList(), app.vyaya.core.model.EntrySource.MANUAL, null, emptyList(), null, now, now, false)
        (app.vyaya.core.data.SplitStore(db).save(dinner, emptyList(), listOf(rahul.id to Money(400_00), priya.id to Money(400_00)), now)
            as app.vyaya.core.data.CatalogResult.Saved).item
    }

    private fun openDinner() {
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        waitFor("Barbeque Nation")
        compose.onNodeWithText("Barbeque Nation", useUnmergedTree = true).performClick()
        waitFor("Rahul's share")
    }

    @Test
    fun split_flaw_003_edit_who_shared_a_dinner() {
        val dinner = dinnerSplit()
        openDinner()
        compose.onNodeWithText("Rahul's share").assertExists()
        compose.onNodeWithText("Priya", useUnmergedTree = true).performScrollTo().performClick() // Priya didn't come
        waitFor("Your share: ₹800")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { LedgerStore(db).balances() }.owedToYou == Money(400_00) }
        assertEquals(listOf(Split(category("Food & dining").id, Money(800_00)), Split(SystemCategories.LENT.id, Money(400_00))),
            runBlocking { EntryStore(db).byId(dinner.id) }!!.splits)
        compose.waitForIdle()
    }

    @Test
    fun split_data_001_deleting_a_split_dinner_asks_about_what_friends_owe() {
        val dinner = dinnerSplit()
        openDinner()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        waitFor("Also take back what Priya and Rahul owe (₹800)?") // names in alphabetical order, whatever order they're stored in
        compose.onNodeWithText("Take it back").performClick()
        compose.waitUntil(10_000) { runBlocking { EntryStore(db).byId(dinner.id) }!!.deleted }
        assertEquals(0L, runBlocking { LedgerStore(db).balances() }.owedToYou.paise)
        compose.waitForIdle()
    }

    @Test
    fun split_flaw_005_a_friend_added_after_an_edited_share_gets_a_share_of_whats_left() {
        addEntry("1200")
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Split with friends").performScrollTo().performClick()
        compose.onNodeWithText("New person's name").performScrollTo().performTextInput("Rahul")
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        waitFor("Rahul's share")
        compose.onNodeWithText("600").performScrollTo().performTextReplacement("500")
        compose.onNodeWithText("New person's name").performScrollTo().performTextInput("Priya")
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        waitFor("Priya's share")
        waitFor("Your share: ₹350") // ₹700 left: half to Priya, half to you; Rahul's ₹500 kept
        compose.onNodeWithText("350").assertExists()
        compose.onNodeWithText("500").assertExists()
    }

    @Test
    fun split_flaw_004_home_says_safe_to_spend_is_after_what_friends_owe() {
        dinnerSplit()
        runBlocking { app.vyaya.core.data.EntryStore(db).save(listOf(app.vyaya.core.model.Entry(app.vyaya.core.model.EntityId.random(),
            app.vyaya.core.model.EntryType.INCOME, Money(50_000_00), app.vyaya.core.model.CapturedAt(now, kotlinx.datetime.TimeZone.of("Asia/Kolkata")),
            null, null, null, "Salary", "", emptyList(), app.vyaya.core.model.EntrySource.MANUAL, null, emptyList(), null, now, now, false))) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        waitFor("after ₹800 your friends owe you")
    }

    @Test
    fun split_flaw_004_when_what_friends_owe_is_more_than_whats_left_home_says_so() {
        dinnerSplit() // ₹400 spent, ₹800 lent; ₹1,000 came in. Seen the next day (today's payments count in today's amount)
        runBlocking { app.vyaya.core.data.EntryStore(db).save(listOf(app.vyaya.core.model.Entry(app.vyaya.core.model.EntityId.random(),
            app.vyaya.core.model.EntryType.INCOME, Money(1_000_00), app.vyaya.core.model.CapturedAt(now, kotlinx.datetime.TimeZone.of("Asia/Kolkata")),
            null, null, null, "Refund", "", emptyList(), app.vyaya.core.model.EntrySource.MANUAL, null, emptyList(), null, now, now, false))) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now + kotlin.time.Duration.parse("1d") }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        waitFor("Nothing safe to spend until your friends pay you back: they owe you ₹800, ₹200 more than what's left")
        assertEquals(0, compose.onAllNodes(hasText("Bills still due", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun split_flaw_003_save_waits_until_the_shares_are_read() {
        val saved = mutableListOf<app.vyaya.ui.EntryInput>()
        val split = runBlocking { dinnerSplit() }
        compose.setContent {
            app.vyaya.ui.AddEntryScreen(split, runBlocking { CatalogStore(db).categories() }, kotlinx.datetime.LocalDate(2026, 11, 18), onSave = { true },
                onDelete = {}, onClose = {}, onSaveSplit = { saved += it; null }, shares = null)
        }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor("One moment: still reading who shared it")
        assertEquals(0, saved.size)
    }
}
