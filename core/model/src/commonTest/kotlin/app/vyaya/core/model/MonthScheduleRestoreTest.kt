package app.vyaya.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Rebuilding a schedule from stored data (backup restore) must accept exactly what withStartDay can produce. */
class MonthScheduleRestoreTest {

    @Test
    fun any_schedule_restores_to_itself() = runTest {
        checkAll(Arb.int(1..31), Arb.list(Arb.pair(Arb.int(0..400), Arb.int(1..31)), 0..6)) { initial, steps ->
            var schedule = MonthSchedule.of(initial)
            var day = LocalDate(2024, 1, 1)
            for ((offset, newDay) in steps) {
                day = day.plus(offset, DateTimeUnit.DAY)
                schedule = schedule.withStartDay(newDay, day)
            }
            assertEquals(schedule, MonthSchedule.restore(schedule.initialStartDay, schedule.changes))
        }
    }

    @Test
    fun tampered_or_impossible_schedules_are_rejected() {
        val good = MonthSchedule.of(1).withStartDay(25, LocalDate(2026, 3, 10)).changes.single()
        assertNull(MonthSchedule.restore(0, emptyList()), "initial day out of range")
        assertNull(MonthSchedule.restore(1, listOf(good.copy(startDay = 40))), "start day out of range")
        assertNull(MonthSchedule.restore(1, listOf(good.copy(transitionEnd = LocalDate(2026, 4, 20)))), "wrong transition end")
        assertNull(MonthSchedule.restore(1, listOf(good.copy(effectiveFrom = LocalDate(2026, 4, 5)))), "not on a month boundary")
        assertNull(MonthSchedule.restore(25, listOf(good)), "change to the day already in effect")
        assertNull(MonthSchedule.restore(1, listOf(good, good)), "out of order / duplicate")
        val far = LocalDate(999_999_999, 12, 1)
        assertNull(MonthSchedule.restore(1, listOf(StartDayChange(far, far, 15))), "dates beyond arithmetic range")
    }
}
