package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
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
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** core-data DATA-006: a damaged database opens, says what was left out, and leads to backup and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DamagedDataScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-26T06:00:00Z")

    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun data_006_home_says_what_couldnt_be_read_and_leads_to_backup() {
        runBlocking {
            AppOpen(db).run(now)
            val ok = Entry(EntityId.random(), EntryType.EXPENSE, Money(250_00), CapturedAt(now, TimeZone.of("Asia/Kolkata")), null, null, null,
                "Swiggy", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, now, now, false)
            val bad = ok.copy(id = EntityId.random(), payee = "Chai")
            EntryStore(db).save(listOf(ok, bad))
            db.entries().upsert(listOf(EntryStore.row(bad).copy(line = "{broken")))
        }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        waitFor("Out ₹250") // the good entry only: the damaged ₹90 one is left out
        waitFor("1 record couldn't be read and is left out")
        compose.onNodeWithText("Backup and restore").performScrollTo().performClick()
        waitFor("Backup and privacy")
    }
}
