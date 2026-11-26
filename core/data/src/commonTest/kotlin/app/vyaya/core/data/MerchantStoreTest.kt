package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Money
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntityId
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
        assertEquals(listOf("Swiggy" to food.id), merchants.rules().map { it.payee to it.categoryId })
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

    @Test
    fun merchant_flaw_003_004_earlier_payments_are_counted_and_follow_only_when_asked() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val food = saved(catalog.addCategory("Food", CategoryKind.EXPENSE, t))
        val treats = saved(catalog.addCategory("Treats", CategoryKind.EXPENSE, t))
        fun e(payee: String, type: EntryType = EntryType.EXPENSE, category: EntityId? = null, deleted: Boolean = false) = Entry(EntityId.random(), type,
            Money(100_00), CapturedAt(t, ist), null, null, category, payee, "", emptyList(), EntrySource.NOTIFICATION, null, emptyList(), null, t, t, deleted)
        val open1 = e("Swiggy"); val open2 = e("SWIGGY@axl"); val chosen = e("Swiggy", category = treats.id)
        val refund = e("Swiggy", type = EntryType.INCOME); val gone = e("Swiggy", deleted = true); val other = e("Zomato")
        EntryStore(db).save(listOf(open1, open2, chosen, refund, gone, other))
        val merchants = MerchantStore(db)
        assertEquals(MerchantStore.Matches(total = 3, uncategorised = 2), merchants.matches("Swiggy", EntryType.EXPENSE))
        val later = Instant.parse("2026-11-12T06:00:00Z")
        assertEquals(2, merchants.applyToEarlier("Swiggy", food.id, EntryType.EXPENSE, later))
        val after = EntryStore(db).all().associateBy { it.id }
        assertEquals(food.id to later, after.getValue(open1.id).let { it.categoryId to it.updatedAt })
        assertEquals(food.id, after.getValue(open2.id).categoryId)
        assertEquals(treats.id, after.getValue(chosen.id).categoryId, "a category the user chose is never changed")
        assertEquals(null, after.getValue(refund.id).categoryId, "income from the payee isn't spending")
        assertEquals(null, after.getValue(gone.id).categoryId)
        assertEquals(null, after.getValue(other.id).categoryId)
        assertEquals(MerchantStore.Matches(0, 0), merchants.matches("@@", EntryType.EXPENSE), "no key, no matches")
        db.close()
    }

    @Test
    fun merchant_edges_a_forgotten_payee_comes_back_and_nameless_payees_do_nothing() = runTest {
        val db = inMemoryDatabase()
        val food = saved(CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t))
        val merchants = MerchantStore(db)
        val first = saved(merchants.remember("Swiggy", food.id, t))
        merchants.forget(first, t)
        assertEquals(first.id, saved(merchants.remember("swiggy", food.id, t)).id, "the forgotten rule, back")
        assertEquals(1, merchants.rules().size)
        assertEquals(0, merchants.applyToEarlier("@@", food.id, EntryType.EXPENSE, t))
        assertEquals("swiggy", merchants.keyOf("SWIGGY@axl"))
        db.close()
    }
}
