package app.vyaya.core.engine

import app.vyaya.core.model.Budget
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.Split
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/** Budgets (Phase 3): a fresh limit for each budget month per spending category; never stored, always calculated. */
class BudgetsTest {

    private val calendar = MonthSchedule.of(1)
    private val october = calendar.monthContaining(LocalDate(2026, 10, 15))
    private val food = Category(FOOD, "Food", CategoryKind.EXPENSE, T, false)
    private val rent = Category(RENT, "Rent", CategoryKind.EXPENSE, T, false)

    private fun budget(category: EntityId, rupees: Long, deleted: Boolean = false, at: kotlin.time.Instant = T) =
        Budget(EntityId.random(), category, rupees(rupees), at, deleted)

    @Test
    fun progress_is_the_months_spending_in_the_category_against_its_limit() {
        val summary = MonthSummary.of(
            listOf(
                entry(EntryType.EXPENSE, rupees(3_000), "2026-10-02T04:00:00Z"),
                entry(EntryType.EXPENSE, rupees(1_000), "2026-10-03T04:00:00Z", splits = listOf(Split(FOOD, rupees(700)), Split(RENT, rupees(300)))),
                entry(EntryType.INCOME, rupees(9_000), "2026-10-04T04:00:00Z"), // income in Food is not spending
                entry(EntryType.EXPENSE, rupees(500), "2026-09-30T04:00:00Z"), // last month
                entry(EntryType.EXPENSE, rupees(500), "2026-10-05T04:00:00Z", deleted = true),
            ),
            calendar, october,
        )
        val progress = Budgets.progress(listOf(budget(FOOD, 5_000), budget(RENT, 300)), summary, listOf(food, rent))
        assertEquals(listOf(RENT, FOOD), progress.map { it.budget.categoryId }, "fullest first")
        val f = progress.single { it.budget.categoryId == FOOD }
        assertEquals(rupees(3_700) to 74L, f.spent to f.percent)
        assertEquals(BudgetState.OK, f.state)
        assertEquals(rupees(1_300), f.left)
        assertEquals(BudgetState.NEAR, progress.single { it.budget.categoryId == RENT }.state, "exactly at the limit: used up, not over")
    }

    @Test
    fun near_from_80_percent_and_over_past_the_limit() {
        fun state(spent: Long, limit: Long) = BudgetProgress(budget(FOOD, limit), rupees(spent)).state
        assertEquals(BudgetState.OK, state(3_999, 5_000))
        assertEquals(BudgetState.NEAR, state(4_000, 5_000))
        assertEquals(BudgetState.NEAR, state(5_000, 5_000))
        assertEquals(BudgetState.OVER, state(5_001, 5_000))
        assertEquals(rupees(-1), BudgetProgress(budget(FOOD, 5_000), rupees(5_001)).left)
    }

    @Test
    fun one_budget_per_category_the_latest_wins_and_removed_or_orphaned_ones_are_left_out() {
        val summary = MonthSummary.of(emptyList(), calendar, october)
        val newer = budget(FOOD, 6_000, at = T + 1.hours)
        val progress = Budgets.progress(
            listOf(budget(FOOD, 5_000), newer, budget(RENT, 100, deleted = true), budget(EntityId.random(), 100)),
            summary, listOf(food, rent),
        )
        assertEquals(listOf(newer), progress.map { it.budget })
        assertEquals(emptyList(), Budgets.progress(listOf(budget(FOOD, 5_000)), summary, listOf(food.copy(deleted = true))))
        assertEquals(0L, progress.single().percent)
    }

    @Test
    fun the_suggestion_is_the_recent_monthly_average_rounded_up_to_100_rupees() {
        assertEquals(rupees(4_100), Budgets.suggest(listOf(rupees(4_000), rupees(3_950), rupees(4_200))))
        assertEquals(rupees(100), Budgets.suggest(listOf(rupees(1))))
        assertEquals(rupees(2_000), Budgets.suggest(listOf(rupees(2_000), rupees(0)).take(1)))
        assertNull(Budgets.suggest(emptyList()), "no finished month yet")
        assertNull(Budgets.suggest(listOf(rupees(0), rupees(0))), "nothing spent: nothing to suggest")
    }

    @Test
    fun the_suggestion_covers_the_average_and_is_never_more_than_100_rupees_above_it() = runTest {
        checkAll(Arb.long(1L..1_000_000_00L), Arb.long(0L..1_000_000_00L), Arb.long(0L..1_000_000_00L)) { a, b, c ->
            val months = listOf(Money(a), Money(b), Money(c))
            val s = Budgets.suggest(months)!!.paise
            val total = a + b + c
            check(s * 3 >= total && (s - 100_00) * 3 < total && s % 100_00 == 0L) { "a=$a b=$b c=$c s=$s" }
        }
    }

    @Test
    fun budgets_sim_two_copies_changed_at_the_same_moment_resolve_the_same_way_in_any_order() {
        val a = Budget(EntityId.parse("00000000-0000-4000-8000-00000000000a")!!, FOOD, rupees(1_000), T, false)
        val b = Budget(EntityId.parse("00000000-0000-4000-8000-00000000000b")!!, FOOD, rupees(2_000), T, false)
        assertEquals(listOf(b), Budgets.live(listOf(a, b), listOf(food)))
        assertEquals(listOf(b), Budgets.live(listOf(b, a), listOf(food)), "the larger id wins a tie, whatever the order")
    }
}
