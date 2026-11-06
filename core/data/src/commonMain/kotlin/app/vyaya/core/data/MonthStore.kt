package app.vyaya.core.data

import app.vyaya.core.engine.MonthSummary
import app.vyaya.core.model.BudgetMonth
import app.vyaya.core.model.Entry
import app.vyaya.core.model.Settings
import app.vyaya.core.model.monthOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One budget month for the home screen: in, out and left ([summary]), and its entries, newest first, without deleted ones. */
data class MonthView(val month: BudgetMonth, val summary: MonthSummary, val entries: List<Entry>)

/** A month's entries from the database, kept up to date (the home screen). */
class MonthStore(private val db: VyayaDatabase) {

    private val entries = EntryStore(db)

    /** The app's settings (month start, home time zone), without reading entries. */
    suspend fun settings(): Settings = SnapshotStore(db).loadRecords().settings

    fun watch(month: BudgetMonth, settings: Settings): Flow<MonthView> {
        // Each entry is in a month by its own date and zone (rule 8.3), up to 14 hours from UTC: read a day either side,
        // then let the month function decide.
        val from = month.start.minus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
        val to = month.endExclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
        val schedule = settings.monthSchedule
        return entries.watchRange(from, to).map { all ->
            val inMonth = all.filter { !it.deleted && schedule.monthOf(it.occurredAt) == month }
            MonthView(month, MonthSummary.of(inMonth, schedule, month), inMonth.sortedByDescending { it.occurredAt.instant })
        }
    }
}
