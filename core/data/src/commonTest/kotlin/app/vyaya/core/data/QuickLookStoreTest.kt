package app.vyaya.core.data

import app.vyaya.core.engine.SafeToSpend
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The widget (Phase 3): the same safe-to-spend Home shows, and whether the widget may show amounts (off by default). */
class QuickLookStoreTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-18T06:30:00Z") // 18 Nov, noon in India

    private fun entry(type: EntryType, rupees: Long, at: Instant, payee: String) = Entry(EntityId.random(), type, Money(rupees * 100), CapturedAt(at, ist),
        null, null, null, payee, "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, at, at, false)

    @Test
    fun amounts_are_hidden_until_the_user_shows_them_and_the_choice_stays_on_the_phone() = runTest {
        val db = inMemoryDatabase()
        val look = QuickLookStore(db)
        assertFalse(look.showAmounts())
        look.setShowAmounts(true)
        assertTrue(QuickLookStore(db).showAmounts())
        assertTrue(SnapshotStore(db).load().let { it.settings == Settings.default() }, "not a setting a backup carries")
        look.setShowAmounts(false)
        assertFalse(look.showAmounts())
        db.close()
    }

    @Test
    fun safe_to_spend_is_what_home_works_out_with_bills_and_lending() = runTest {
        val db = inMemoryDatabase()
        val food = (CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, now) as CatalogResult.Saved).item
        RulesStore(db).add(EntryType.EXPENSE, Money(15_000_00), 25, null, "Rent", Instant.parse("2026-11-01T06:00:00Z"), ist)
        EntryStore(db).save(listOf(entry(EntryType.INCOME, 60_000, Instant.parse("2026-11-01T06:00:00Z"), "Salary"),
            entry(EntryType.EXPENSE, 2_000, Instant.parse("2026-11-10T06:00:00Z"), "DMart")))
        val rahul = LedgerStore(db).addPerson("Rahul", now)
        SplitStore(db).save(entry(EntryType.EXPENSE, 1_200, Instant.parse("2026-11-12T14:00:00Z"), "Dinner").copy(categoryId = food.id),
            emptyList(), listOf(rahul.id to Money(600_00)), now)

        val got = QuickLookStore(db).safeToSpend(now)!!
        // Independently: the same inputs Home passes to the engine.
        val settings = SnapshotStore(db).loadRecords().settings
        val month = settings.monthSchedule.monthContaining(LocalDate(2026, 11, 18))
        val view = MonthStore(db).watch(month, settings).first()
        val want = SafeToSpend.of(view.summary, RulesStore(db).active(), RulesStore(db).madeKeys(), LocalDate(2026, 11, 18), view.entries,
            RulesStore(db).linkedPayments(), lent = Money(600_00))
        assertEquals(want, got)
        assertEquals(Money(15_000_00) to Money(600_00), got.billsDue to got.lent)
        db.close()
    }

    @Test
    fun an_empty_phone_has_nothing_safe_to_spend_yet() = runTest {
        val db = inMemoryDatabase()
        assertEquals(Money(0), QuickLookStore(db).safeToSpend(now)!!.perDay)
        db.close()
    }
}
