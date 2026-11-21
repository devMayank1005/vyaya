package app.vyaya.core.engine

import app.vyaya.core.model.EntryType
import app.vyaya.core.model.MonthSchedule
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Safe-to-spend FLAW-002: when the salary comes on about the same day each month, suggest starting the month on it. */
class SalaryDayTest {

    private val today = LocalDate(2026, 11, 12)
    private fun salary(at: String, rupees: Long = 60_000) = entry(EntryType.INCOME, rupees(rupees), at, category = null)

    @Test
    fun the_same_salary_day_each_month_is_suggested_as_the_month_start() {
        val incomes = listOf(salary("2026-08-30T06:00:00Z"), salary("2026-09-30T06:00:00Z"), salary("2026-10-29T06:00:00Z"),
            salary("2026-10-05T06:00:00Z", rupees = 500)) // a small refund: not the salary
        assertEquals(29, SalaryDay.suggest(incomes, MonthSchedule.of(1), today), "the earliest of the days, so the salary is always in its month")
    }

    @Test
    fun no_suggestion_when_it_already_starts_near_that_day_or_the_days_vary_or_one_month_only() {
        val regular = listOf(salary("2026-09-30T06:00:00Z"), salary("2026-10-29T06:00:00Z"))
        assertEquals(29, SalaryDay.suggest(regular, MonthSchedule.of(1), today), "two months are enough")
        assertNull(SalaryDay.suggest(regular, MonthSchedule.of(28), today), "already starts within 3 days")
        assertNull(SalaryDay.suggest(listOf(salary("2026-10-29T06:00:00Z")), MonthSchedule.of(1), today), "one month only")
        assertNull(SalaryDay.suggest(listOf(salary("2026-08-05T06:00:00Z"), salary("2026-09-20T06:00:00Z"), salary("2026-10-28T06:00:00Z")),
            MonthSchedule.of(1), today), "no regular day")
        assertNull(SalaryDay.suggest(listOf(salary("2026-09-02T06:00:00Z"), salary("2026-10-02T06:00:00Z")), MonthSchedule.of(1), today),
            "comes at the month's start already")
        assertNull(SalaryDay.suggest(emptyList(), MonthSchedule.of(1), today))
    }

    @Test
    fun deleted_entries_and_this_months_income_are_not_used() {
        val incomes = listOf(salary("2026-09-30T06:00:00Z").copy(deleted = true), salary("2026-10-29T06:00:00Z"), salary("2026-11-10T06:00:00Z"))
        assertNull(SalaryDay.suggest(incomes, MonthSchedule.of(1), today), "October only: the deleted one and this month's don't count")
    }

    @Test
    fun once_chosen_for_next_month_it_is_not_suggested_again() {
        val regular = listOf(salary("2026-09-30T06:00:00Z"), salary("2026-10-29T06:00:00Z"))
        assertNull(SalaryDay.suggest(regular, MonthSchedule.of(1).withStartDay(29, today), today), "already chosen, from next month")
    }
}
