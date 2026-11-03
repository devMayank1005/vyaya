package app.vyaya.core.engine

import app.vyaya.core.model.BudgetMonth
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.monthOf

/**
 * One budget month: money in, money out, and what's left (plan.md Part E invariant 2).
 *
 * Only [Entry.countsAsIncome] and [Entry.countsAsSpending] entries count: transfers between the user's own
 * accounts, Saved, and deleted entries never do. Lent and borrowed money lives in the ledger, not here.
 * [left] is calculated, never stored (CLAUDE.md rule 8.5).
 */
data class MonthSummary(
    val month: BudgetMonth,
    val income: Money,
    val spent: Money,
    /** Spending per category (null: no category). An entry with splits counts towards each split's category. */
    val spentByCategory: Map<EntityId?, Money>,
) {
    val left: Money get() = income - spent

    companion object {
        fun of(entries: List<Entry>, schedule: MonthSchedule, month: BudgetMonth): MonthSummary {
            var income = Money(0)
            var spent = Money(0)
            val byCategory = LinkedHashMap<EntityId?, Money>()
            fun add(category: EntityId?, amount: Money) { byCategory[category] = (byCategory[category] ?: Money(0)) + amount }
            for (e in entries) {
                // The single shared month function decides which month an entry is in (CLAUDE.md rule 8.3).
                if (e.deleted || schedule.monthOf(e.occurredAt) != month) continue
                when {
                    e.countsAsIncome -> income += e.amount
                    e.countsAsSpending -> {
                        spent += e.amount
                        if (e.splits.isEmpty()) add(e.categoryId, e.amount) else e.splits.forEach { add(it.categoryId, it.amount) }
                    }
                }
            }
            return MonthSummary(month, income, spent, byCategory)
        }
    }
}
