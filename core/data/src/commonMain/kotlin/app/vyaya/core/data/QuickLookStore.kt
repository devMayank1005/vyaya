package app.vyaya.core.data

import app.vyaya.core.engine.SafeToSpend
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The home-screen widget and Home's summary (Phase 3): safe to spend today, worked out one way for both, and whether the
 * widget may show amounts. That choice is kept on the phone (the prefs table), never in a backup, and is off until the
 * user turns it on (their decision: hidden by default).
 */
class QuickLookStore(private val db: VyayaDatabase) {

    suspend fun showAmounts(): Boolean = db.prefs().get(SHOW_AMOUNTS) == "true"

    suspend fun setShowAmounts(on: Boolean) = db.prefs().put(PrefRow(SHOW_AMOUNTS, on.toString()))

    /** Safe to spend for the month [now] is in, in the home time zone: bills still due, lending still owed, today's spending. */
    suspend fun safeToSpend(now: Instant): SafeToSpend? {
        val records = SnapshotStore(db).loadRecords()
        val settings = records.settings
        val today = now.toLocalDateTime(settings.homeTimeZone).date
        val month = settings.monthSchedule.monthContaining(today)
        val view = MonthStore(db).watch(month, settings).first()
        val rules = RulesStore(db)
        return SafeToSpend.of(view.summary, records.recurring.filter { !it.deleted }, rules.madeKeys(), today, view.entries,
            rules.linkedPayments(), lent = SafeToSpend.openLent(records.ledger, month))
    }

    private companion object {
        const val SHOW_AMOUNTS = "widget.showAmounts"
    }
}
