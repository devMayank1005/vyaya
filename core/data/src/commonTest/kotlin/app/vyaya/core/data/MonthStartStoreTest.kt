package app.vyaya.core.data

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Safe-to-spend FLAW-002: the month's start day, set by the user, and the salary-day suggestion (with "not now"). */
class MonthStartStoreTest {

    private val t = Instant.parse("2026-11-12T06:00:00Z")
    private val today = LocalDate(2026, 11, 12)

    private fun salary(at: String) = Entry(EntityId.random(), EntryType.INCOME, Money(60_000_00), CapturedAt(Instant.parse(at), TimeZone.of("Asia/Kolkata")),
        null, null, null, "Employer", "", emptyList(), EntrySource.SMS, null, emptyList(), null, t, t, false)

    @Test
    fun the_start_day_changes_from_the_next_month_and_is_in_the_backup() = runTest {
        val db = inMemoryDatabase()
        val months = MonthStartStore(db)
        assertEquals(1, months.startDay(today))
        months.setStartDay(29, today)
        assertEquals(1, months.startDay(today), "this month keeps its start")
        assertEquals(29, months.startDay(LocalDate(2027, 1, 15)), "later months start on the 29th")
        val backup = BackupCodec.encode(SnapshotStore(db).load(), ExportMeta("1", "android", t))
        assertTrue(backup.contains("\"startDay\":29"), "the schedule is part of the settings in a backup")
        db.close()
    }

    @Test
    fun the_salary_day_is_suggested_until_taken_or_put_off() = runTest {
        val db = inMemoryDatabase()
        val months = MonthStartStore(db)
        assertNull(months.salarySuggestion(today))
        EntryStore(db).save(listOf(salary("2026-09-30T06:00:00Z"), salary("2026-10-29T06:00:00Z")))
        assertEquals(29, months.salarySuggestion(today))
        months.notNow(29)
        assertNull(months.salarySuggestion(today), "not asked again for the same day")
        EntryStore(db).save(listOf(salary("2026-08-10T06:00:00Z").copy(id = EntityId.random())))
        months.setStartDay(29, today)
        assertNull(months.salarySuggestion(LocalDate(2027, 1, 15)), "taken: nothing to suggest")
        db.close()
    }
}
