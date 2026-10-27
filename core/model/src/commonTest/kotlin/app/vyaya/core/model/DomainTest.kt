package app.vyaya.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DomainTest {

    private val now = Instant.parse("2026-10-27T10:00:00Z")
    private val at = CapturedAt(now, TimeZone.of("Asia/Kolkata"))
    private fun id() = EntityId.random()

    // --- ids (design rule 2) ------------------------------------------------------------

    @Test
    fun random_ids_are_uuids_and_unique() {
        val ids = List(1000) { EntityId.random() }
        assertEquals(1000, ids.toSet().size)
        ids.forEach { assertEquals(it, EntityId.parse(it.value)) }
    }

    @Test
    fun parse_accepts_uuids_and_system_ids_only() {
        assertEquals("sys.saved", EntityId.parse("sys.saved")?.value)
        assertEquals("1b4e28ba-2fa1-11d2-883f-0016d3cca427", EntityId.parse("1B4E28BA-2FA1-11D2-883F-0016D3CCA427")?.value)
        listOf("", "1", "not-a-uuid", "sys.", "sys.Saved", "1b4e28ba2fa111d2883f0016d3cca427", "../../etc").forEach {
            assertNull(EntityId.parse(it), "id: '$it'")
        }
    }

    // --- system categories (design rule 5) ---------------------------------------------------

    @Test
    fun system_categories_have_fixed_ids() {
        assertEquals("sys.saved", SystemCategories.SAVED.id.value)
        assertEquals("sys.transfer", SystemCategories.TRANSFER.id.value)
        assertEquals("sys.lent", SystemCategories.LENT.id.value)
        assertTrue(SystemCategories.all.all { it.kind == CategoryKind.SYSTEM })
    }

    // --- entries ------------------------------------------------------------------------------

    private fun entry(
        type: EntryType = EntryType.EXPENSE,
        amount: Money = Money(12000),
        splits: List<Split> = emptyList(),
        tags: List<String> = emptyList(),
    ) = Entry(
        id = id(), type = type, amount = amount, occurredAt = at, accountId = null, toAccountId = null, categoryId = null,
        payee = "Swiggy", note = "", tags = tags, source = EntrySource.MANUAL, upiRef = null, splits = splits,
        linkedEntryId = null, createdAt = now, updatedAt = now, deleted = false,
    )

    @Test
    fun only_expenses_count_as_spending_and_only_income_as_income() { // design rule 7 inputs
        assertTrue(entry(EntryType.EXPENSE).countsAsSpending)
        assertFalse(entry(EntryType.TRANSFER).countsAsSpending)
        assertFalse(entry(EntryType.SAVED).countsAsSpending)
        assertTrue(entry(EntryType.INCOME).countsAsIncome)
        assertFalse(entry(EntryType.SAVED).countsAsIncome)
    }

    @Test
    fun entry_amounts_must_be_positive() {
        assertFailsWith<IllegalArgumentException> { entry(amount = Money(0)) }
        assertFailsWith<IllegalArgumentException> { entry(amount = Money(-100)) }
    }

    @Test
    fun splits_must_add_up_to_the_amount() {
        val a = id(); val b = id()
        entry(amount = Money(240000), splits = listOf(Split(a, Money(180000)), Split(b, Money(60000))))
        assertFailsWith<IllegalArgumentException> {
            entry(amount = Money(240000), splits = listOf(Split(a, Money(180000)), Split(b, Money(50000))))
        }
        assertFailsWith<IllegalArgumentException> { Split(a, Money(0)) }
    }

    @Test
    fun tags_are_limited() {
        assertFailsWith<IllegalArgumentException> { entry(tags = List(21) { "t$it" }) }
        assertFailsWith<IllegalArgumentException> { entry(tags = listOf("x".repeat(31))) }
        assertFailsWith<IllegalArgumentException> { entry(tags = listOf(" ")) }
    }

    @Test
    fun deleting_is_soft_and_bumps_updated_at() { // design rule 3
        val e = entry()
        val later = Instant.parse("2026-10-28T10:00:00Z")
        val d = e.markDeleted(later)
        assertTrue(d.deleted)
        assertEquals(later, d.updatedAt)
        assertEquals(e.id, d.id)
        assertNotEquals(e, d)
    }

    // --- other records ------------------------------------------------------------------------

    @Test
    fun names_must_be_present_and_reasonable() {
        assertFailsWith<IllegalArgumentException> { Account(id(), " ", AccountKind.BANK, null, now, false) }
        assertFailsWith<IllegalArgumentException> { Person(id(), "x".repeat(101), now, false) }
        assertFailsWith<IllegalArgumentException> { Category(id(), "", CategoryKind.EXPENSE, now, false) }
        assertFailsWith<IllegalArgumentException> { Account(id(), "HDFC", AccountKind.CARD, "12a4", now, false) }
        Account(id(), "HDFC Salary", AccountKind.BANK, "1234", now, false)
    }

    @Test
    fun text_fields_have_length_limits() {
        assertFailsWith<IllegalArgumentException> { entry().copy(payee = "x".repeat(101)) }
        assertFailsWith<IllegalArgumentException> { entry().copy(note = "x".repeat(501)) }
        assertFailsWith<IllegalArgumentException> { entry().copy(upiRef = "1".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { Account(id(), "HDFC", AccountKind.BANK, "12", now, false) }
        assertFailsWith<IllegalArgumentException> { Account(id(), "HDFC", AccountKind.BANK, "1234567", now, false) }
        assertFailsWith<IllegalArgumentException> {
            RecurringRule(id(), EntryType.INCOME, Money(100), 1, null, null, "x".repeat(501), false, LocalDate(2026, 1, 1), null, now, false)
        }
        assertFailsWith<IllegalArgumentException> {
            LedgerEntry(id(), id(), LedgerDirection.LENT, Money(100), LocalDate(2026, 1, 1), "x".repeat(501), false, now, false)
        }
    }

    @Test
    fun merchant_rules_compare_by_value() {
        val a = MerchantRule(id(), "Swiggy", id(), now, false)
        val same = MerchantRule(a.id, " SWIGGY", a.categoryId, now, false)
        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, MerchantRule(a.id, "Swiggy", a.categoryId, now, true))
        assertNotEquals<Any>(a, "swiggy")
        assertTrue(a.toString().contains("payee=swiggy"))
    }

    @Test
    fun recurring_rules_need_a_valid_day_and_amount() {
        fun rule(day: Int, amount: Long) = RecurringRule(
            id(), EntryType.EXPENSE, Money(amount), day, null, null, "Spotify", false, LocalDate(2026, 1, 5), null, now, false,
        )
        rule(5, 11900)
        assertFailsWith<IllegalArgumentException> { rule(0, 11900) }
        assertFailsWith<IllegalArgumentException> { rule(32, 11900) }
        assertFailsWith<IllegalArgumentException> { rule(5, 0) }
        assertFailsWith<IllegalArgumentException> {
            RecurringRule(id(), EntryType.TRANSFER, Money(100), 5, null, null, "", false, LocalDate(2026, 1, 5), null, now, false)
        }
    }

    @Test
    fun ledger_budget_and_merchant_rules_validate() {
        val p = id()
        LedgerEntry(id(), p, LedgerDirection.LENT, Money(30000), LocalDate(2026, 10, 1), "Dinner", false, now, false)
        assertFailsWith<IllegalArgumentException> {
            LedgerEntry(id(), p, LedgerDirection.BORROWED, Money(0), LocalDate(2026, 10, 1), "", false, now, false)
        }
        assertFailsWith<IllegalArgumentException> { Budget(id(), id(), Money(0), now, false) }
        assertEquals("swiggy", MerchantRule(id(), "  SWIGGY ", id(), now, false).payee)
        assertFailsWith<IllegalArgumentException> { MerchantRule(id(), " ", id(), now, false) }
    }

    @Test
    fun settings_default_to_india() {
        val s = Settings.default()
        assertEquals("INR", s.currency)
        assertEquals(TimeZone.of("Asia/Kolkata"), s.homeTimeZone)
        assertEquals(MonthSchedule.of(1), s.monthSchedule)
        assertTrue(s.alertsEnabled)
    }
}
