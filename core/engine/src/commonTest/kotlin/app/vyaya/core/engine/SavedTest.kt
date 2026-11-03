package app.vyaya.core.engine

import app.vyaya.core.model.EntryType
import app.vyaya.core.model.MonthSchedule
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Month-end Saved: a finished month's leftover, calculated, never stored (rule 8.5; plan.md invariant 3). */
class SavedTest {

    private val calendar = MonthSchedule.of(1)
    private val october = calendar.monthContaining(LocalDate(2026, 10, 15))
    private val salary = entry(EntryType.INCOME, rupees(50_000), "2026-10-01T04:00:00Z")
    private val rent = entry(EntryType.EXPENSE, rupees(20_000), "2026-10-05T04:00:00Z")

    @Test
    fun a_finished_month_saves_its_leftover() {
        val end = MonthEnd.of(listOf(salary, rent), calendar, october, today = LocalDate(2026, 11, 1))!!
        assertEquals(rupees(30_000), end.saved)
        assertEquals(rupees(0), end.overspent)
    }

    @Test
    fun a_month_that_has_not_ended_has_no_saved_yet() {
        assertNull(MonthEnd.of(listOf(salary, rent), calendar, october, today = LocalDate(2026, 10, 31)))
    }

    @Test
    fun an_overspent_month_saves_nothing_and_says_by_how_much() {
        val trip = entry(EntryType.EXPENSE, rupees(40_000), "2026-10-20T04:00:00Z")
        val end = MonthEnd.of(listOf(salary, rent, trip), calendar, october, today = LocalDate(2026, 11, 1))!!
        assertEquals(rupees(0), end.saved)
        assertEquals(rupees(10_000), end.overspent)
    }

    @Test
    fun a_backdated_entry_changes_that_months_saved() { // self-adjusting: nothing stored to go stale
        val forgotten = entry(EntryType.EXPENSE, rupees(5_000), "2026-10-15T04:00:00Z")
        val before = MonthEnd.of(listOf(salary, rent), calendar, october, today = LocalDate(2026, 12, 3))!!
        val after = MonthEnd.of(listOf(salary, rent, forgotten), calendar, october, today = LocalDate(2026, 12, 3))!!
        assertEquals(rupees(30_000), before.saved)
        assertEquals(rupees(25_000), after.saved)
    }

    @Test
    fun saved_minus_overspent_is_always_the_months_leftover() = runTest {
        checkAll(200, Arb.long()) { seed ->
            val r = Random(seed)
            val entries = List(r.nextInt(0, 40)) {
                entry(if (r.nextInt(4) == 0) EntryType.INCOME else EntryType.EXPENSE, rupees(r.nextLong(1, 50_000)),
                    "2026-10-${r.nextInt(1, 29).toString().padStart(2, '0')}T06:00:00Z")
            }
            val end = MonthEnd.of(entries, calendar, october, today = LocalDate(2027, 1, 1))!!
            assertEquals(MonthSummary.of(entries, calendar, october).left, end.saved - end.overspent, "seed=$seed")
        }
    }
}
