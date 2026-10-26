package app.vyaya.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MoneyTest {

    // --- construction -------------------------------------------------------

    @Test
    fun ofRupees_combines_rupees_and_paise() {
        assertEquals(12050L, Money.ofRupees(120, 50).paise)
        assertEquals(12000L, Money.ofRupees(120).paise)
    }

    @Test
    fun ofRupees_rejects_paise_outside_0_to_99() {
        assertFailsWith<IllegalArgumentException> { Money.ofRupees(1, 100) }
        assertFailsWith<IllegalArgumentException> { Money.ofRupees(1, -1) }
    }

    // --- arithmetic ---------------------------------------------------------

    @Test
    fun plus_and_minus_work_in_paise() {
        assertEquals(Money(150), Money(100) + Money(50))
        assertEquals(Money(-50), Money(50) - Money(100))
    }

    @Test
    fun arithmetic_overflow_throws_instead_of_wrapping() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE) - Money(1) }
    }

    // --- parsing (text from SMS, notifications, OCR, keypad) ----------------

    @Test
    fun parse_accepts_common_indian_currency_prefixes() {
        val expected = Money(12000)
        listOf("₹120", "₹ 120", "Rs120", "Rs 120", "Rs.120", "Rs. 120", "rs.120", "RS 120", "INR 120", "INR120", "120")
            .forEach { assertEquals(expected, Money.parse(it), "input: '$it'") }
    }

    @Test
    fun parse_handles_decimals() {
        assertEquals(Money(12050), Money.parse("Rs.120.50"))
        assertEquals(Money(12050), Money.parse("120.5"))
        assertEquals(Money(12000), Money.parse("120.00"))
        assertEquals(Money(5), Money.parse("0.05"))
    }

    @Test
    fun parse_handles_indian_and_western_digit_grouping() {
        assertEquals(Money(12_000_050), Money.parse("₹1,20,000.50"))
        assertEquals(Money(12_000_000), Money.parse("120,000"))
        assertEquals(Money(1_000_000_000), Money.parse("INR 1,00,00,000"))
    }

    @Test
    fun parse_trims_surrounding_whitespace() {
        assertEquals(Money(12000), Money.parse("  ₹120  "))
    }

    @Test
    fun parse_rejects_invalid_text() {
        listOf("", " ", "₹", "Rs.", "abc", "12a", "1.2.3", "12.345", "-120", "₹-120", ",120", "120,", "1,,200", ".", "1e5")
            .forEach { assertNull(Money.parse(it), "input: '$it'") }
    }

    @Test
    fun parse_rejects_amounts_above_the_sanity_limit() {
        // ₹100 crore is far beyond any personal UPI/bank alert; anything bigger is a parse error or a scam.
        assertEquals(Money.MAX, Money.parse("1,00,00,00,000"))
        assertNull(Money.parse("1,00,00,00,000.01"))
        assertNull(Money.parse("99999999999999999999999"))
    }

    // --- formatting (Indian lakh/crore grouping) ----------------------------

    @Test
    fun format_uses_indian_grouping_and_hides_zero_paise() {
        assertEquals("₹0", Money(0).format())
        assertEquals("₹120", Money(12000).format())
        assertEquals("₹120.50", Money(12050).format())
        assertEquals("₹0.05", Money(5).format())
        assertEquals("₹1,000", Money(100_000).format())
        assertEquals("₹1,20,000.50", Money(12_000_050).format())
        assertEquals("₹1,00,00,000", Money(1_000_000_000).format())
    }

    @Test
    fun format_puts_the_minus_sign_before_the_rupee_symbol() {
        assertEquals("-₹500", Money(-50_000).format())
    }

    // --- properties ---------------------------------------------------------

    @Test
    fun format_then_parse_round_trips_for_every_valid_amount() = runTest {
        checkAll(Arb.long(0L..Money.MAX.paise)) { paise ->
            val money = Money(paise)
            assertEquals(money, Money.parse(money.format()), "paise=$paise formatted=${money.format()}")
        }
    }
}
