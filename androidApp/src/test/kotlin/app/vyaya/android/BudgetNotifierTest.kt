package app.vyaya.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.BudgetStore
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Budgets report #2: a notification when a captured payment takes a budget to 80 % or past it, once a month each. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BudgetNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private var now = Instant.parse("2026-11-16T06:00:00Z")
    private var locked = false
    private val notifier = BudgetNotifier(context, db, locked = { locked }, clock = { now })
    private val shadow get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @After fun close() = db.close()

    private fun food() = runBlocking { CatalogStore(db).categories() }.first { it.name == "Food & dining" }

    private fun spend(rupees: Long) = runBlocking {
        EntryStore(db).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(rupees * 100), CapturedAt(now, TimeZone.of("Asia/Kolkata")),
            null, null, food().id, "Cafe", "", emptyList(), EntrySource.NOTIFICATION, null, emptyList(), null, now, now, false)))
        notifier.check()
    }

    private fun texts() = shadow.allNotifications.map {
        it.extras.getString(Notification.EXTRA_TITLE) to it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    private fun budget(rupees: Long) = runBlocking { AppOpen(db).run(now); BudgetStore(db).set(food(), Money(rupees * 100), now) }

    @Test
    fun budgets_flaw_002_80_percent_is_said_once_then_over_once() {
        budget(5_000)
        spend(3_000)
        assertEquals(emptyList<Pair<String?, String?>>(), texts())
        spend(1_100)
        assertEquals(listOf("Food & dining: 82% of the budget used" to "₹4,100 of ₹5,000 this month. ₹900 left."), texts())
        context.getSystemService(NotificationManager::class.java).cancelAll()
        spend(100)
        assertEquals("near again: not said twice in a month", emptyList<Pair<String?, String?>>(), texts())
        spend(1_000)
        assertEquals(listOf("Food & dining is over budget" to "₹5,200 of ₹5,000 this month: ₹200 over."), texts())
    }

    @Test
    fun budgets_flaw_002_while_the_app_lock_is_on_nothing_private_is_shown() {
        budget(5_000)
        locked = true
        spend(4_500)
        assertEquals(listOf("Vyaya" to "A budget is nearly used up"), texts())
        val n = shadow.allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
    }

    @Test
    fun budgets_flaw_002_a_new_month_gets_a_fresh_alert_and_no_budget_says_nothing() {
        runBlocking { AppOpen(db).run(now) }
        spend(9_000)
        assertEquals("no budgets: nothing", 0, shadow.allNotifications.size)
        budget(5_000)
        spend(0 + 1) // past 80 % already with the 9,000
        assertEquals(1, shadow.allNotifications.size)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        now = Instant.parse("2026-12-03T06:00:00Z")
        spend(4_500)
        assertEquals(listOf("Food & dining: 90% of the budget used" to "₹4,500 of ₹5,000 this month. ₹500 left."), texts())
    }

    @Test
    fun budgets_flaw_002_captures_call_it() {
        budget(100)
        val capture = CaptureStore(db, afterSave = { notifier.check() })
        runBlocking {
            capture.process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹150 paid to Swiggy", CapturedAt(now, TimeZone.of("Asia/Kolkata"))), now)
        }
        // The payment has no category (no merchant memory), so Food is untouched: nothing to say.
        assertEquals(0, shadow.allNotifications.size)
    }
}
