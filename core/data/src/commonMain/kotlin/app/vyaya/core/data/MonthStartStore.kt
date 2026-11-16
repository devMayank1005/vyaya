package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.engine.SalaryDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The month's start day (safe-to-spend FLAW-002): the user's setting, and the salary day suggested when the salary
 * comes on about the same day each month. A change takes effect from the next month ([app.vyaya.core.model.MonthSchedule.withStartDay]):
 * months already lived keep their dates. "Not now" is kept on this phone only, per suggested day.
 */
class MonthStartStore(private val db: VyayaDatabase) {

    private val snapshots = SnapshotStore(db)

    /** The start day of the month [date] is in. */
    suspend fun startDay(date: LocalDate): Int = snapshots.loadRecords().settings.monthSchedule.monthContaining(date).startDay

    /** From the month after [today]'s, months start on [day] (1–31; the last day in shorter months). */
    suspend fun setStartDay(day: Int, today: LocalDate) {
        val settings = snapshots.loadRecords().settings
        snapshots.save(Snapshot.empty().copy(settings = settings.copy(monthSchedule = settings.monthSchedule.withStartDay(day, today))))
    }

    /** The salary day to suggest, unless the user put it off. Reads the last 4 calendar months' entries only. */
    suspend fun salarySuggestion(today: LocalDate): Int? {
        val settings = snapshots.loadRecords().settings
        val from = LocalDate(today.year, today.month, 1).minus(4, DateTimeUnit.MONTH).atStartOfDayIn(TimeZone.UTC)
        val to = today.plus(2, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
        val day = SalaryDay.suggest(EntryStore(db).inRange(from, to), settings.monthSchedule, today) ?: return null
        return if (db.prefs().get(NOT_NOW) == day.toString()) null else day
    }

    /** "Not now" for starting on [day]: not suggested again unless the salary day moves. */
    suspend fun notNow(day: Int) = db.prefs().put(PrefRow(NOT_NOW, day.toString()))

    private companion object {
        const val NOT_NOW = "salaryDay.notNow"
    }
}
