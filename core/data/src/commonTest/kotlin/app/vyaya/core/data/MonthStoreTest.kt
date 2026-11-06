package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The home screen's month from the database: what's in, what's out, what's left, and the month's entries. */
class MonthStoreTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val made = Instant.parse("2026-11-01T00:00:00Z")
    private fun entry(type: EntryType, rupees: Long, at: String, zone: TimeZone = ist, deleted: Boolean = false) =
        Entry(EntityId.random(), type, Money(rupees * 100), CapturedAt(Instant.parse(at), zone), null, null, null, "x", "", emptyList(),
            EntrySource.MANUAL, null, emptyList(), null, made, made, deleted)

    @Test
    fun a_month_is_its_entries_by_each_entrys_own_date_newest_first() = runTest {
        val db = inMemoryDatabase()
        val salary = entry(EntryType.INCOME, 50_000, "2026-11-01T04:30:00Z")
        val rentLate = entry(EntryType.EXPENSE, 15_000, "2026-11-30T19:00:00Z") // 1 Dec 00:30 IST: December
        val lastNightOct = entry(EntryType.EXPENSE, 300, "2026-10-31T18:00:00Z") // 31 Oct 23:30 IST: October
        val food = entry(EntryType.EXPENSE, 250, "2026-11-05T06:00:00Z")
        val transfer = entry(EntryType.TRANSFER, 5_000, "2026-11-06T06:00:00Z")
        val undone = entry(EntryType.EXPENSE, 999, "2026-11-07T06:00:00Z", deleted = true)
        EntryStore(db).save(listOf(salary, rentLate, lastNightOct, food, transfer, undone))
        val settings = Snapshot.empty().settings
        val november = settings.monthSchedule.monthContaining(LocalDate(2026, 11, 6))
        val view = MonthStore(db).watch(november, settings).first()
        assertEquals(listOf(transfer, food, salary), view.entries, "this month's, newest first, without deleted ones")
        assertEquals(Money(50_000_00), view.summary.income)
        assertEquals(Money(250_00), view.summary.spent)
        assertEquals(Money(49_750_00), view.summary.left)
        db.close()
    }

    @Test
    fun a_custom_month_start_is_followed() = runTest {
        val db = inMemoryDatabase()
        val before = entry(EntryType.EXPENSE, 100, "2026-11-24T06:00:00Z")
        val after = entry(EntryType.EXPENSE, 200, "2026-11-25T06:00:00Z")
        EntryStore(db).save(listOf(before, after))
        val settings = Snapshot.empty().settings.copy(monthSchedule = MonthSchedule.of(25))
        val month = settings.monthSchedule.monthContaining(LocalDate(2026, 11, 30))
        assertEquals(listOf(after), MonthStore(db).watch(month, settings).first().entries)
        db.close()
    }

    @Test
    fun the_month_follows_new_entries() = runTest {
        val db = inMemoryDatabase()
        val settings = Snapshot.empty().settings
        val november = settings.monthSchedule.monthContaining(LocalDate(2026, 11, 6))
        val store = MonthStore(db)
        assertEquals(Money(0), store.watch(november, settings).first().summary.left)
        EntryStore(db).save(listOf(entry(EntryType.EXPENSE, 250, "2026-11-05T06:00:00Z")))
        assertEquals(Money(-250_00), store.watch(november, settings).first { it.entries.isNotEmpty() }.summary.left)
        assertEquals(settings, store.settings())
        db.close()
    }
}
