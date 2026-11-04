package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.android.ui.Setup
import app.vyaya.android.ui.VyayaScreen
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The main screen over the real inbox: a captured payment appears, and Undo removes it from the books. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VyayaScreenTest {

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val inbox = DetectedInbox(db)
    private val now = Instant.parse("2026-11-04T10:00:00Z")

    @After fun close() = db.close()

    @Test
    fun a_captured_payment_appears_and_undo_takes_it_out() {
        runBlocking {
            CaptureStore(db, inbox).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹120 paid to Swiggy", CapturedAt(now, TimeZone.of("Asia/Kolkata"))), now)
        }
        compose.setContent { VyayaScreen(inbox, Setup(true, false, false), clock = { now }, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Swiggy") == 1 }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Nothing detected yet") == 1 }
        assertTrue(runBlocking { EntryStore(db).all().single().deleted })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
