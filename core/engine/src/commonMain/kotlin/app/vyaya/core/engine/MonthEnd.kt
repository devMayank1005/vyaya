package app.vyaya.core.engine

import app.vyaya.core.model.BudgetMonth
import app.vyaya.core.model.Entry
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import kotlinx.datetime.LocalDate

/**
 * A finished month's result: what it saved, or by how much it was overspent (plan.md invariant 3).
 *
 * Calculated from the month's entries every time, never stored (CLAUDE.md rule 8.5), so an entry added or
 * edited later in that month changes it straight away. Saved is never negative: a month that spent more
 * than came in shows [overspent] instead. `saved - overspent == summary.left` always.
 */
data class MonthEnd(val summary: MonthSummary, val saved: Money, val overspent: Money) {
    companion object {
        /** Null while [month] hasn't ended on [today] (the home time zone's date). */
        fun of(entries: List<Entry>, schedule: MonthSchedule, month: BudgetMonth, today: LocalDate): MonthEnd? {
            if (today < month.endExclusive) return null
            val summary = MonthSummary.of(entries, schedule, month)
            val left = summary.left
            return if (left.paise >= 0) MonthEnd(summary, left, Money(0)) else MonthEnd(summary, Money(0), Money(0) - left)
        }
    }
}
