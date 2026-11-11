package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.After
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

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-11T06:00:00Z")

    @After fun close() { compose.waitForIdle(); db.close() }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

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
        assertEquals(listOf("swiggy" to food().id), runBlocking { MerchantStore(db).rules() }.map { it.payee to it.categoryId })
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
}
