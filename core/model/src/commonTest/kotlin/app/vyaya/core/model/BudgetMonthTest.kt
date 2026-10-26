package app.vyaya.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class BudgetMonthTest {

    private fun date(y: Int, m: Int, d: Int) = LocalDate(y, m, d)

    @Test
    fun calendar_month_when_start_day_is_1() {
        val month = BudgetMonth.containing(date(2026, 10, 15), startDay = 1)
        assertEquals(date(2026, 10, 1), month.start)
        assertEquals(date(2026, 11, 1), month.endExclusive)
        assertEquals(date(2026, 10, 31), month.lastDay)
    }

    @Test
    fun payday_month_starts_on_the_chosen_day() {
        assertEquals(date(2026, 9, 25), BudgetMonth.containing(date(2026, 10, 24), startDay = 25).start)
        val fromPayday = BudgetMonth.containing(date(2026, 10, 25), startDay = 25)
        assertEquals(date(2026, 10, 25), fromPayday.start)
        assertEquals(date(2026, 11, 25), fromPayday.endExclusive)
        assertEquals(date(2026, 11, 24), fromPayday.lastDay)
    }

    @Test
    fun start_day_beyond_month_length_clamps_to_the_last_day_of_that_month() {
        // Start day 31 in a non-leap February starts on the 28th.
        val feb = BudgetMonth.containing(date(2026, 2, 28), startDay = 31)
        assertEquals(date(2026, 2, 28), feb.start)
        assertEquals(date(2026, 3, 31), feb.endExclusive)

        assertEquals(date(2026, 1, 31), BudgetMonth.containing(date(2026, 2, 27), startDay = 31).start)
        // Leap year February starts on the 29th.
        assertEquals(date(2028, 2, 29), BudgetMonth.containing(date(2028, 3, 1), startDay = 30).start)
        // Start day 31 in a 30-day month starts on the 30th.
        assertEquals(date(2026, 4, 30), BudgetMonth.containing(date(2026, 4, 30), startDay = 31).start)
    }

    @Test
    fun periods_cross_the_year_boundary() {
        val dec = BudgetMonth.containing(date(2026, 12, 31), startDay = 1)
        assertEquals(date(2027, 1, 1), dec.endExclusive)

        val payday = BudgetMonth.containing(date(2027, 1, 10), startDay = 25)
        assertEquals(date(2026, 12, 25), payday.start)
        assertEquals(date(2027, 1, 25), payday.endExclusive)
    }

    @Test
    fun instant_is_assigned_to_a_month_in_the_users_time_zone() {
        // 19:00 UTC on 31 Oct is 00:30 IST on 1 Nov.
        val instant = Instant.parse("2026-10-31T19:00:00Z")
        val ist = BudgetMonth.containing(instant, TimeZone.of("Asia/Kolkata"), startDay = 1)
        val utc = BudgetMonth.containing(instant, TimeZone.UTC, startDay = 1)
        assertEquals(date(2026, 11, 1), ist.start)
        assertEquals(date(2026, 10, 1), utc.start)
    }

    @Test
    fun contains_is_start_inclusive_and_end_exclusive() {
        val month = BudgetMonth.containing(date(2026, 10, 15), startDay = 1)
        assertTrue(date(2026, 10, 1) in month)
        assertTrue(date(2026, 10, 31) in month)
        assertFalse(date(2026, 11, 1) in month)
        assertFalse(date(2026, 9, 30) in month)
    }

    @Test
    fun start_day_must_be_between_1_and_31() {
        assertFailsWith<IllegalArgumentException> { BudgetMonth.containing(date(2026, 1, 1), startDay = 0) }
        assertFailsWith<IllegalArgumentException> { BudgetMonth.containing(date(2026, 1, 1), startDay = 32) }
    }

    @Test
    fun every_date_belongs_to_exactly_one_month_and_months_tile_without_gaps() = runTest {
        checkAll(
            // Common-code date generator (Kotest's Arb.localDate is JVM-only and would break iOS).
            Arb.int(LocalDate(2000, 1, 1).toEpochDays().toInt()..LocalDate(2100, 12, 31).toEpochDays().toInt())
                .map { LocalDate.fromEpochDays(it) },
            Arb.int(1..31),
        ) { day, startDay ->
            val month = BudgetMonth.containing(day, startDay)
            assertTrue(day in month, "day=$day startDay=$startDay month=$month")
            assertEquals(month.endExclusive, month.next().start, "next must start where this ends")
            assertEquals(month.start, month.previous().endExclusive, "previous must end where this starts")
            assertEquals(month, BudgetMonth.containing(month.start, startDay), "start date maps to same month")
            assertEquals(month, BudgetMonth.containing(month.lastDay, startDay), "last day maps to same month")
        }
    }
}
