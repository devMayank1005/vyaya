package app.vyaya.core.data

import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** Split bills (option A): across the user's categories, and with friends, whose shares go to People as lent. */
class SplitStoreTest {

    private val t = Instant.parse("2026-11-18T14:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    private fun <T> saved(r: CatalogResult<T>): T = assertIs<Saved<T>>(r).item

    private fun payment(rupees: Long, payee: String, category: EntityId? = null) = Entry(EntityId.random(), EntryType.EXPENSE, Money(rupees * 100),
        CapturedAt(t, ist), null, null, category, payee, "", emptyList(), EntrySource.NOTIFICATION, null, emptyList(), null, t, t, false)

    @Test
    fun a_dinner_with_two_friends_is_your_share_spent_and_theirs_lent() = runTest {
        val db = inMemoryDatabase()
        val food = saved(CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t))
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t); val priya = ledger.addPerson("Priya", t)
        val dinner = payment(1_200, "Barbeque Nation", food.id)
        val out = saved(SplitStore(db).save(dinner, mine = emptyList(), friends = listOf(rahul.id to Money(400_00), priya.id to Money(400_00)), now = t))
        assertEquals(listOf(Split(food.id, Money(400_00)), Split(SystemCategories.LENT.id, Money(800_00))), out.splits)
        assertEquals(out, EntryStore(db).byId(dinner.id))
        val lent = listOf(rahul, priya).map { p -> ledger.entries(p.id).single() }
        assertEquals(listOf(LedgerDirection.LENT to Money(400_00), LedgerDirection.LENT to Money(400_00)), lent.map { it.direction to it.amount })
        assertEquals("Barbeque Nation: their share of ₹1,200", lent.first().note)
        assertEquals(Money(800_00), ledger.balances().owedToYou)
        val month = Settings.default().monthSchedule.monthContaining(kotlinx.datetime.LocalDate(2026, 11, 18))
        assertEquals(Money(400_00), MonthStore(db).watch(month, Settings.default()).first().summary.spent, "only your share is spent")
        db.close()
    }

    @Test
    fun across_your_own_categories_only() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        val groceries = saved(catalog.addCategory("Groceries", CategoryKind.EXPENSE, t))
        val home = saved(catalog.addCategory("Household", CategoryKind.EXPENSE, t))
        val dmart = payment(2_000, "DMart")
        val out = saved(SplitStore(db).save(dmart, mine = listOf(Split(groceries.id, Money(1_500_00)), Split(home.id, Money(500_00))), friends = emptyList(), now = t))
        assertEquals(listOf(Split(groceries.id, Money(1_500_00)), Split(home.id, Money(500_00))), out.splits)
        assertEquals(groceries.id, out.categoryId, "shown under its first part")
        assertEquals(0, LedgerStore(db).balances().owedToYou.paise)
        db.close()
    }

    @Test
    fun shares_that_dont_add_up_are_refused_with_a_reason() = runTest {
        val db = inMemoryDatabase()
        val food = saved(CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t))
        val rahul = LedgerStore(db).addPerson("Rahul", t)
        val store = SplitStore(db)
        assertEquals(Refused("Your parts add up to ₹900; they should be ₹800 (₹1,200 less ₹400 for friends)"),
            store.save(payment(1_200, "X"), listOf(Split(food.id, Money(900_00))), listOf(rahul.id to Money(400_00)), t))
        assertEquals(Refused("Friends' shares (₹1,200) leave nothing for you; record it in People instead"),
            store.save(payment(1_200, "X", food.id), emptyList(), listOf(rahul.id to Money(1_200_00)), t))
        assertEquals(Refused("Choose a category for your share"), store.save(payment(1_200, "X"), emptyList(), listOf(rahul.id to Money(400_00)), t))
        assertEquals(Refused("Only spending can be split"), store.save(payment(1_200, "X", food.id).copy(type = EntryType.INCOME), emptyList(),
            listOf(rahul.id to Money(400_00)), t))
        assertEquals(0, EntryStore(db).all().size, "nothing saved when refused")
        assertEquals(0, LedgerStore(db).balances().owedToYou.paise)
        db.close()
    }
}
