package app.vyaya.android

import android.content.Context
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
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.EntryStore
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
}
