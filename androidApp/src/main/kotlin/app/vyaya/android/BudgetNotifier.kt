package app.vyaya.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.BudgetAlerts
import app.vyaya.core.engine.BudgetState
import app.vyaya.core.engine.Budgets
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Budgets report #2: after a capture, a notification for each budget that newly reached 80 % or went over this month,
 * once per budget, month and level (the keys said are kept in app-private preferences, this month's only).
 * While the app lock is on ([locked]), neither the category nor any amount is shown, and on the lock screen the
 * notification never shows its text (private, with a plain public version).
 */
class BudgetNotifier(
    private val context: Context,
    private val db: VyayaDatabase,
    private val locked: () -> Boolean,
    private val clock: () -> Instant,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun check() {
        val records = SnapshotStore(db).loadRecords()
        if (records.budgets.none { !it.deleted }) return
        val settings = records.settings
        val month = settings.monthSchedule.monthContaining(clock().toLocalDateTime(settings.homeTimeZone).date)
        val summary = MonthStore(db).watch(month, settings).first().summary
        val sent = prefs.getStringSet(SENT, emptySet()).orEmpty()
        val due = BudgetAlerts.due(Budgets.progress(records.budgets, summary, records.categories), month, sent)
        if (due.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        // Not allowed to notify (Android 13+ permission refused): not marked as said, so it can still come once allowed.
        if (!manager.areNotificationsEnabled()) return
        channel()
        val names = records.categories.associate { it.id to it.name }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (alert in due) {
            val (title, text) = alert.text(names[alert.progress.budget.categoryId].orEmpty(), locked())
            val hidden = alert.text("", locked = true)
            val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_vyaya)
                .setContentTitle(hidden.first).setContentText(hidden.second).build()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vyaya)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setPriority(if (alert.level == BudgetState.OVER) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
                .build()
            try {
                manager.notify(alert.progress.budget.id.value.hashCode(), n)
            } catch (e: SecurityException) {
                return // the permission was taken back meanwhile
            }
        }
        // Only this month's keys are kept: a new month starts fresh.
        val thisMonth = ":${month.start}:"
        prefs.edit().putStringSet(SENT, (sent.filter { thisMonth in it } + due.map { it.key }).toSet()).apply()
    }

    private fun channel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Budgets", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a budget is nearly used up or over its limit"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        }
    }

    private companion object {
        const val PREFS = "budget-alerts"
        const val SENT = "sent"
        const val CHANNEL = "budgets"
    }
}
