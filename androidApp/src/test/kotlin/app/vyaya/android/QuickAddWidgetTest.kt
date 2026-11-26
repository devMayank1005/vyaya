package app.vyaya.android

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.TextView
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.QuickLookStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.SafeToSpend
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The home-screen widget and the Quick Settings tile (Phase 3): quick add, and amounts only when the user allows them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickAddWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val now = Instant.parse("2026-11-18T06:30:00Z")
    private val manager = AppWidgetManager.getInstance(context)

    /** The redraw a placed widget starts in the background is over before the test goes on, or ends. */
    @After fun close() { runBlocking { QuickAddWidget.awaitIdle() }; db.close() }

    private fun place(): Int = shadowOf(manager).createWidget(QuickAddWidget::class.java, R.layout.widget_quick_add)
        .also { runBlocking { QuickAddWidget.awaitIdle() } }

    /** What the widget would show, drawn into a view as the launcher draws it. */
    private fun lineShown(locked: Boolean): String {
        val line = runBlocking { QuickAddWidget.lineFor(db, locked, now) }
        val drawn = QuickAddWidget.views(context, line).apply(context, android.widget.FrameLayout(context))
        return drawn.findViewById<TextView>(R.id.widget_line).text.toString()
    }

    private fun salary() = runBlocking {
        EntryStore(db).save(listOf(Entry(EntityId.random(), EntryType.INCOME, Money(30_000_00), CapturedAt(Instant.parse("2026-11-01T06:00:00Z"),
            TimeZone.of("Asia/Kolkata")), null, null, null, "Salary", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, now, now, false)))
    }

    @Test
    fun amounts_are_hidden_by_default() {
        salary()
        assertEquals(WidgetText.HIDDEN, lineShown(locked = false))
    }

    @Test
    fun amounts_show_once_allowed_and_never_while_the_app_lock_is_on() {
        salary()
        runBlocking { QuickLookStore(db).setShowAmounts(true) }
        assertEquals("₹2,307 left for today", lineShown(locked = false)) // ₹30,000 over the 13 days left, rounded down
        assertEquals(WidgetText.HIDDEN, lineShown(locked = true))
    }

    @Test
    fun the_add_button_and_the_tile_open_the_add_form_in_the_app() {
        val add = shadowOf(QuickAddWidget.addIntent(context))
        assertEquals(MainActivity::class.java.name, add.savedIntent.component?.className)
        assertEquals(QuickAddWidget.ACTION_ADD, add.savedIntent.action)
        assertTrue("immutable, so no other app can change where it goes", QuickAddWidget.addIntent(context).isImmutable)
        val tile = Robolectric.setupService(QuickAddTile::class.java)
        val launched = mutableListOf<android.app.PendingIntent>()
        tile.launch = { launched += it } // the system's collapse-and-open needs the real Quick Settings panel
        tile.onClick()
        assertEquals(QuickAddWidget.ACTION_ADD, shadowOf(launched.single()).savedIntent.action)
    }

    @Test
    fun the_widgets_wording() {
        val safe = SafeToSpend(Money(10_000_00), Money(0), 10, Money(1_000_00), Money(0), leftToday = Money(289_00))
        assertEquals(WidgetText.HIDDEN, WidgetText.line(safe, showAmounts = false, locked = false))
        assertEquals(WidgetText.HIDDEN, WidgetText.line(safe, showAmounts = true, locked = true))
        assertEquals(WidgetText.HIDDEN, WidgetText.line(null, showAmounts = true, locked = false))
        assertEquals("₹289 left for today", WidgetText.line(safe, showAmounts = true, locked = false))
        assertEquals("₹120 over today's amount", WidgetText.line(safe.copy(leftToday = Money(-120_00)), showAmounts = true, locked = false))
        assertEquals("Nothing left to spend this month", WidgetText.line(safe.copy(left = Money(0), perDay = Money(0), leftToday = Money(0)), showAmounts = true, locked = false))
    }

    @Test
    fun a_placed_widget_is_drawn_by_the_systems_update() {
        val id = place()
        runBlocking { QuickAddWidget.update(context, manager, intArrayOf(id), db, locked = true, now = now) }
        assertEquals(WidgetText.HIDDEN, shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_line).text.toString())
        assertTrue(shadowOf(manager).getViewFor(id).findViewById<android.view.View>(R.id.widget_add) != null)
    }

    // --- widget report 2026-11-18 ------------------------------------------------------------------------------------

    @Test
    fun widget_flaw_001_a_redraw_is_booked_for_the_next_midnight_at_home() {
        val id = place()
        runBlocking { QuickAddWidget.update(context, manager, intArrayOf(id), db, locked = false, now = now) }
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        val next = shadowOf(alarms).scheduledAlarms.single()
        // 18 Nov 12:00 in India: the next redraw is at 19 Nov 00:00 there (18 Nov 18:30 UTC), a minute after at most.
        val midnight = Instant.parse("2026-11-18T18:30:00Z").toEpochMilliseconds()
        assertTrue("${next.triggerAtTime}", next.triggerAtTime in midnight..(midnight + 60_000))
        val intent = shadowOf(next.operation).savedIntent
        assertEquals(QuickAddWidget::class.java.name, intent.component?.className)
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_UPDATE, intent.action)
        assertTrue(next.operation!!.isImmutable)
    }

    @Test
    fun widget_flaw_001_the_phones_time_or_zone_changing_redraws_it() {
        listOf(android.content.Intent.ACTION_TIME_CHANGED, android.content.Intent.ACTION_TIMEZONE_CHANGED).forEach { action ->
            val found = context.packageManager.queryBroadcastReceivers(android.content.Intent(action).setPackage(context.packageName), 0)
            assertEquals(action, listOf(QuickAddWidget::class.java.name), found.map { it.activityInfo.name })
        }
    }

    @Test
    fun widget_sec_001_turning_the_lock_on_hides_the_amount_at_once_and_a_failed_update_draws_it_hidden() {
        val id = place()
        fun shown() = shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_line).text.toString()
        manager.updateAppWidget(intArrayOf(id), QuickAddWidget.views(context, "₹500 left for today"))
        QuickAddWidget.hideNow(context) // synchronous: no database read, nothing to wait for
        assertEquals(WidgetText.HIDDEN, shown())
        manager.updateAppWidget(intArrayOf(id), QuickAddWidget.views(context, "₹500 left for today"))
        val broken = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build().also { it.close() }
        runBlocking { QuickAddWidget.update(context, manager, intArrayOf(id), broken, locked = false, now = now) } // must not throw
        assertEquals(WidgetText.HIDDEN, shown())
    }

    @Test
    fun widget_pen_001_many_redraw_requests_read_the_month_at_most_twice_and_only_placed_widgets_are_drawn() {
        var reads = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val jobs = List(100) { QuickAddWidget.redraw { reads++; gate.await() } }
        gate.complete(Unit)
        runBlocking { jobs.forEach { it.join() } }
        assertTrue("$reads", reads in 1..2)
        val id = place()
        assertEquals(listOf(id), QuickAddWidget.placed(context, manager).toList())
    }

    @Test
    fun widget_flaw_003_nothing_safe_says_why() {
        val none = SafeToSpend(Money(0), Money(0), 10, Money(0), Money(0))
        assertEquals("₹0 today: bills still due", WidgetText.line(none.copy(billsDue = Money(15_000_00), shortBy = Money(5_000_00)), true, false))
        assertEquals("₹0 today: friends owe you ₹800", WidgetText.line(none.copy(lent = Money(800_00), shortBy = Money(200_00)), true, false))
        assertEquals("Nothing left to spend this month", WidgetText.line(none, true, false))
    }
}
