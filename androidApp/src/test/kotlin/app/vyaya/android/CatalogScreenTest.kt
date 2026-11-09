package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.CatalogResult
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.RulesStore
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Categories and accounts managed in Settings, and an account chosen for an entry (over a real database). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogScreenTest {

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-09T10:00:00Z")

    @After fun close() { compose.waitForIdle(); db.close() }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun app() {
        runBlocking { AppOpen(db).run(now) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
    }

    @Test
    fun a_category_is_added_and_deleted_in_settings() {
        app()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Categories").performScrollTo().performClick()
        waitFor("Food & dining")
        compose.onNodeWithText("Add category", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput("Pets")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { runBlocking { CatalogStore(db).categories() }.any { it.name == "Pets" } }
        // The list is lazy: "Pets" sorts below what fits on the test's small screen, so scroll to it.
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Pets"))
        compose.onNodeWithText("Pets").performClick()
        compose.onNodeWithText("Delete").performClick()
        waitFor("Yes, delete") // the dialog shows once it has counted the uses
        compose.onNodeWithText("Yes, delete").performClick()
        compose.waitUntil(5_000) { runBlocking { CatalogStore(db).categories() }.none { it.name == "Pets" } }
    }

    @Test
    fun an_account_is_added_and_chosen_for_an_entry() {
        app()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Accounts").performScrollTo().performClick()
        compose.onNodeWithText("Add account", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput("HDFC")
        compose.onNodeWithText("Last digits").performTextInput("1234")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { runBlocking { CatalogStore(db).accounts() }.isNotEmpty() }
        compose.onNodeWithText("‹ Settings").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Amount").performTextInput("250")
        compose.onNodeWithText("HDFC ·1234").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { runBlocking { EntryStore(db).all() }.isNotEmpty() }
        val hdfc = runBlocking { CatalogStore(db).accounts() }.single()
        assertEquals(hdfc.id, runBlocking { EntryStore(db).all() }.single().accountId)
    }

    private fun <T> saved(r: CatalogResult<T>): T = (r as CatalogResult.Saved<T>).item

    private fun pets(): Category = runBlocking { saved(CatalogStore(db).addCategory("Pets", CategoryKind.EXPENSE, now)) }

    private fun vet(category: Category) = runBlocking {
        EntryStore(db).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(800_00), CapturedAt(now, kotlinx.datetime.TimeZone.UTC), null, null,
            category.id, "Vet", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, now, now, false)))
    }

    private fun openList(name: String) {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText(name).performScrollTo().performClick()
    }

    private fun openPets() {
        openList("Categories")
        waitFor("Pets")
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Pets"))
        compose.onNodeWithText("Pets").performClick()
    }

    @Test
    fun catalog_data_001_an_accounts_type_and_last_digits_can_be_changed() {
        runBlocking { CatalogStore(db).addAccount("HDFC", AccountKind.BANK, "1234", now) }
        app()
        openList("Accounts")
        waitFor("HDFC")
        compose.onNodeWithText("HDFC", substring = true).performClick()
        compose.onNodeWithText("1234").performTextReplacement("5678")
        compose.onNodeWithText("Card").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { runBlocking { CatalogStore(db).accounts() }.single().mask == "5678" }
        assertEquals(AccountKind.CARD, runBlocking { CatalogStore(db).accounts() }.single().kind)
    }

    @Test
    fun catalog_flaw_009_the_form_shows_what_the_store_answered() {
        val pets = pets()
        app()
        openPets()
        runBlocking { CatalogStore(db).deleteCategory(pets, now) } // deleted meanwhile (another screen, a restore)
        compose.onNodeWithText("Pets").performTextReplacement("Pet care")
        compose.onNodeWithText("Save").performClick()
        waitFor("This was deleted meanwhile")
    }

    @Test
    fun catalog_flaw_005_007_delete_asks_first_and_says_what_uses_it() {
        val pets = pets()
        vet(pets)
        runBlocking { RulesStore(db).add(EntryType.EXPENSE, Money(500_00), 5, pets.id, "Pet food", now, kotlinx.datetime.TimeZone.UTC) }
        app()
        openPets()
        compose.onNodeWithText("Delete").performClick()
        waitFor("1 entry keeps it")
        waitFor("1 recurring rule still uses it")
        compose.onNodeWithText("Keep it").performClick()
        compose.waitForIdle()
        assertTrue(runBlocking { CatalogStore(db).categories() }.any { it.id == pets.id })
        compose.onNodeWithText("Delete").performClick()
        waitFor("Yes, delete") // the dialog shows once it has counted the uses
        compose.onNodeWithText("Yes, delete").performClick()
        compose.waitUntil(5_000) { runBlocking { CatalogStore(db).categories() }.none { it.id == pets.id } }
    }

    @Test
    fun catalog_flaw_006_an_entry_shows_its_own_deleted_category() {
        val pets = pets()
        vet(pets)
        runBlocking { CatalogStore(db).deleteCategory(pets, now) }
        app()
        waitFor("Vet")
        compose.onNodeWithText("Vet").performClick()
        waitFor("Pets (deleted)")
        compose.onNodeWithText("Pets (deleted)").performScrollTo().assertIsSelected()
    }

    @Test
    fun catalog_flaw_004_a_recurring_rule_can_have_an_account() {
        val hdfc = runBlocking { saved(CatalogStore(db).addAccount("HDFC", AccountKind.BANK, "1234", now)) }
        app()
        compose.onNodeWithText("Recurring").performClick()
        compose.onNodeWithText("Add rule").performClick()
        compose.onNodeWithText("Amount").performTextInput("15000")
        compose.onNodeWithText("Day of the month").performTextInput("5")
        compose.onNodeWithText("Name").performTextInput("Rent")
        compose.onNodeWithText("HDFC ·1234").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { RulesStore(db).active() }.isNotEmpty() }
        assertEquals(hdfc.id, runBlocking { RulesStore(db).active() }.single().accountId)
    }
}
