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
}
