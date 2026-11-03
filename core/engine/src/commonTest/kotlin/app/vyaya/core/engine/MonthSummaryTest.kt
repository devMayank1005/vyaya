package app.vyaya.core.engine

import app.vyaya.core.model.EntryType
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.Split
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** "What's left" for a month (plan.md Part E invariant 2): income − expenses, nothing else. */
class MonthSummaryTest {

    private val calendar = MonthSchedule.of(1)
    private val october = calendar.monthContaining(LocalDate(2026, 10, 15))

    @Test
    fun left_is_income_minus_expenses() {
        val entries = listOf(
            entry(EntryType.INCOME, rupees(50_000), "2026-10-01T04:00:00Z"),
            entry(EntryType.EXPENSE, rupees(1_200), "2026-10-05T04:00:00Z"),
            entry(EntryType.EXPENSE, rupees(800), "2026-10-20T04:00:00Z"),
        )
        val s = MonthSummary.of(entries, calendar, october)
        assertEquals(rupees(50_000), s.income)
        assertEquals(rupees(2_000), s.spent)
        assertEquals(rupees(48_000), s.left)
    }

    @Test
    fun transfers_saved_and_deleted_entries_do_not_count() {
        val entries = listOf(
            entry(EntryType.INCOME, rupees(1_000), "2026-10-01T04:00:00Z"),
            entry(EntryType.TRANSFER, rupees(500), "2026-10-02T04:00:00Z"), // own accounts: not spending
            entry(EntryType.SAVED, rupees(300), "2026-10-03T04:00:00Z"), // kept aside: not spending
            entry(EntryType.EXPENSE, rupees(200), "2026-10-04T04:00:00Z", deleted = true),
            entry(EntryType.EXPENSE, rupees(100), "2026-10-04T04:00:00Z"),
        )
        val s = MonthSummary.of(entries, calendar, october)
        assertEquals(rupees(1_000), s.income)
        assertEquals(rupees(100), s.spent)
        assertEquals(rupees(900), s.left)
    }

    @Test
    fun an_entry_belongs_to_the_month_of_its_local_date() { // rule 8.3: the shared month function
        val payday = MonthSchedule.of(25)
        val month = payday.monthContaining(LocalDate(2026, 10, 25)) // 25 Oct .. 24 Nov
        val entries = listOf(
            entry(EntryType.EXPENSE, rupees(10), "2026-10-24T18:00:00Z"), // 24 Oct 23:30 in Kolkata: the month before
            entry(EntryType.EXPENSE, rupees(20), "2026-10-24T19:00:00Z"), // 25 Oct 00:30 in Kolkata: this month
            entry(EntryType.EXPENSE, rupees(40), "2026-11-24T18:29:00Z"), // 24 Nov 23:59: still this month
            entry(EntryType.EXPENSE, rupees(80), "2026-11-24T18:30:00Z"), // 25 Nov 00:00: next month
        )
        assertEquals(rupees(60), MonthSummary.of(entries, payday, month).spent)
    }

    @Test
    fun spending_by_category_follows_splits_and_adds_up() {
        val groceries = app.vyaya.core.model.EntityId.random()
        val dmart = entry(EntryType.EXPENSE, rupees(2_400), "2026-10-10T04:00:00Z",
            splits = listOf(Split(groceries, rupees(1_800)), Split(FOOD, rupees(600))))
        val lunch = entry(EntryType.EXPENSE, rupees(300), "2026-10-11T04:00:00Z")
        val unfiled = entry(EntryType.EXPENSE, rupees(50), "2026-10-12T04:00:00Z", category = null)
        val s = MonthSummary.of(listOf(dmart, lunch, unfiled), calendar, october)
        assertEquals(mapOf(groceries to rupees(1_800), FOOD to rupees(900), null to rupees(50)), s.spentByCategory)
    }

    @Test
    fun left_always_equals_income_minus_spent_and_categories_add_up_to_spent() = runTest {
        checkAll(200, Arb.long()) { seed ->
            val r = Random(seed)
            val entries = List(r.nextInt(0, 60)) {
                val day = r.nextInt(1, 29)
                entry(EntryType.entries.random(r), rupees(r.nextLong(1, 100_000)), "2026-10-${day.toString().padStart(2, '0')}T06:00:00Z",
                    category = if (r.nextBoolean()) FOOD else null, deleted = r.nextInt(10) == 0)
            }
            val s = MonthSummary.of(entries, calendar, october)
            assertEquals(s.income - s.spent, s.left, "seed=$seed")
            assertEquals(s.spent, s.spentByCategory.values.fold(rupees(0)) { a, b -> a + b }, "seed=$seed")
            val expected = entries.filter { !it.deleted && it.type == EntryType.EXPENSE }.fold(rupees(0)) { a, e -> a + e.amount }
            assertEquals(expected, s.spent, "seed=$seed")
        }
    }
}
