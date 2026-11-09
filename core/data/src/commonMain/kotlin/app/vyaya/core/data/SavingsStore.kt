package app.vyaya.core.data

import app.vyaya.core.engine.MonthEnd
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Every finished month: what they saved together, by how much the overspent ones went over, and from when. */
data class SavingsSummary(val saved: Money, val overspent: Money, val months: Int, val since: LocalDate?)

/**
 * Saved so far, from the database: each finished budget month's [MonthEnd] (calculated, never stored, rule 8.5), read
 * one month at a time, so years of entries are never all in memory.
 */
class SavingsStore(private val db: VyayaDatabase) {

    private val entries = EntryStore(db)

    suspend fun summary(settings: Settings, today: LocalDate): SavingsSummary {
        val earliest = db.entries().earliestMs() ?: return SavingsSummary(Money(0), Money(0), 0, null)
        val schedule = settings.monthSchedule
        // A day earlier, for entries recorded in a zone behind the home one; an empty month adds nothing.
        var month = schedule.monthContaining(Instant.fromEpochMilliseconds(earliest).toLocalDateTime(settings.homeTimeZone).date.minus(1, DateTimeUnit.DAY))
        var saved = Money(0); var overspent = Money(0); var months = 0; var since: LocalDate? = null
        while (today >= month.endExclusive) {
            val from = month.start.minus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
            val to = month.endExclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
            val end = MonthEnd.of(entries.inRange(from, to).filter { !it.deleted }, schedule, month, today)
            if (end != null && (end.summary.income.paise > 0 || end.summary.spent.paise > 0)) {
                saved += end.saved; overspent += end.overspent; months++
                if (since == null) since = month.start
            }
            month = schedule.next(month)
        }
        return SavingsSummary(saved, overspent, months, since)
    }
}
