package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** Merchant memory: "always put Swiggy in Food", kept as a record and applied by capture. */
class MerchantStoreTest {

    private val t = Instant.parse("2026-11-11T06:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    private fun <T> saved(r: CatalogResult<T>): T = assertIs<Saved<T>>(r).item

    @Test
    fun a_payee_is_remembered_changed_and_forgotten() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val food = saved(catalog.addCategory("Food", CategoryKind.EXPENSE, t))
        val treats = saved(catalog.addCategory("Treats", CategoryKind.EXPENSE, t))
        val merchants = MerchantStore(db)
        val rule = saved(merchants.remember("Swiggy", food.id, t))
        assertEquals(listOf("swiggy" to food.id), merchants.rules().map { it.payee to it.categoryId })
        // The same payee however it's written (case, a UPI handle) is one rule, changed in place.
        assertEquals(rule.id, saved(merchants.remember("SWIGGY@axl", treats.id, t)).id)
        assertEquals(listOf(treats.id), merchants.rules().map { it.categoryId })
        merchants.forget(rule, t)
        assertEquals(emptyList(), merchants.rules())
        assertEquals(Refused("This payee has no letters or digits to remember it by"), merchants.remember("@@ --", food.id, t))
        db.close()
    }

    @Test
    fun no_more_rules_than_a_backup_can_hold() = runTest {
        val db = inMemoryDatabase()
        val food = saved(CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t))
        val merchants = MerchantStore(db, BackupLimits(maxRecordsPerType = 1))
        saved(merchants.remember("Swiggy", food.id, t))
        assertEquals(Refused("Vyaya can remember up to 1 payees, forgotten ones included"), merchants.remember("Zomato", food.id, t))
        db.close()
    }

    @Test
    fun capture_puts_a_remembered_payee_in_its_category() = runTest {
        val db = inMemoryDatabase()
        val food = saved(CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t))
        MerchantStore(db).remember("Swiggy", food.id, t)
        val raw = RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹250 paid to Swiggy", CapturedAt(t, ist))
        val added = assertIs<Outcome.Added>(CaptureStore(db).process(raw, t).single()).entry
        assertEquals(food.id, EntryStore(db).byId(added.id)?.categoryId)
        db.close()
    }
}
