package app.vyaya.core.engine

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
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
 * Safe to spend per day (Phase 3, plan B2 "Understand"): what's left this month, plus the income still expected, after
 * the bills still due, spread over the days left (today included). Calculated, never stored (rule 8.5); paise (8.1).
 *
 * - [left]: the month so far, income − spending ([MonthSummary.left]).
 * - [billsDue] / [expectedIncome]: active rules due from today to the month's end whose entry for that month isn't made
 *   yet (a month skipped counts as made) and that weren't already paid a few days early (DATA-001). Expected income is
 *   shown as such (FLAW-002): "income expected", not money in hand.
 * - [perDay]: set at the start of today (FLAW-003): today's own spending doesn't lower today's amount; rounded down to
 *   whole rupees, never below zero. [leftToday] is what's left of it after today's spending (below zero: over).
 * - [shortBy]: how much the bills still due are more than what's left and expected (zero when they fit).
 */
data class SafeToSpend(
    val left: Money,
    val billsDue: Money,
    val daysLeft: Int,
    val perDay: Money,
    val shortBy: Money,
    val expectedIncome: Money = Money(0),
    val leftToday: Money = perDay,
) {

    companion object {
        /** Null unless [today] is in [summary]'s month: a past or future month has no "days left". */
        fun of(
            summary: MonthSummary, rules: List<RecurringRule>, made: Set<Generation>, today: LocalDate,
            /** This month's entries: bills paid early, and today's spending. */
            payments: List<Entry> = emptyList(),
            /** Payments that already stand for a rule's month. */
            taken: Set<EntityId> = emptySet(),
        ): SafeToSpend? {
            val month = summary.month
            if (today < month.start || today >= month.endExclusive) return null
            val last = month.endExclusive.minus(1, DateTimeUnit.DAY)
            // A budget month with a custom start day spans two calendar months; a rule is due once in each.
            val calendarMonths = listOf(today.yearMonth, last.yearMonth).distinct()
            var bills = 0L
            var expected = 0L
            val used = taken.toMutableSet()
            for (rule in rules) {
                if (rule.deleted || rule.paused || (rule.type != EntryType.EXPENSE && rule.type != EntryType.INCOME)) continue
                for (ym in calendarMonths) {
                    val due = Recurring.dueOn(rule, ym)
                    if (due < today || due > last || due < rule.startsOn) continue
                    if (Generation(rule.id, ym) in made) continue
                    val paid = Recurring.paymentFor(rule.type, rule.amount, due, payments, used)
                    if (paid != null) { used += paid.id; continue }
                    if (rule.type == EntryType.EXPENSE) bills += rule.amount.paise else expected += rule.amount.paise
                }
            }
            // Today's own spending, except bills (a rule's entry, or a payment standing for one): added back, so the
            // amount for today is the one it had when the day began.
            val spentToday = payments.filter {
                !it.deleted && it.countsAsSpending && it.generatedBy == null && it.id !in used && it.occurredAt.localDate == today
            }.sumOf { it.amount.paise }
            val daysLeft = today.daysUntil(month.endExclusive)
            val room = summary.left.paise + expected - bills
            val roomAtDayStart = room + spentToday
            val perDay = if (roomAtDayStart <= 0) 0L else roomAtDayStart / daysLeft / 100 * 100
            return SafeToSpend(summary.left, Money(bills), daysLeft, Money(perDay), Money(maxOf(0L, -room)), Money(expected), Money(perDay - spentToday))
        }
    }
}
