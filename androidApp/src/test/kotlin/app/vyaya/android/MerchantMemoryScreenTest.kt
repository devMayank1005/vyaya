package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.MerchantStore
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

/** Merchant memory from the screens: taught while categorising a detected payment, forgotten in Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MerchantMemoryScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()

    // The database closes after the screen is gone (outer rule): a reload still running after the last check (these
    // tests save, then the lists reload) is cancelled with the screen instead of reading a closed database.
    @get:Rule(order = 0) val closeDb = object : org.junit.rules.ExternalResource() { override fun after() = db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()
    private val now = Instant.parse("2026-11-11T06:00:00Z")


    // 10 s: these tests reload lists after each save, and the full gate runs them under heavy load.
    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun app(payee: String, source: EntrySource) {
        runBlocking {
            AppOpen(db).run(now)
            EntryStore(db).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(250_00), CapturedAt(now, kotlinx.datetime.TimeZone.UTC),
                null, null, null, payee, "", emptyList(), source, null, emptyList(), null, now, now, false)))
        }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
    }

    private fun food() = runBlocking { CatalogStore(db).categories() }.first { it.name == "Food & dining" }

    @Test
    fun merchant_memory_a_detected_payment_teaches_its_payee() {
        app("Swiggy", EntrySource.NOTIFICATION)
        waitFor("Swiggy")
        compose.onNodeWithText("Swiggy").performClick()
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Always put Swiggy in Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { MerchantStore(db).rules() }.isNotEmpty() }
        waitFor("Food & dining") // Home shows the entry in its category once the lists have reloaded
        compose.waitForIdle()
        assertEquals(listOf("Swiggy" to food().id), runBlocking { MerchantStore(db).rules() }.map { it.payee to it.categoryId })
    }

    @Test
    fun merchant_memory_is_not_offered_for_an_entry_typed_in() {
        app("Tea stall", EntrySource.MANUAL)
        waitFor("Tea stall")
        compose.onNodeWithText("Tea stall").performClick()
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Always put", substring = true).assertDoesNotExist()
    }

    @Test
    fun merchant_memory_a_remembered_payee_can_be_forgotten_in_settings() {
        runBlocking { AppOpen(db).run(now); MerchantStore(db).remember("Swiggy", food().id, now) }
        app("Swiggy", EntrySource.NOTIFICATION)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Remembered payees").performScrollTo().performClick()
        waitFor("Food & dining")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Swiggy"))
        compose.onNodeWithText("Swiggy").performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.waitUntil(5_000) { runBlocking { MerchantStore(db).rules() }.isEmpty() }
    }

    private fun captured(payee: String, category: EntityId? = null) = Entry(EntityId.random(), EntryType.EXPENSE, Money(99_00),
        CapturedAt(now, kotlinx.datetime.TimeZone.UTC), null, null, category, payee, "", emptyList(), EntrySource.NOTIFICATION, null, emptyList(), null, now, now, false)

    private fun shopping() = runBlocking { CatalogStore(db).categories() }.first { it.name == "Shopping" }

    @Test
    fun merchant_flaw_007_an_entry_says_its_category_was_remembered() {
        runBlocking { AppOpen(db).run(now); MerchantStore(db).remember("Swiggy", food().id, now); EntryStore(db).save(listOf(captured("Swiggy", food().id))) }
        compose.setContent { VyayaRoot(db, Setup(true, false, false), clock = { now }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {}) }
        waitFor("Swiggy")
        compose.onNodeWithText("Swiggy").performClick()
        compose.onNodeWithText("Food & dining: remembered for Swiggy").performScrollTo()
        compose.onNodeWithText("Always put", substring = true).assertDoesNotExist()
    }

    @Test
    fun merchant_flaw_002_choosing_another_category_says_what_is_remembered() {
        runBlocking { AppOpen(db).run(now); MerchantStore(db).remember("Swiggy", food().id, now) }
        app("Swiggy", EntrySource.NOTIFICATION)
        waitFor("Swiggy")
        compose.onNodeWithText("Swiggy").performClick()
        compose.onNodeWithText("Shopping").performScrollTo().performClick()
        compose.onNodeWithText("Swiggy goes in Food & dining. Always put it in Shopping instead").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { MerchantStore(db).rules() }.singleOrNull()?.categoryId == shopping().id }
        waitFor("Shopping"); compose.waitForIdle()
    }

    @Test
    fun merchant_flaw_003_004_the_form_counts_payments_and_can_bring_earlier_ones_along() {
        val earlier = listOf(captured("Swiggy"), captured("SWIGGY@axl"))
        runBlocking { AppOpen(db).run(now); EntryStore(db).save(earlier) }
        app("Swiggy", EntrySource.NOTIFICATION)
        waitFor("Out ₹448") // all three payments loaded (₹99 + ₹99 + ₹250)
        // On a small screen the summary fills the list's view, so scroll to a row before tapping it.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Swiggy"))
        compose.onAllNodes(hasText("Swiggy")).onFirst().performClick()
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        waitFor("3 payments to Swiggy so far")
        compose.onNodeWithText("Always put Swiggy in Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Also put the 2 other Swiggy payments with no category in Food & dining").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { EntryStore(db).all() }.all { it.categoryId == food().id } }
        waitFor("Food & dining"); compose.waitForIdle()
    }

    @Test
    fun merchant_flaw_004_a_very_short_name_is_flagged() {
        app("AK", EntrySource.NOTIFICATION)
        waitFor("AK")
        compose.onNodeWithText("AK").performClick()
        compose.onNodeWithText("Food & dining").performScrollTo().performClick()
        waitFor("Short name: other payees may match")
    }
}
