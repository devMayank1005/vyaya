package app.vyaya.core.backup

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Generation
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * SIM-001, SIM-002 (core-engine report): the phone's clock was ahead for a while and is right again. What was
 * stamped in the future would block every backup until real time catches up, and catch-up made months early.
 */
class ClockRepairTest {

    private val ahead = Instant.parse("2027-01-15T09:00:00Z") // the wrong clock, 2.5 months ahead
    private val now = Instant.parse("2026-10-28T09:00:00Z") // the clock is right again

    private val stampedAhead: Snapshot = run {
        val october = GOLDEN_V3_SNAPSHOT.entries.single { it.generatedBy != null }
        fun keyed(month: YearMonth) = Generation(R1, month).let { k ->
            october.copy(id = k.entryId, generatedBy = k, occurredAt = CapturedAt(Instant.parse("$month-05T06:30:00Z"), IST), createdAt = ahead, updatedAt = ahead)
        }
        GOLDEN_V3_SNAPSHOT.copy(
            people = GOLDEN_V3_SNAPSHOT.people.map { it.copy(updatedAt = ahead) }, // edited while the clock was ahead
            entries = GOLDEN_V3_SNAPSHOT.entries + keyed(YearMonth(2026, 11)) + keyed(YearMonth(2026, 12)).markDeleted(ahead) + keyed(YearMonth(2027, 1)),
        )
    }

    @Test
    fun it_notices_change_times_after_now() {
        assertTrue(ClockRepair.needed(stampedAhead, now))
        assertFalse(ClockRepair.needed(GOLDEN_V3_SNAPSHOT, now))
        assertIs<EncodeResult.Failure>(BackupCodec.write(stampedAhead, META.copy(exportedAt = now)) {}, "the problem it fixes")
    }

    @Test
    fun repaired_data_can_be_backed_up_and_keeps_this_months_entries() {
        val repaired = ClockRepair.repair(stampedAhead, now)
        assertIs<EncodeResult.Success>(BackupCodec.write(repaired, META.copy(exportedAt = now)) {})
        assertEquals(listOf(YearMonth(2026, 10)), repaired.entries.mapNotNull { it.generatedBy?.month }, "months not yet here are gone")
        assertEquals(now, repaired.people.single().updatedAt, "change times after now become now")
        assertEquals(GOLDEN_V3_SNAPSHOT.people.map { it.name }, repaired.people.map { it.name }, "the edit itself stays")
        assertEquals(GOLDEN_V3_SNAPSHOT.entries.filter { it.generatedBy == null }, repaired.entries.filter { it.generatedBy == null })
    }

    @Test
    fun data_with_a_right_clock_is_left_alone() {
        val fine = GOLDEN_V3_SNAPSHOT
        assertSame(fine, ClockRepair.repair(fine, now))
    }

    @Test
    fun this_months_generated_entries_not_yet_due_are_removed_too() { // DATA-008 (branch review)
        val october = GOLDEN_V3_SNAPSHOT.entries.single { it.generatedBy != null } // Spotify, due 5 Oct
        val early = october.copy(createdAt = ahead, updatedAt = ahead)
        val fourthOfOctober = Instant.parse("2026-10-04T09:00:00Z") // the clock is right again, before the 5th
        val repaired = ClockRepair.repair(GOLDEN_V3_SNAPSHOT.copy(entries = GOLDEN_V3_SNAPSHOT.entries - october + early), fourthOfOctober)
        assertEquals(emptyList(), repaired.entries.filter { it.generatedBy != null })
    }
}
