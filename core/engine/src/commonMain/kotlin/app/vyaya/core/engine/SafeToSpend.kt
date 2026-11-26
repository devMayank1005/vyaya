package app.vyaya.core.engine

import app.vyaya.core.model.BudgetMonth
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.SystemCategories
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
 * - [lent]: money lent this month that friends haven't paid back yet (split-bills report #4): it left the account, so it
 *   isn't safe to spend until it comes back. A friend's share of a bill paid today counts as today's, like your own.
 */
data class SafeToSpend(
    val left: Money,
    val billsDue: Money,
    val daysLeft: Int,
    val perDay: Money,
    val shortBy: Money,
    val expectedIncome: Money = Money(0),
    val leftToday: Money = perDay,
    val lent: Money = Money(0),
) {

    companion object {
        /** Null unless [today] is in [summary]'s month: a past or future month has no "days left". */
        fun of(
            summary: MonthSummary, rules: List<RecurringRule>, made: Set<Generation>, today: LocalDate,
            /** This month's entries: bills paid early, and today's spending. */
            payments: List<Entry> = emptyList(),
            /** Payments that already stand for a rule's month. */
            taken: Set<EntityId> = emptySet(),
            /** [openLent] for this month. */
            lent: Money = Money(0),
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
            // Your share only (a split's friends' shares aren't in [summary]'s spending); their shares paid today are below.
            val todays = payments.filter {
                !it.deleted && it.countsAsSpending && it.generatedBy == null && it.id !in used && it.occurredAt.localDate == today
            }
            val lentToday = minOf(lent.paise, todays.sumOf { e -> e.splits.filter { it.categoryId == SystemCategories.LENT.id }.sumOf { it.amount.paise } })
            val spentToday = todays.sumOf { it.ownShare.paise } + lentToday
            val daysLeft = today.daysUntil(month.endExclusive)
            val room = summary.left.paise + expected - bills - lent.paise
            val roomAtDayStart = room + spentToday
            val perDay = if (roomAtDayStart <= 0) 0L else roomAtDayStart / daysLeft / 100 * 100
            return SafeToSpend(summary.left, Money(bills), daysLeft, Money(perDay), Money(maxOf(0L, -room)), Money(expected),
                Money(perDay - spentToday), lent)
        }

        /**
         * What friends still owe of what you lent them in [month] (a split bill's shares, or money given): for each person,
         * this month's lending, at most what they owe now. Older lending came out of an earlier month's money.
         */
        fun openLent(ledger: List<LedgerEntry>, month: BudgetMonth): Money {
            val balances = Balances.of(ledger)
            val thisMonth = ledger.filter {
                !it.deleted && !it.settled && it.direction == LedgerDirection.LENT && it.date >= month.start && it.date < month.endExclusive
            }.groupBy { it.personId }
            return Money(thisMonth.entries.sumOf { (person, lent) -> minOf(lent.sumOf { it.amount.paise }, maxOf(0L, balances.of(person).paise)) })
        }
    }
}
