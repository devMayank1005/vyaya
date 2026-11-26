package app.vyaya.core.data

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.ExportMeta
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
import app.vyaya.core.model.Generation
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Money
import app.vyaya.core.model.Person
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Split
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/** core-data DATA-006: what a backup of a damaged database keeps, once the unreadable records are left out. */
class DamageRepairTest {

    private val t = Instant.parse("2026-11-26T06:00:00Z")
    private val lostCategory = EntityId.random(); private val lostAccount = EntityId.random(); private val lostRule = EntityId.random()
    private val lostPerson = EntityId.random(); private val lostEntry = EntityId.random()
    private val food = Category(EntityId.random(), "Food", CategoryKind.EXPENSE, t, false)
    private val bank = Account(EntityId.random(), "HDFC", AccountKind.BANK, "1234", t, false)
    private val rahul = Person(EntityId.random(), "Rahul", t, false)

    private fun entry(rupees: Long) = Entry(EntityId.random(), EntryType.EXPENSE, Money(rupees * 100), CapturedAt(t, TimeZone.of("Asia/Kolkata")),
        null, null, null, "Shop", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)

    @Test
    fun data_006_references_to_lost_records_are_detached_and_what_cant_stand_alone_is_left_out() {
        val keyed = Generation(lostRule, YearMonth(2026, 11))
        val generated = entry(500).copy(id = keyed.entryId, generatedBy = keyed, source = EntrySource.RECURRING)
        val split = entry(300).copy(categoryId = food.id, splits = listOf(Split(food.id, Money(200_00)), Split(lostCategory, Money(100_00))))
        val linked = entry(100).copy(categoryId = lostCategory, accountId = lostAccount, linkedEntryId = lostEntry)
        val fine = entry(50).copy(categoryId = food.id, accountId = bank.id)
        val s = Snapshot.empty().copy(
            categories = listOf(food), accounts = listOf(bank), people = listOf(rahul),
            entries = listOf(generated, split, linked, fine),
            recurring = listOf(RecurringRule(EntityId.random(), EntryType.EXPENSE, Money(1_000_00), 5, lostCategory, lostAccount, "Rent", false,
                LocalDate(2026, 1, 5), t, false)),
            ledger = listOf(
                LedgerEntry(EntityId.random(), rahul.id, LedgerDirection.LENT, Money(40_00), LocalDate(2026, 11, 26), "", false, t, false, paymentId = lostEntry),
                LedgerEntry(EntityId.random(), lostPerson, LedgerDirection.LENT, Money(40_00), LocalDate(2026, 11, 26), "", false, t, false),
            ),
            budgets = listOf(Budget(EntityId.random(), food.id, Money(5_000_00), t, false), Budget(EntityId.random(), lostCategory, Money(1_00), t, false)),
            merchantRules = listOf(MerchantRule(EntityId.random(), "Swiggy", food.id, t, false), MerchantRule(EntityId.random(), "Zomato", lostCategory, t, false)),
        )
        val out = DamageRepair.detach(s)

        val g = out.entries.single { it.amount == Money(500_00) }
        assertEquals(null, g.generatedBy, "its rule is lost: an ordinary entry")
        assertNotEquals(generated.id, g.id, "with an id of its own (the derived one needs the rule)")
        assertEquals(emptyList(), out.entries.single { it.amount == Money(300_00) }.splits, "a part in a lost category: a plain payment")
        out.entries.single { it.amount == Money(100_00) }.let { assertEquals(Triple(null, null, null), Triple(it.categoryId, it.accountId, it.linkedEntryId)) }
        assertEquals(fine, out.entries.single { it.amount == Money(50_00) }, "untouched")
        out.recurring.single().let { assertEquals(null to null, it.categoryId to it.accountId) }
        assertEquals(listOf(rahul.id), out.ledger.map { it.personId }, "a lost person's ledger entry can't stand alone")
        assertEquals(null, out.ledger.single().paymentId)
        assertEquals(listOf(food.id), out.budgets.map { it.categoryId })
        assertEquals(listOf("Swiggy"), out.merchantRules.map { it.payee })
        // And it is a backup that restores.
        val text = BackupCodec.encode(out, ExportMeta("1", "android", t))
        assertIs<DecodeResult.Success>(BackupCodec.decode(text, t))
    }
}
