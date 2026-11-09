package app.vyaya.core.data

import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.CategoryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        val pets = catalog.addCategory("Pets", CategoryKind.EXPENSE, t)!!
        assertTrue(catalog.categories().any { it.id == pets.id })
        assertNull(catalog.addCategory("pets ", CategoryKind.EXPENSE, t), "the same name again")
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
        val hdfc = catalog.addAccount("HDFC Salary", AccountKind.BANK, "1234", t)!!
        val cash = catalog.addAccount("Cash", AccountKind.CASH, null, t)!!
        assertEquals(listOf("Cash", "HDFC Salary"), catalog.accounts().map { it.name })
        assertNull(catalog.addAccount("cash", AccountKind.CASH, null, t))
        assertNull(catalog.addAccount("Card", AccountKind.CARD, "12", t), "a card's last digits are 3–6")
        catalog.renameAccount(hdfc, "HDFC", t)
        catalog.deleteAccount(cash, t)
        assertEquals(listOf("HDFC"), catalog.accounts().map { it.name })
        db.close()
    }
}
