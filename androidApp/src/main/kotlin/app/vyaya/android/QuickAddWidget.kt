package app.vyaya.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.vyaya.core.data.QuickLookStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.SafeToSpend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The home-screen widget (Phase 3): "Vyaya", one line and an Add button that opens the add form (through the app, so
 * the app lock still asks). The line shows safe to spend today only when the user turned amounts on in settings, and
 * never while the app lock is on: anyone who sees the home screen sees the widget.
 */
class QuickAddWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as VyayaApp
        // Null when called outside a broadcast (a launcher or test calling onUpdate directly): then nothing to finish.
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                update(context, manager, ids, app.database, locked = app.appLock.enabled, now = Clock.System.now())
            } catch (e: Exception) {
                // Left as it was drawn last; the next change or the half-hourly update tries again.
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        /** Opens the add form (the widget's Add, and the Quick Settings tile). */
        const val ACTION_ADD = "app.vyaya.action.ADD"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** To MainActivity only, and immutable, so no other app can redirect it. */
        fun addIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_ADD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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

        suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray, db: VyayaDatabase, locked: Boolean, now: Instant) {
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context, lineFor(db, locked, now)))
        }

        /** Redraws any widgets placed (after a change in the app, a capture, or the setting). */
        fun refresh(context: Context) {
            val app = context.applicationContext as VyayaApp
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickAddWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch {
                runCatching { update(context, manager, ids, app.database, app.appLock.enabled, Clock.System.now()) }
            }
        }
    }
}

/** The widget's one line. */
object WidgetText {
    const val HIDDEN = "Tap Add to log a payment"

    fun line(safe: SafeToSpend?, showAmounts: Boolean, locked: Boolean): String = when {
        !showAmounts || locked || safe == null -> HIDDEN
        safe.perDay.paise <= 0 -> "Nothing safe to spend this month"
        safe.leftToday.paise >= 0 -> "${safe.leftToday.format()} left for today"
        else -> "${app.vyaya.core.model.Money(-safe.leftToday.paise).format()} over today's amount"
    }
}
