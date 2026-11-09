package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The user's categories and accounts: add, rename, delete (soft, so old entries keep their names). */
class CatalogStoreTest {

    private val t = Instant.parse("2026-11-09T10:00:00Z")

    @Test
    fun categories_can_be_added_renamed_and_deleted() = runTest {
        val db = inMemoryDatabase()
        AppOpen(db).run(t) // the default categories
        val catalog = CatalogStore(db)
        val pets = saved(catalog.addCategory("Pets", CategoryKind.EXPENSE, t))
        assertTrue(catalog.categories().any { it.id == pets.id })
        assertEquals(Refused("A category of this type already has that name"), catalog.addCategory("pets ", CategoryKind.EXPENSE, t))
        catalog.renameCategory(pets, "Pet care", t)
        assertEquals("Pet care", catalog.categories().single { it.id == pets.id }.name)
        catalog.deleteCategory(pets, t)
        assertTrue(catalog.categories().none { it.id == pets.id }, "not offered any more")
        assertEquals("Pet care", catalog.allCategories().single { it.id == pets.id }.name, "still named for old entries")
        db.close()
    }

    @Test
    fun accounts_can_be_added_renamed_and_deleted() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val hdfc = saved(catalog.addAccount("HDFC Salary", AccountKind.BANK, "1234", t))
        val cash = saved(catalog.addAccount("Cash", AccountKind.CASH, null, t))
        assertEquals(listOf("Cash", "HDFC Salary"), catalog.accounts().map { it.name })
        assertEquals(Refused("An account already has that name"), catalog.addAccount("cash", AccountKind.CASH, null, t))
        assertEquals(Refused("Last digits: 3 to 6 digits"), catalog.addAccount("Card", AccountKind.CARD, "12", t))
        catalog.updateAccount(hdfc, "HDFC", hdfc.kind, hdfc.mask, t)
        catalog.deleteAccount(cash, t)
        assertEquals(listOf("HDFC"), catalog.accounts().map { it.name })
        db.close()
    }

    @Test
    fun catalog_data_001_editing_an_account_saves_its_type_and_last_digits() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val hdfc = saved(catalog.addAccount("HDFC", AccountKind.BANK, "1234", t))
        saved(catalog.updateAccount(hdfc, "HDFC card", AccountKind.CARD, "5678", t))
        val now = catalog.accounts().single()
        assertEquals(Triple("HDFC card", AccountKind.CARD, "5678"), Triple(now.name, now.kind, now.mask))
        assertEquals(Refused("Last digits: 3 to 6 digits"), catalog.updateAccount(now, "HDFC card", AccountKind.CARD, "12a", t))
        saved(catalog.updateAccount(now, "HDFC card", AccountKind.CARD, "", t))
        assertEquals(null, catalog.accounts().single().mask, "last digits can be cleared")
        db.close()
    }

    @Test
    fun catalog_data_002_no_category_or_account_past_what_a_backup_can_hold() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db, BackupLimits(maxRecordsPerType = 2))
        val a = saved(catalog.addCategory("A", CategoryKind.EXPENSE, t))
        saved(catalog.addCategory("B", CategoryKind.EXPENSE, t))
        catalog.deleteCategory(a, t) // still in every backup, so it still counts
        assertEquals(Refused("Vyaya can keep up to 2 categories, deleted ones included"), catalog.addCategory("C", CategoryKind.INCOME, t))
        saved(catalog.addAccount("X", AccountKind.CASH, null, t))
        saved(catalog.addAccount("Y", AccountKind.CASH, null, t))
        assertEquals(Refused("Vyaya can keep up to 2 accounts, deleted ones included"), catalog.addAccount("Z", AccountKind.CASH, null, t))
        db.close()
    }

    @Test
    fun catalog_flaw_003_adding_a_deleted_name_brings_it_back() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val food = saved(catalog.addCategory("Food", CategoryKind.EXPENSE, t))
        catalog.deleteCategory(food, t)
        val again = saved(catalog.addCategory("food", CategoryKind.EXPENSE, t))
        assertEquals(food.id, again.id, "the same category, so old and new entries stay together")
        assertEquals(listOf("food"), catalog.allCategories().map { it.name })
        assertTrue(catalog.categories().any { it.id == food.id })
        val income = saved(catalog.addCategory("Food", CategoryKind.INCOME, t))
        assertTrue(income.id != food.id, "a different type is a different category")
        val cash = saved(catalog.addAccount("Cash", AccountKind.CASH, null, t))
        catalog.deleteAccount(cash, t)
        assertEquals(cash.id, saved(catalog.addAccount("Cash", AccountKind.WALLET, null, t)).id)
        assertEquals(AccountKind.WALLET, catalog.accounts().single().kind)
        db.close()
    }

    @Test
    fun catalog_flaw_005_007_a_delete_says_how_many_entries_and_rules_use_it() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val pets = saved(catalog.addCategory("Pets", CategoryKind.EXPENSE, t))
        val hdfc = saved(catalog.addAccount("HDFC", AccountKind.BANK, "1234", t))
        fun entry(category: Category?, account: Account?, deleted: Boolean = false) = Entry(
            EntityId.random(), EntryType.EXPENSE, Money(100_00), CapturedAt(t, TimeZone.UTC), account?.id, null, category?.id, "Vet", "",
            emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, deleted,
        )
        EntryStore(db).save(listOf(entry(pets, hdfc), entry(pets, null), entry(pets, hdfc, deleted = true), entry(null, hdfc)))
        RulesStore(db).add(EntryType.EXPENSE, Money(500_00), 5, pets.id, "Food", t, TimeZone.UTC, accountId = hdfc.id)
        assertEquals(CatalogStore.Uses(entries = 2, rules = 1), catalog.uses(pets))
        assertEquals(CatalogStore.Uses(entries = 2, rules = 1), catalog.uses(hdfc))
        db.close()
    }

    @Test
    fun catalog_pen_008_a_bad_name_is_refused_not_thrown() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val pets = saved(catalog.addCategory("Pets", CategoryKind.EXPENSE, t))
        saved(catalog.addCategory("Food", CategoryKind.EXPENSE, t))
        assertEquals(Refused("Enter a name"), catalog.renameCategory(pets, "   ", t))
        assertEquals(Refused("A name can be up to 100 characters"), catalog.renameCategory(pets, "y".repeat(101), t))
        assertEquals(Refused("A name can be up to 100 characters"), catalog.addCategory("y".repeat(101), CategoryKind.EXPENSE, t))
        assertEquals(Refused("A category of this type already has that name"), catalog.renameCategory(pets, "FOOD", t))
        assertEquals(Saved(pets.copy(name = "pets", updatedAt = t)), catalog.renameCategory(pets, "pets", t), "its own name in another case")
        val cash = saved(catalog.addAccount("Cash", AccountKind.CASH, null, t))
        saved(catalog.addAccount("HDFC", AccountKind.BANK, null, t))
        assertEquals(Refused("Enter a name"), catalog.updateAccount(cash, "", AccountKind.CASH, null, t))
        assertEquals(Refused("An account already has that name"), catalog.updateAccount(cash, "hdfc", AccountKind.CASH, null, t))
        catalog.deleteCategory(pets, t)
        catalog.deleteAccount(cash, t)
        assertEquals(Refused("This was deleted meanwhile"), catalog.renameCategory(pets, "Pet care", t), "a form left open while it was deleted")
        assertEquals(Refused("This was deleted meanwhile"), catalog.updateAccount(cash, "Wallet", AccountKind.CASH, null, t))
        db.close()
    }

    private fun <T> saved(r: CatalogResult<T>): T = assertIs<Saved<T>>(r).item
}
