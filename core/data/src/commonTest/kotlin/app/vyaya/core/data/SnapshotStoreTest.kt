package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Budget
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.Person
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** All the app's data on the database: what is saved reads back exactly (CLAUDE.md §2: repositories on an in-memory DB). */
class SnapshotStoreTest {

    private val t = Instant.parse("2026-10-27T10:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    private fun sample(): Snapshot {
        val account = Account(EntityId.random(), "HDFC Salary", AccountKind.BANK, "1234", t, false)
        val food = Category(EntityId.random(), "Food", CategoryKind.EXPENSE, t, false)
        val rahul = Person(EntityId.random(), "Rahul", t, false)
        return Snapshot(
            Settings("INR", ist, MonthSchedule.of(1).withStartDay(25, LocalDate(2026, 3, 10)), alertsEnabled = true),
            listOf(account), listOf(food),
            listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(12_050), CapturedAt(t, ist), account.id, null, food.id, "Swiggy", "lunch",
                listOf("office"), EntrySource.NOTIFICATION, "429100000001", emptyList(), null, t, t, false)),
            listOf(RecurringRule(EntityId.random(), EntryType.EXPENSE, Money(11_900), 5, food.id, account.id, "Spotify", false, LocalDate(2026, 1, 5), t, false)),
            listOf(rahul),
            listOf(LedgerEntry(EntityId.random(), rahul.id, LedgerDirection.LENT, Money(30_000), LocalDate(2026, 10, 20), "Dinner", false, t, false)),
            listOf(Budget(EntityId.random(), food.id, Money(500_000), t, false)),
            listOf(MerchantRule(EntityId.random(), "swiggy", food.id, t, false)),
        )
    }

    @Test
    fun everything_saved_reads_back_exactly() = runTest {
        val db = inMemoryDatabase()
        val store = SnapshotStore(db)
        val s = sample()
        store.save(s)
        assertEquals(s.normalized(), store.load())
        db.close()
    }

    @Test
    fun saving_again_replaces_records_and_settings() = runTest {
        val db = inMemoryDatabase()
        val store = SnapshotStore(db)
        val s = sample()
        store.save(s)
        val renamed = s.people.single().copy(name = "Rahul S", updatedAt = t + 1.minutes)
        val changed = s.copy(people = listOf(renamed), settings = s.settings.copy(alertsEnabled = false))
        store.save(changed)
        assertEquals(changed.normalized(), store.load())
        db.close()
    }

    @Test
    fun an_empty_database_loads_as_the_default_snapshot() = runTest {
        val db = inMemoryDatabase()
        assertEquals(Snapshot.empty(), SnapshotStore(db).load())
        db.close()
    }

    @Test
    fun records_saved_without_settings_load_with_the_default_settings() = runTest {
        val db = inMemoryDatabase()
        val person = sample().people.single()
        db.records().upsert(listOf(RecordRow("person", person.id.value, app.vyaya.core.backup.RecordLines.of(sample()).people.single(), false, 0)))
        assertEquals(Settings.default(), SnapshotStore(db).load().settings)
        db.close()
    }

    @Test
    fun a_damaged_row_is_reported_not_hidden() = runTest {
        val db = inMemoryDatabase()
        db.records().upsert(listOf(RecordRow("person", "x", "{not a person", false, 0)))
        kotlin.test.assertFailsWith<IllegalStateException> { SnapshotStore(db).load() }
        db.close()
    }
}
