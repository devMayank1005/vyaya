package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/** Budgets on the database: one per spending category, and a suggestion from the last finished months. */
class BudgetStoreTest {

    private val t = Instant.parse("2026-11-12T06:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")
    private val today = LocalDate(2026, 11, 12)

    private fun <T> saved(r: CatalogResult<T>): T = assertIs<Saved<T>>(r).item

    private fun spend(category: EntityId?, rupees: Long, at: String, type: EntryType = EntryType.EXPENSE) = Entry(EntityId.random(), type,
        Money(rupees * 100), CapturedAt(Instant.parse(at), ist), null, null, category, "Shop", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)

    @Test
    fun a_budget_is_set_changed_removed_and_comes_back() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val food = saved(catalog.addCategory("Food", CategoryKind.EXPENSE, t))
        val salary = saved(catalog.addCategory("Salary", CategoryKind.INCOME, t))
        val budgets = BudgetStore(db)
        val b = saved(budgets.set(food, Money(5_000_00), t))
        assertEquals(listOf(food.id to Money(5_000_00)), budgets.budgets().map { it.categoryId to it.limit })
        assertEquals(b.id, saved(budgets.set(food, Money(6_000_00), t)).id, "one budget per category, changed in place")
        assertEquals(listOf(Money(6_000_00)), budgets.budgets().map { it.limit })
        budgets.remove(b, t)
        assertEquals(emptyList(), budgets.budgets())
        assertEquals(b.id, saved(budgets.set(food, Money(4_000_00), t)).id, "a removed budget comes back")
        assertEquals(Refused("Budgets are for spending categories"), budgets.set(salary, Money(100_00), t))
        catalog.deleteCategory(food, t)
        assertEquals(emptyList(), budgets.budgets(), "a deleted category's budget isn't shown")
        assertEquals(Refused("Budgets are for spending categories"), budgets.set(food, Money(100_00), t))
        db.close()
    }

    @Test
    fun no_more_budgets_than_a_backup_can_hold() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val a = saved(catalog.addCategory("A", CategoryKind.EXPENSE, t))
        val b = saved(catalog.addCategory("B", CategoryKind.EXPENSE, t))
        val budgets = BudgetStore(db, BackupLimits(maxRecordsPerType = 1))
        saved(budgets.set(a, Money(100_00), t))
        assertEquals(Refused("Vyaya can keep up to 1 budgets, removed ones included"), budgets.set(b, Money(100_00), t))
        db.close()
    }

    @Test
    fun the_suggestion_averages_the_finished_months_since_the_first_entry() = runTest {
        val db = inMemoryDatabase()
        val food = saved(CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t))
        val settings = Settings.default()
        val budgets = BudgetStore(db)
        assertNull(budgets.suggestion(food.id, settings, today), "no entries yet")
        EntryStore(db).save(listOf(
            spend(food.id, 3_000, "2026-09-10T06:00:00Z"), // September: the first month with an entry
            spend(food.id, 1_000, "2026-10-01T06:00:00Z"), spend(food.id, 4_000, "2026-10-20T06:00:00Z"), // October: 5,000
            spend(null, 9_000, "2026-10-21T06:00:00Z"), // no category
            spend(food.id, 9_999, "2026-10-22T06:00:00Z", EntryType.INCOME), // not spending
            spend(food.id, 7_000, "2026-11-05T06:00:00Z"), // this month isn't finished: left out
        ))
        // Only September and October count (August and before: the user hadn't started). (3,000 + 5,000) / 2 = 4,000.
        assertEquals(BudgetStore.Suggestion(Money(4_000_00), months = 2), budgets.suggestion(food.id, settings, today))
        EntryStore(db).save(listOf(spend(food.id, 1_000, "2026-06-15T06:00:00Z"))) // June: now 3 finished months back (Aug–Oct)
        assertEquals(BudgetStore.Suggestion(Money(2_700_00), months = 3), budgets.suggestion(food.id, settings, today), "(0 + 3,000 + 5,000) / 3 = 2,667, up to 2,700")
        db.close()
    }
}
