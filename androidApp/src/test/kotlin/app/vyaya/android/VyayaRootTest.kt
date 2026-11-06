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
import app.vyaya.android.ui.Setup
import app.vyaya.android.ui.VyayaRoot
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.VyayaDatabase
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

/** The app over a real database: first open, add by hand, see it on the home screen, edit and delete it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VyayaRootTest {

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-06T09:15:00Z")

    @After fun close() = db.close()

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun add_an_entry_by_hand_see_it_edit_it_and_delete_it() {
        runBlocking { AppOpen(db).run(now) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        waitFor("No entries this month")

        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Amount").performTextInput("250")
        compose.onNodeWithText("Food & dining").performClick()
        compose.onNodeWithText("Payee").performTextInput("Swiggy")
        compose.onNodeWithText("Save").performClick()
        waitFor("Out ₹250")
        compose.onNodeWithText("-₹250").assertExists()

        compose.onNodeWithText("Swiggy").performClick() // edit
        compose.onNodeWithText("250").performTextReplacement("300")
        compose.onNodeWithText("Save").performClick()
        waitFor("Out ₹300")
        assertEquals(1, runBlocking { EntryStore(db).all() }.size)

        compose.onNodeWithText("Swiggy").performClick()
        compose.onNodeWithText("Delete").performClick()
        waitFor("No entries this month")
        assertTrue(runBlocking { EntryStore(db).all().single().deleted })
    }

    @Test
    fun the_detected_tab_shows_the_inbox() {
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.onNodeWithText("Detected").performClick()
        waitFor("Nothing detected yet")
    }
}
