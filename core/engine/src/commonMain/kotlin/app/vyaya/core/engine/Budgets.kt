package app.vyaya.core.engine

import app.vyaya.core.model.Budget
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Money

enum class BudgetState {
    /** Under 80 % of the limit. */
    OK,

    /** 80 % of the limit or more, up to the limit itself (used up, not over). */
    NEAR,

    /** Past the limit. */
    OVER,
}

/**
 * One budget in one month: what was spent in its category against its limit. The limit is fresh each budget month
 * (the user's choice: no rollover). Calculated, never stored (rule 8.5); money in paise, ratios in whole numbers (8.1).
 */
data class BudgetProgress(val budget: Budget, val spent: Money) {
    val limit: Money get() = budget.limit

    /** Below zero when over. */
    val left: Money get() = limit - spent

    /** Whole percent of the limit spent, rounded down. */
    val percent: Long get() = spent.paise * 100 / limit.paise

    val state: BudgetState
        get() = when {
            spent.paise > limit.paise -> BudgetState.OVER
            spent.paise * 5 >= limit.paise * 4 -> BudgetState.NEAR
            else -> BudgetState.OK
        }
}

object Budgets {

    private val LATEST = compareBy<Budget>({ it.updatedAt }, { it.id.value })

    /**
     * The budgets in use: one per spending category that still exists. Two budgets for one category (merged from two
     * phones) are one: the latest change wins; changed at the same moment, the larger id, so every phone and every
     * order of the records agrees.
     */
    fun live(budgets: List<Budget>, categories: List<Category>): List<Budget> {
        val spending = categories.filter { !it.deleted && it.kind == CategoryKind.EXPENSE }.map { it.id }.toSet()
        return budgets.groupBy { it.categoryId }.values.map { same -> same.maxWith(LATEST) }
            .filter { !it.deleted && it.categoryId in spending }
    }

    /**
     * Each budget's progress in [summary]'s month, fullest first. Spending is [MonthSummary.spentByCategory]: expense
     * entries only (income, transfers and Saved never count), each split towards its own category.
     */
    fun progress(budgets: List<Budget>, summary: MonthSummary, categories: List<Category>): List<BudgetProgress> =
        live(budgets, categories)
            .map { BudgetProgress(it, summary.spentByCategory[it.categoryId] ?: Money(0)) }
            .sortedWith(compareByDescending<BudgetProgress> { it.percent }.thenByDescending { it.spent.paise }.thenBy { it.budget.categoryId.value })

    /**
     * A limit to start from: the average of [recentMonths] (the finished months, up to the last 3, since the user
     * began), rounded up to the next ₹100. Null when there's no finished month or nothing was spent.
     */
    fun suggest(recentMonths: List<Money>): Money? {
        if (recentMonths.isEmpty()) return null
        val total = recentMonths.sumOf { it.paise }
        if (total <= 0) return null
        val hundred = 100_00L
        val average = (total + recentMonths.size - 1) / recentMonths.size // rounded up, so the limit covers it
        return Money((average + hundred - 1) / hundred * hundred)
    }
}
