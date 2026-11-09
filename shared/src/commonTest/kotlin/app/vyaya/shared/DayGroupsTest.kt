package app.vyaya.shared

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.ui.dayGroups
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The home screen's entries grouped by day, newest first, each day with what went out and came in (Android and iOS). */
class DayGroupsTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private fun e(type: EntryType, rupees: Long, at: String) = Entry(EntityId.random(), type, Money(rupees * 100), CapturedAt(Instant.parse(at), ist),
        null, null, null, "x", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, Instant.parse(at), Instant.parse(at), false)

    @Test
    fun entries_are_grouped_by_their_own_day_with_friendly_labels_and_totals() {
        val today = LocalDate(2026, 11, 7)
        val lunch = e(EntryType.EXPENSE, 250, "2026-11-07T07:00:00Z")
        val tea = e(EntryType.EXPENSE, 20, "2026-11-07T04:00:00Z")
        val salary = e(EntryType.INCOME, 50_000, "2026-11-06T05:00:00Z")
        val late = e(EntryType.EXPENSE, 300, "2026-11-04T18:00:00Z") // 23:30 IST on the 4th
        val groups = dayGroups(listOf(lunch, tea, salary, late), today)
        assertEquals(listOf("Today", "Yesterday", "Wed 4 Nov"), groups.map { it.label })
        assertEquals(listOf(listOf(lunch, tea), listOf(salary), listOf(late)), groups.map { it.entries })
        assertEquals(listOf(Money(270_00), Money(0), Money(300_00)), groups.map { it.spent })
        assertEquals(Money(50_000_00), groups[1].received)
    }

    @Test
    fun another_year_shows_the_year() {
        val groups = dayGroups(listOf(e(EntryType.EXPENSE, 10, "2025-12-31T06:00:00Z")), LocalDate(2026, 1, 2))
        assertEquals("Wed 31 Dec 2025", groups.single().label)
    }
}
