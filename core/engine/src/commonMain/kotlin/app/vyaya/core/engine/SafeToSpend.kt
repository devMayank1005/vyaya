package app.vyaya.core.engine

import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.yearMonth

/**
 * Safe to spend per day (Phase 3, plan B2 "Understand"): what's left this month after the bills still due, spread over
 * the days left, today included. Calculated, never stored (rule 8.5); money in paise (8.1).
 *
 * - [left]: the month so far, income − spending ([MonthSummary.left]).
 * - [billsDue]: active spending rules due from today to the month's end whose entry for that month isn't made yet
 *   (a month skipped counts as made). Income still to come is not counted: the amount stays on the safe side.
 * - [perDay]: (left − billsDue) ÷ daysLeft, rounded down to whole rupees, never below zero.
 * - [shortBy]: how much the bills still due are more than what's left (zero when they fit).
 */
data class SafeToSpend(val left: Money, val billsDue: Money, val daysLeft: Int, val perDay: Money, val shortBy: Money) {

    companion object {
        /** Null unless [today] is in [summary]'s month: a past or future month has no "days left". */
        fun of(summary: MonthSummary, rules: List<RecurringRule>, made: Set<Generation>, today: LocalDate): SafeToSpend? {
            val month = summary.month
            if (today < month.start || today >= month.endExclusive) return null
            val last = month.endExclusive.minus(1, DateTimeUnit.DAY)
            // A budget month with a custom start day spans two calendar months; a rule is due once in each.
            val calendarMonths = listOf(today.yearMonth, last.yearMonth).distinct()
            var bills = 0L
            for (rule in rules) {
                if (rule.deleted || rule.paused || rule.type != EntryType.EXPENSE) continue
                for (ym in calendarMonths) {
                    val due = Recurring.dueOn(rule, ym)
                    if (due < today || due > last || due < rule.startsOn) continue
                    if (Generation(rule.id, ym) in made) continue
                    bills += rule.amount.paise
                }
            }
            val daysLeft = today.daysUntil(month.endExclusive)
            val room = summary.left.paise - bills
            val perDay = if (room <= 0) 0L else room / daysLeft / 100 * 100
            return SafeToSpend(summary.left, Money(bills), daysLeft, Money(perDay), Money(maxOf(0L, -room)))
        }
    }
}
