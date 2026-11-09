package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.LedgerStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.Money
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The People tab over a real database: who owes whom, adding what was lent, a pay-back, settling up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleScreenTest {

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-09T10:00:00Z")

    @After fun close() { compose.waitForIdle(); db.close() }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun the_people_tab_shows_balances_and_a_person_can_be_settled() {
        runBlocking {
            val ledger = LedgerStore(db)
            val rahul = ledger.addPerson("Rahul", now)
            ledger.add(rahul.id, LedgerDirection.LENT, Money(500_00), LocalDate(2026, 11, 9), "Dinner", now)
        }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("People").performClick()
        waitFor("Others owe you ₹500")
        compose.onNodeWithText("Rahul").performClick()
        waitFor("Rahul owes you ₹500")
        compose.onNodeWithText("Paid back").performClick()
        compose.onNodeWithText("Amount").performTextInput("300")
        compose.onNodeWithText("Save").performClick()
        waitFor("Rahul owes you ₹200")
        compose.onNodeWithText("Settle all").performClick()
        waitFor("All settled with Rahul")
        assertEquals(Money(0), runBlocking { LedgerStore(db).balances().owedToYou })
    }

    @Test
    fun a_person_and_what_you_lent_them_can_be_added() {
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("People").performClick()
        waitFor("Add person")
        compose.onNodeWithText("Add person", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput("Priya")
        compose.onNodeWithText("Save").performClick()
        waitFor("Priya")
        compose.onNodeWithText("Priya").performClick()
        compose.onNodeWithText("I lent").performClick()
        compose.onNodeWithText("Amount").performTextInput("1200")
        compose.onNodeWithText("Note").performTextInput("Cab")
        compose.onNodeWithText("Save").performClick()
        waitFor("Priya owes you ₹1,200")
        assertEquals(Money(1_200_00), runBlocking { LedgerStore(db).balances().owedToYou })
    }

    private fun withRahul(owes: Long) = runBlocking {
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", now)
        ledger.add(rahul.id, LedgerDirection.LENT, Money(owes * 100), LocalDate(2026, 11, 9), "Tea", now)
        rahul
    }

    private fun openRahul() {
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("People").performClick()
        waitFor("Rahul")
        compose.onNodeWithText("Rahul").performClick()
        waitFor("Rahul owes you")
    }

    @Test
    fun mvp2_data_003_paying_back_more_than_is_owed_says_so() {
        withRahul(200)
        openRahul()
        compose.onNodeWithText("Paid back").performClick()
        compose.onNodeWithText("Amount").performTextInput("2000")
        compose.onNodeWithText("Save").performClick()
        waitFor("Rahul owes you ₹200; use Settle all")
        assertEquals(Money(200_00), runBlocking { LedgerStore(db).balances().owedToYou })
    }

    @Test
    fun mvp2_flaw_004_an_entry_can_be_corrected_or_deleted() {
        withRahul(200)
        openRahul()
        compose.onNodeWithText("Tea").performClick()
        compose.onNodeWithText("200").performTextReplacement("250")
        compose.onNodeWithText("Save").performClick()
        waitFor("Rahul owes you ₹250")
        waitFor("Tea")
        compose.onNodeWithText("Tea").performClick()
        compose.onNodeWithText("Delete").performClick()
        waitFor("All settled with Rahul")
    }

    @Test
    fun mvp2_flaw_005_a_person_can_be_renamed_and_removed_once_settled() {
        withRahul(200)
        openRahul()
        compose.onNodeWithText("Remove").assertDoesNotExist() // open balance: settle first
        compose.onNodeWithText("Settle all first to remove", substring = true).assertExists()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rahul").performTextReplacement("Rahul S")
        compose.onNodeWithText("Save").performClick()
        waitFor("Rahul S owes you")
        compose.onNodeWithText("Settle all").performClick()
        waitFor("All settled with Rahul S")
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { runBlocking { LedgerStore(db).people() }.isEmpty() }
    }
}
