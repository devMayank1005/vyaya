package app.vyaya.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.vyaya.core.data.QuickLookStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.SafeToSpend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The home-screen widget (Phase 3): "Vyaya", one line and an Add button that opens the add form (through the app, so
 * the app lock still asks). The line shows safe to spend today only when the user turned amounts on in settings, and
 * never while the app lock is on: anyone who sees the home screen sees the widget.
 *
 * Widget report 2026-11-18: redrawn at the next midnight at home and when the phone's time or zone changes (#1); hidden
 * at once when the lock turns on, and hidden when an update can't be worked out (#2); the sender's widget ids are
 * ignored and requests are coalesced, so another app's broadcasts can't make it work hard (#3).
 */
class QuickAddWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        // Protected broadcasts: only the system sends these.
        if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            return onUpdate(context, manager, placed(context, manager))
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Null when called outside a broadcast (a launcher or test calling onUpdate directly): then nothing to finish.
        val pending: PendingResult? = goAsync()
        // The ids a broadcast names are the sender's; only this app's placed widgets are drawn.
        refresh(context).invokeOnCompletion { pending?.finish() }
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
    }

    companion object {
        /** Opens the add form (the widget's Add, and the Quick Settings tile). */
        const val ACTION_ADD = "app.vyaya.action.ADD"

        /** At most one redraw at a time, and at least this long apart; requests meanwhile become one more. */
        private const val MIN_GAP_MS = 1_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private var running: Job? = null
        /** The latest redraw asked for while one runs: it runs next (the others it replaces are the same redraw). */
        private var next: (suspend () -> Unit)? = null

        /** To MainActivity only, and immutable, so no other app can redirect it. */
        fun addIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_ADD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** The system's own update, to this widget only: what the midnight alarm sends. */
        private fun midnightIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 1,
            Intent(context, QuickAddWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** This app's widgets on the home screen. */
        fun placed(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, QuickAddWidget::class.java)) ?: IntArray(0)

        /** The widget's line: amounts only when allowed and the app lock is off (nothing is read for the line otherwise). */
        suspend fun lineFor(db: VyayaDatabase, locked: Boolean, now: Instant): String {
            val look = QuickLookStore(db)
            val show = !locked && look.showAmounts()
            return WidgetText.line(if (show) look.safeToSpend(now) else null, show, locked)
        }

        fun views(context: Context, line: String): RemoteViews = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            setTextViewText(R.id.widget_line, line)
            setOnClickPendingIntent(R.id.widget_add, addIntent(context))
            setOnClickPendingIntent(R.id.widget_root, addIntent(context))
        }

        /** Draws [ids]; if the line can't be worked out, draws it hidden rather than leaving an old amount. */
        suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray, db: VyayaDatabase, locked: Boolean, now: Instant) {
            if (ids.isEmpty()) return
            val line = try {
                lineFor(db, locked, now)
            } catch (e: CancellationException) {
                // Our own cancellation goes on; one from inside (a closed database cancels its work) is a failure.
                currentCoroutineContext().ensureActive()
                WidgetText.HIDDEN
            } catch (e: Exception) {
                WidgetText.HIDDEN
            }
            manager.updateAppWidget(ids, views(context, line))
            runCatching { bookMidnight(context, db, now) }
        }

        /** The next redraw at midnight at home (inexact, allowed while the phone sleeps; no permission needed). */
        private suspend fun bookMidnight(context: Context, db: VyayaDatabase, now: Instant) {
            val home = SnapshotStore(db).loadRecords().settings.homeTimeZone
            val tomorrow = now.toLocalDateTime(home).date.plus(1, DateTimeUnit.DAY)
            val at = tomorrow.atStartOfDayIn(home).toEpochMilliseconds()
            context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC, at, midnightIntent(context))
        }

        /** Hidden at once, with no database read: the app lock was just turned on. */
        fun hideNow(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = placed(context, manager)
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, WidgetText.HIDDEN))
        }

        /**
         * Runs [work] now, or once more after the redraw already running (however many ask meanwhile), at least
         * [MIN_GAP_MS] apart.
         */
        fun redraw(work: suspend () -> Unit): Job = synchronized(lock) {
            running?.takeIf { it.isActive }?.let { next = work; return it }
            scope.launch {
                var current: (suspend () -> Unit)? = work
                while (current != null) {
                    runCatching { current() }
                    delay(MIN_GAP_MS)
                    current = synchronized(lock) { next.also { next = null }.also { if (it == null) running = null } }
                }
            }.also { running = it }
        }

        /** Waits until no redraw is running or waiting (tests: a redraw left running would touch the next test's state). */
        internal suspend fun awaitIdle() {
            while (true) (synchronized(lock) { running?.takeIf { it.isActive } } ?: return).join()
        }

        /** Redraws this app's placed widgets (after a change in the app, a capture, the setting, or the system asking). */
        fun refresh(context: Context): Job {
            val app = context.applicationContext as VyayaApp
            return redraw {
                val manager = AppWidgetManager.getInstance(context) ?: return@redraw
                update(context, manager, placed(context, manager), app.database, app.appLock.enabled, Clock.System.now())
            }
        }
    }
}

/** The widget's one line. */
object WidgetText {
    const val HIDDEN = "Tap Add to log a payment"

    fun line(safe: SafeToSpend?, showAmounts: Boolean, locked: Boolean): String = when {
        !showAmounts || locked || safe == null -> HIDDEN
        safe.perDay.paise > 0 && safe.leftToday.paise >= 0 -> "${safe.leftToday.format()} left for today"
        safe.perDay.paise > 0 -> "${app.vyaya.core.model.Money(-safe.leftToday.paise).format()} over today's amount"
        // Widget report #5: why there's nothing, as Home says it (shorter).
        safe.billsDue.paise > 0 && safe.shortBy.paise > 0 -> "₹0 today: bills still due"
        safe.lent.paise > 0 && safe.shortBy.paise > 0 -> "₹0 today: friends owe you ${safe.lent.format()}"
        else -> "Nothing left to spend this month"
    }
}
