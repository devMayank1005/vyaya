package app.vyaya.core.engine

import app.vyaya.core.model.Entry
import app.vyaya.core.model.MonthSchedule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.yearMonth

/**
 * Safe-to-spend FLAW-002: a month that starts on the 1st while the salary comes on the 29th spends each salary in the
 * month after it, so most of the month shows nothing safe to spend. When the largest income of each recent finished
 * month came on about the same day, that day is suggested as the month's start.
 */
object SalaryDay {

    /** How far apart the salary days may be (and how near the current start day counts as "already there"). */
    private const val NEAR = 3

    /**
     * The day to start the month on, or null. Looks at the last 3 finished calendar months before [today]'s: the
     * largest income of each (not deleted). At least 2 of them, all within [NEAR] days of each other; the earliest
     * day is suggested, so the salary always falls inside its month. Null when the month already starts within
     * [NEAR] days of it.
     */
    fun suggest(incomes: List<Entry>, schedule: MonthSchedule, today: LocalDate): Int? {
        var month = today.yearMonth.minusMonth()
        val days = mutableListOf<Int>()
        repeat(3) {
            val ym: YearMonth = month
            incomes.filter { !it.deleted && it.countsAsIncome && it.occurredAt.localDate.yearMonth == ym }
                .maxWithOrNull(compareBy({ it.amount.paise }, { it.id.value }))
                ?.let { days += it.occurredAt.localDate.day }
            month = month.minusMonth()
        }
        if (days.size < 2 || days.max() - days.min() > NEAR) return null
        val day = days.min()
        // The start day the user has chosen: a change still to come counts (it takes effect from the next month).
        val startsOn = schedule.changes.lastOrNull()?.startDay ?: schedule.initialStartDay
        return if (kotlin.math.abs(day - startsOn) <= NEAR) null else day
    }
}
