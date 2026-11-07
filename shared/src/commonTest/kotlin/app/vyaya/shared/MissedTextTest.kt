package app.vyaya.shared

import app.vyaya.ui.missedText
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/** Recurring report #2: the months missed while paused, said briefly (runs on Android and iOS). */
class MissedTextTest {
    private fun YearMonth.plusMonths(n: Int): YearMonth = YearMonth(year + (month.ordinal + n) / 12, (month.ordinal + n) % 12 + 1)

    @Test
    fun a_long_pause_is_said_as_a_range() {
        assertEquals("2 months were missed while it was paused (Oct 2026 and Nov 2026)", missedText(listOf(YearMonth(2026, 10), YearMonth(2026, 11))))
        assertEquals("14 months were missed while it was paused (Jan 2024 to Feb 2025)", missedText((0 until 14).map { YearMonth(2024, 1).plusMonths(it) }))
        assertEquals("1 month was missed while it was paused (Nov 2026)", missedText(listOf(YearMonth(2026, 11))))
    }
}
