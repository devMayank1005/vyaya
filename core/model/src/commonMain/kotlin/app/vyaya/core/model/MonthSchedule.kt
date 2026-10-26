package app.vyaya.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One change of the month start day.
 *
 * The first month under the new setting is `[effectiveFrom, transitionEnd)`, and normal months follow from
 * [transitionEnd] onwards.
 */
data class StartDayChange(val effectiveFrom: LocalDate, val transitionEnd: LocalDate, val startDay: Int)

/**
 * The history of the user's month start day (FLAW-003).
 *
 * A change made today never moves past months: it takes effect when the current month ends. That first
 * month runs from the old boundary to the next new-style boundary. If that would be shorter than
 * [MIN_TRANSITION_DAYS], it runs to the boundary after that instead.
 *
 * Create one with [of] and change it with [withStartDay], so the invariants always hold.
 */
data class MonthSchedule private constructor(val initialStartDay: Int, val changes: List<StartDayChange>) {

    fun monthContaining(date: LocalDate): BudgetMonth {
        val change = changes.lastOrNull { it.effectiveFrom <= date }
            ?: return BudgetMonth.containing(date, initialStartDay)
        return if (date < change.transitionEnd) {
            BudgetMonth(change.effectiveFrom, change.transitionEnd, change.startDay)
        } else {
            BudgetMonth.containing(date, change.startDay)
        }
    }

    fun monthContaining(instant: Instant, timeZone: TimeZone): BudgetMonth =
        monthContaining(instant.toLocalDateTime(timeZone).date)

    fun next(month: BudgetMonth): BudgetMonth = monthContaining(month.endExclusive)

    fun previous(month: BudgetMonth): BudgetMonth = monthContaining(month.start.minus(1, DateTimeUnit.DAY))

    /** The schedule after the user picks [newStartDay] on [changedOn]. */
    fun withStartDay(newStartDay: Int, changedOn: LocalDate): MonthSchedule {
        requireStartDay(newStartDay)
        val effectiveFrom = monthContaining(changedOn).endExclusive
        // A change that has not taken effect yet is replaced by this one.
        val kept = changes.filter { it.effectiveFrom < effectiveFrom }
        val dayInEffect = kept.lastOrNull()?.startDay ?: initialStartDay
        if (newStartDay == dayInEffect) return MonthSchedule(initialStartDay, kept)

        var transitionEnd = BudgetMonth.containing(effectiveFrom, newStartDay).endExclusive
        if (effectiveFrom.daysUntil(transitionEnd) < MIN_TRANSITION_DAYS) {
            transitionEnd = BudgetMonth.containing(transitionEnd, newStartDay).endExclusive
        }
        return MonthSchedule(initialStartDay, kept + StartDayChange(effectiveFrom, transitionEnd, newStartDay))
    }

    companion object {
        const val MIN_TRANSITION_DAYS = 7

        fun of(startDay: Int): MonthSchedule {
            requireStartDay(startDay)
            return MonthSchedule(startDay, emptyList())
        }

        private fun requireStartDay(startDay: Int) =
            require(startDay in 1..31) { "startDay must be 1..31, was $startDay" }
    }
}
