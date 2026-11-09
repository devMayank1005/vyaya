package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Saved so far: each finished month's Saved (or Overspent), calculated from the database, never stored (rule 8.5). */
class SavingsStoreTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private fun e(type: EntryType, rupees: Long, at: String, deleted: Boolean = false) = Entry(EntityId.random(), type, Money(rupees * 100),
        CapturedAt(Instant.parse(at), ist), null, null, null, "x", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null,
        Instant.parse(at), Instant.parse(at), deleted)

    private val entries = listOf(
        e(EntryType.INCOME, 10_000, "2026-09-03T05:00:00Z"), e(EntryType.EXPENSE, 4_000, "2026-09-10T05:00:00Z"),
        e(EntryType.EXPENSE, 3_000, "2026-09-11T05:00:00Z", deleted = true), e(EntryType.TRANSFER, 9_000, "2026-09-12T05:00:00Z"),
        e(EntryType.INCOME, 5_000, "2026-10-01T05:00:00Z"), e(EntryType.EXPENSE, 7_000, "2026-10-20T05:00:00Z"),
        e(EntryType.INCOME, 1_000, "2026-11-02T05:00:00Z"), // this month: not finished
    )

    @Test
    fun finished_months_add_up_and_this_month_doesnt_count() = runTest {
        val db = inMemoryDatabase()
        EntryStore(db).save(entries)
        val s = SavingsStore(db).summary(Snapshot.empty().settings, today = LocalDate(2026, 11, 9))
        assertEquals(Money(6_000_00), s.saved, "September saved 6,000")
        assertEquals(Money(2_000_00), s.overspent, "October overspent 2,000")
        assertEquals(2, s.months)
        assertEquals(LocalDate(2026, 9, 1), s.since)
        db.close()
    }

    @Test
    fun nothing_yet_is_nothing() = runTest {
        val db = inMemoryDatabase()
        val s = SavingsStore(db).summary(Snapshot.empty().settings, today = LocalDate(2026, 11, 9))
        assertEquals(SavingsSummary(Money(0), Money(0), 0, null), s)
        db.close()
    }

    @Test
    fun a_custom_month_start_is_followed() = runTest {
        val db = inMemoryDatabase()
        EntryStore(db).save(listOf(e(EntryType.INCOME, 1_000, "2026-10-26T05:00:00Z"), e(EntryType.EXPENSE, 400, "2026-11-20T05:00:00Z")))
        val settings = Snapshot.empty().settings.copy(monthSchedule = MonthSchedule.of(25))
        // 25 Oct – 24 Nov has ended by 30 Nov: saved 600.
        assertEquals(Money(600_00), SavingsStore(db).summary(settings, LocalDate(2026, 11, 30)).saved)
        assertEquals(0, SavingsStore(db).summary(settings, LocalDate(2026, 11, 20)).months, "not ended yet")
        db.close()
    }
}
