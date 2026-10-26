package app.vyaya.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** FLAW-004: an entry belongs to the month of the zone it was logged in, whatever the phone's zone is later. */
class CapturedAtTest {

    private val lateNightUtc = Instant.parse("2026-03-31T20:00:00Z")
    private val schedule = MonthSchedule.of(startDay = 1)

    @Test
    fun local_date_is_taken_in_the_capture_zone() {
        assertEquals(LocalDate(2026, 4, 1), CapturedAt(lateNightUtc, TimeZone.of("Asia/Kolkata")).localDate)
        assertEquals(LocalDate(2026, 3, 31), CapturedAt(lateNightUtc, TimeZone.of("America/New_York")).localDate)
    }

    @Test
    fun the_month_comes_from_the_entry_not_from_the_current_zone() {
        // Logged in India at 01:30 on 1 Apr: it stays in April even when viewed later from New York.
        val loggedInIndia = CapturedAt(lateNightUtc, TimeZone.of("Asia/Kolkata"))
        val loggedInNewYork = CapturedAt(lateNightUtc, TimeZone.of("America/New_York"))
        assertEquals(LocalDate(2026, 4, 1), schedule.monthOf(loggedInIndia).start)
        assertEquals(LocalDate(2026, 3, 1), schedule.monthOf(loggedInNewYork).start)
    }

    @Test
    fun the_zone_id_is_kept_for_storage_and_backup() {
        assertEquals("Asia/Kolkata", CapturedAt(lateNightUtc, TimeZone.of("Asia/Kolkata")).timeZone.id)
    }
}
