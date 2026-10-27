package app.vyaya.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/** FLAW-003: changing the month start day must not rewrite past months. */
class MonthScheduleTest {

    private fun date(y: Int, m: Int, d: Int) = LocalDate(y, m, d)

    @Test
    fun without_changes_it_matches_budget_month() {
        val schedule = MonthSchedule.of(startDay = 25)
        assertEquals(BudgetMonth.containing(date(2026, 3, 27), 25), schedule.monthContaining(date(2026, 3, 27)))
    }

    @Test
    fun a_change_applies_from_the_next_month_and_keeps_past_months() {
        // FLAW-003 reproduction: 27 Mar used to jump to the month starting 25 Mar.
        val schedule = MonthSchedule.of(startDay = 1).withStartDay(25, changedOn = date(2026, 3, 10))

        val march = schedule.monthContaining(date(2026, 3, 27))
        assertEquals(date(2026, 3, 1), march.start)
        assertEquals(date(2026, 4, 1), march.endExclusive)
        assertEquals(date(2026, 2, 1), schedule.monthContaining(date(2026, 2, 14)).start)

        // Transition month: old boundary → first new boundary.
        val transition = schedule.monthContaining(date(2026, 4, 10))
        assertEquals(date(2026, 4, 1), transition.start)
        assertEquals(date(2026, 4, 25), transition.endExclusive)

        // Then normal payday months.
        val may = schedule.monthContaining(date(2026, 5, 1))
        assertEquals(date(2026, 4, 25), may.start)
        assertEquals(date(2026, 5, 25), may.endExclusive)
    }

    @Test
    fun a_transition_shorter_than_a_week_is_merged_into_the_following_month() {
        // 1 → 3 would otherwise create a 2-day month (1–3 Apr).
        val schedule = MonthSchedule.of(startDay = 1).withStartDay(3, changedOn = date(2026, 3, 10))
        val transition = schedule.monthContaining(date(2026, 4, 2))
        assertEquals(date(2026, 4, 1), transition.start)
        assertEquals(date(2026, 5, 3), transition.endExclusive)
        assertEquals(transition, schedule.monthContaining(date(2026, 5, 2)))
        assertEquals(date(2026, 5, 3), schedule.monthContaining(date(2026, 5, 3)).start)
    }

    @Test
    fun changing_again_before_it_takes_effect_replaces_the_pending_change() {
        val schedule = MonthSchedule.of(startDay = 1)
            .withStartDay(25, changedOn = date(2026, 3, 10))
            .withStartDay(15, changedOn = date(2026, 3, 20))
        assertEquals(date(2026, 4, 15), schedule.monthContaining(date(2026, 4, 10)).endExclusive)
        assertEquals(date(2026, 4, 15), schedule.monthContaining(date(2026, 4, 20)).start)
        assertEquals(1, schedule.changes.size)
    }

    @Test
    fun changing_back_before_it_takes_effect_cancels_the_pending_change() {
        val schedule = MonthSchedule.of(startDay = 1)
            .withStartDay(25, changedOn = date(2026, 3, 10))
            .withStartDay(1, changedOn = date(2026, 3, 20))
        assertEquals(MonthSchedule.of(startDay = 1), schedule)
    }

    @Test
    fun several_changes_over_time_each_keep_earlier_history() {
        val schedule = MonthSchedule.of(startDay = 1)
            .withStartDay(25, changedOn = date(2026, 3, 10)) // effective 1 Apr, first payday month 25 Apr
            .withStartDay(10, changedOn = date(2026, 6, 1)) // current month 25 May–25 Jun → effective 25 Jun

        assertEquals(date(2026, 5, 25), schedule.monthContaining(date(2026, 6, 20)).start)
        val transition = schedule.monthContaining(date(2026, 7, 1))
        assertEquals(date(2026, 6, 25), transition.start)
        assertEquals(date(2026, 7, 10), transition.endExclusive)
        assertEquals(date(2026, 7, 10), schedule.monthContaining(date(2026, 7, 10)).start)
    }

    @Test
    fun a_change_dated_before_one_already_in_place_is_rejected() { // FLAW-001
        val schedule = MonthSchedule.of(1).withStartDay(25, changedOn = date(2026, 3, 10)) // effective 1 Apr
        val mayBefore = schedule.monthContaining(date(2026, 5, 10))
        // Phone clock wrongly set to February: this would delete the April change and move May.
        assertFailsWith<IllegalArgumentException> { schedule.withStartDay(10, changedOn = date(2026, 2, 5)) }
        assertEquals(mayBefore, schedule.monthContaining(date(2026, 5, 10)))
        // Replacing a change that hasn't taken effect yet (same effective date) is still allowed.
        assertEquals(date(2026, 4, 1), schedule.withStartDay(10, changedOn = date(2026, 3, 20)).changes.single().effectiveFrom)
    }

    @Test
    fun next_and_previous_follow_the_schedule() {
        val schedule = MonthSchedule.of(startDay = 1).withStartDay(25, changedOn = date(2026, 3, 10))
        val march = schedule.monthContaining(date(2026, 3, 5))
        assertEquals(date(2026, 4, 1), schedule.next(march).start)
        assertEquals(date(2026, 4, 25), schedule.next(schedule.next(march)).start)
        assertEquals(march, schedule.previous(schedule.next(march)))
    }

    @Test
    fun instants_are_assigned_in_the_given_zone() {
        val schedule = MonthSchedule.of(startDay = 1)
        val instant = Instant.parse("2026-10-31T19:00:00Z") // 00:30 IST on 1 Nov
        assertEquals(date(2026, 11, 1), schedule.monthContaining(instant, TimeZone.of("Asia/Kolkata")).start)
    }

    @Test
    fun start_day_must_be_between_1_and_31() {
        assertFailsWith<IllegalArgumentException> { MonthSchedule.of(0) }
        assertFailsWith<IllegalArgumentException> { MonthSchedule.of(1).withStartDay(32, date(2026, 1, 1)) }
    }

    @Test
    fun any_sequence_of_changes_preserves_history_and_tiles_without_gaps() = runTest {
        val dayOffset = Arb.int(0..400) // days between successive changes
        checkAll(Arb.int(1..31), Arb.list(Arb.pair(dayOffset, Arb.int(1..31)), 0..5)) { initial, steps ->
            var schedule = MonthSchedule.of(initial)
            var changedOn = date(2024, 1, 1)
            for ((offset, newDay) in steps) {
                changedOn = changedOn.plus(offset, DateTimeUnit.DAY)
                val before = schedule
                schedule = schedule.withStartDay(newDay, changedOn)
                // History: every month up to and including the current one is unchanged.
                var m = before.monthContaining(changedOn)
                repeat(13) {
                    assertEquals(m, schedule.monthContaining(m.start), "changedOn=$changedOn newDay=$newDay")
                    m = before.previous(m)
                }
            }
            // Tiling: walk 6 years of months; each contains its own dates and the next starts where it ends.
            var month = schedule.monthContaining(date(2024, 1, 1))
            while (month.start < date(2030, 1, 1)) {
                val next = schedule.next(month)
                assertEquals(month.endExclusive, next.start)
                assertTrue(month.endExclusive > month.start)
                assertEquals(month, schedule.monthContaining(month.lastDay))
                assertEquals(month, schedule.previous(next))
                month = next
            }
        }
    }
}
