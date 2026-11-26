package app.vyaya.core.engine

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.RecurringRule
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Safe to spend per day (Phase 3): what's left after the bills still due, spread over the days left. Never stored. */
class SafeToSpendTest {

    private val calendar = MonthSchedule.of(1)

    private fun rule(day: Int, rupees: Long, type: EntryType = EntryType.EXPENSE, paused: Boolean = false, deleted: Boolean = false,
        startsOn: LocalDate = LocalDate(2026, 1, 1)) =
        RecurringRule(EntityId.random(), type, rupees(rupees), day, null, null, "Bill", paused, startsOn, T, deleted)

    private fun summary(month: app.vyaya.core.model.BudgetMonth, income: Long, spent: Long) = MonthSummary(month, rupees(income), rupees(spent), emptyMap())

    @Test
    fun whats_left_after_the_bills_still_due_spread_over_the_days_left() {
        val today = LocalDate(2026, 10, 10)
        val month = calendar.monthContaining(today)
        val rent = rule(20, 15_000)
        val s = SafeToSpend.of(summary(month, 50_000, 20_000), listOf(rent), emptySet(), today)!!
        assertEquals(rupees(30_000), s.left)
        assertEquals(rupees(15_000), s.billsDue)
        assertEquals(22, s.daysLeft, "10 to 31 October, today included")
        assertEquals(rupees(681), s.perDay, "15,000 / 22 = 681.8, rounded down to whole rupees")
        assertEquals(rupees(0), s.shortBy)
    }

    @Test
    fun only_bills_not_yet_made_paid_out_and_due_from_today_to_the_months_end() {
        val today = LocalDate(2026, 10, 10)
        val month = calendar.monthContaining(today)
        val made = rule(25, 1_000)
        val rules = listOf(
            rule(20, 2_000), // counted
            rule(10, 300), // due today, not made yet: counted
            made, // this month's entry already made (or skipped): not counted
            rule(5, 4_000), // due before today
            rule(15, 5_000, paused = true), rule(15, 6_000, deleted = true),
            rule(28, 7_000, type = EntryType.INCOME), // income not yet in: not counted, to stay on the safe side
            rule(28, 8_000, startsOn = LocalDate(2026, 11, 28)), // starts next month
        )
        val s = SafeToSpend.of(summary(month, 10_000, 0), rules, setOf(Generation(made.id, YearMonth(2026, 10))), today)!!
        assertEquals(rupees(2_300), s.billsDue)
    }

    @Test
    fun a_custom_month_counts_due_dates_in_both_calendar_months_it_spans() {
        val schedule = MonthSchedule.of(25) // 25 October to 24 November
        val today = LocalDate(2026, 10, 26)
        val month = schedule.monthContaining(today)
        val s = SafeToSpend.of(summary(month, 0, 0), listOf(rule(28, 100), rule(5, 200), rule(24, 400), rule(25, 800)), emptySet(), today)!!
        // 28 Oct and 5 Nov and 24 Nov are in it; 25 Oct is before today and 25 Nov is the next month.
        assertEquals(rupees(700), s.billsDue)
        assertEquals(30, s.daysLeft, "26 October to 24 November")
    }

    @Test
    fun overspent_or_bills_beyond_whats_left_means_nothing_safe_to_spend() {
        val today = LocalDate(2026, 10, 30)
        val month = calendar.monthContaining(today)
        val s = SafeToSpend.of(summary(month, 10_000, 9_000), listOf(rule(31, 1_500)), emptySet(), today)!!
        assertEquals(rupees(0), s.perDay)
        assertEquals(rupees(500), s.shortBy)
        assertEquals(rupees(0), SafeToSpend.of(summary(month, 0, 4_000), emptyList(), emptySet(), today)!!.perDay)
    }

    @Test
    fun only_for_the_month_today_is_in() {
        val month = calendar.monthContaining(LocalDate(2026, 9, 10))
        assertNull(SafeToSpend.of(summary(month, 1, 0), emptyList(), emptySet(), LocalDate(2026, 10, 10)))
    }

    @Test
    fun spending_the_daily_amount_every_day_left_never_goes_below_zero() = runTest {
        checkAll(Arb.long(0L..10_000_000L), Arb.long(0L..10_000_000L), Arb.int(1..28), Arb.long(0L..5_000_000L), Arb.int(1..31)) { inc, out, day, bill, billDay ->
            val today = LocalDate(2026, 2, day)
            val month = calendar.monthContaining(today)
            val s = SafeToSpend.of(MonthSummary(month, Money(inc), Money(out), emptyMap()), listOf(rule(billDay, 1).copy(amount = Money(bill + 1))), emptySet(), today)!!
            val room = s.left.paise - s.billsDue.paise
            check(s.perDay.paise >= 0 && s.perDay.paise % 100 == 0L && s.perDay.paise * s.daysLeft <= maxOf(0, room)) { "$inc $out $day $bill $billDay -> $s" }
            check(s.shortBy.paise == maxOf(0, -room))
        }
    }

    @Test
    fun safe_data_001_a_bill_already_paid_early_is_no_longer_due() {
        val today = LocalDate(2026, 11, 19)
        val month = calendar.monthContaining(today)
        val rent = rule(20, 15_000)
        val upi = entry(EntryType.EXPENSE, rupees(15_000), "2026-11-18T06:00:00Z", category = null)
        val s = SafeToSpend.of(summary(month, 50_000, 15_000), listOf(rent), emptySet(), today, payments = listOf(upi))!!
        assertEquals(rupees(0), s.billsDue, "the rent was paid yesterday: not due again")
        val again = SafeToSpend.of(summary(month, 50_000, 15_000), listOf(rent), emptySet(), today, payments = listOf(upi), taken = setOf(upi.id))!!
        assertEquals(rupees(15_000), again.billsDue, "a payment that already stands for another bill doesn't count twice")
        val two = SafeToSpend.of(summary(month, 50_000, 15_000), listOf(rent, rule(20, 15_000)), emptySet(), today, payments = listOf(upi))!!
        assertEquals(rupees(15_000), two.billsDue, "one payment, one bill")
    }

    @Test
    fun safe_flaw_002_income_rules_still_to_come_this_month_are_counted_as_expected() {
        val today = LocalDate(2026, 11, 12)
        val month = calendar.monthContaining(today)
        val salary = rule(30, 60_000, type = EntryType.INCOME)
        val s = SafeToSpend.of(summary(month, 0, 10_000), listOf(salary, rule(20, 15_000)), emptySet(), today)!!
        assertEquals(rupees(60_000), s.expectedIncome)
        assertEquals(rupees(15_000), s.billsDue)
        assertEquals(rupees(1_842), s.perDay, "(−10,000 + 60,000 − 15,000) / 19 = 1,842.1")
        val early = SafeToSpend.of(summary(month, 60_000, 10_000), listOf(salary), emptySet(), LocalDate(2026, 11, 26),
            payments = listOf(entry(EntryType.INCOME, rupees(60_000), "2026-11-26T06:00:00Z", category = null)))!!
        assertEquals(rupees(0), early.expectedIncome, "the salary came 4 days early: not expected again")
    }

    @Test
    fun safe_flaw_003_todays_amount_is_set_at_the_start_of_the_day_and_today_says_what_is_left_of_it() {
        val today = LocalDate(2026, 11, 12)
        val month = calendar.monthContaining(today)
        val lunch = entry(EntryType.EXPENSE, rupees(500), "2026-11-12T06:00:00Z", category = null) // today, in Kolkata
        // Left is 30,000 after lunch: 30,500 at the start of the day.
        val s = SafeToSpend.of(summary(month, 50_000, 20_000), emptyList(), emptySet(), today, payments = listOf(lunch))!!
        assertEquals(rupees(1_605), s.perDay, "30,500 / 19 = 1,605.2: lunch doesn't change today's amount")
        assertEquals(rupees(1_105), s.leftToday)
        val big = entry(EntryType.EXPENSE, rupees(2_000), "2026-11-12T07:00:00Z", category = null)
        assertEquals(rupees(-895), SafeToSpend.of(summary(month, 50_000, 22_000), emptyList(), emptySet(), today, payments = listOf(lunch, big))!!.leftToday,
            "over today's amount")
    }
}
