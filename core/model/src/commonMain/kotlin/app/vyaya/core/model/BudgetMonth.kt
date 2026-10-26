package app.vyaya.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The user's budgeting month: `[start, endExclusive)`.
 *
 * Starts on [startDay] of each calendar month (1 = calendar month, 25 = payday, ...). When a month is
 * shorter than [startDay], it starts on that month's last day (start day 31 → 28/29 Feb, 30 Apr).
 *
 * This is the single place that decides which month an entry belongs to (CLAUDE.md rule 8.3);
 * "left this month", budgets, recurring entries and month-end Saved all go through it.
 */
data class BudgetMonth(val start: LocalDate, val endExclusive: LocalDate, val startDay: Int) {

    /** The last day of this month; month-end Saved is logged on this date. */
    val lastDay: LocalDate get() = endExclusive.minus(1, DateTimeUnit.DAY)

    operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive

    fun next(): BudgetMonth = containing(endExclusive, startDay)

    fun previous(): BudgetMonth = containing(start.minus(1, DateTimeUnit.DAY), startDay)

    companion object {

        fun containing(date: LocalDate, startDay: Int): BudgetMonth {
            require(startDay in 1..31) { "startDay must be 1..31, was $startDay" }
            val thisMonthStart = startIn(date.year, date.month.ordinal + 1, startDay)
            val start = if (date >= thisMonthStart) {
                thisMonthStart
            } else {
                val previous = firstOfMonth(date).minus(1, DateTimeUnit.MONTH)
                startIn(previous.year, previous.month.ordinal + 1, startDay)
            }
            val following = firstOfMonth(start).plus(1, DateTimeUnit.MONTH)
            val end = startIn(following.year, following.month.ordinal + 1, startDay)
            return BudgetMonth(start, end, startDay)
        }

        /** Assigns a moment in time to a month using the user's [timeZone], not the device's or UTC. */
        fun containing(instant: Instant, timeZone: TimeZone, startDay: Int): BudgetMonth =
            containing(instant.toLocalDateTime(timeZone).date, startDay)

        private fun firstOfMonth(date: LocalDate) = LocalDate(date.year, date.month, 1)

        private fun startIn(year: Int, month: Int, startDay: Int): LocalDate {
            val first = LocalDate(year, month, 1)
            val daysInMonth = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
            return LocalDate(year, month, minOf(startDay, daysInMonth))
        }
    }
}
