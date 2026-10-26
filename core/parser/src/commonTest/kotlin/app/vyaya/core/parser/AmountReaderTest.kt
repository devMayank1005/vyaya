package app.vyaya.core.parser

import app.vyaya.core.model.Money
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountReaderTest {

    private val rupees120 = Money(12000)

    @Test
    fun slash_dash_suffix_is_accepted() { // DATA-001
        assertEquals(Money(50000), AmountReader.read("Rs.500/-"))
        assertEquals(Money(50000), AmountReader.read("Rs 500 /-"))
        assertEquals(Money(12_000_000), AmountReader.read("Rs.1,20,000/-"))
    }

    @Test
    fun unicode_space_after_rupee_sign_is_accepted() { // DATA-002
        assertEquals(rupees120, AmountReader.read("₹ 120"))
        assertEquals(rupees120, AmountReader.read("₹ 120"))
    }

    @Test
    fun other_prefix_forms_are_accepted() { // DATA-005
        assertEquals(rupees120, AmountReader.read("INR. 120"))
        assertEquals(rupees120, AmountReader.read("Rs:120"))
        assertEquals(rupees120, AmountReader.read("Rs : 120"))
        assertEquals(rupees120, AmountReader.read("₨120"))
        assertEquals(rupees120, AmountReader.read("₨ 120"))
    }

    @Test
    fun trailing_sentence_punctuation_is_ignored() { // DATA-006
        assertEquals(rupees120, AmountReader.read("Rs.120."))
        assertEquals(Money(12050), AmountReader.read("Rs.120.50."))
        assertEquals(rupees120, AmountReader.read("Rs.120,"))
        assertEquals(Money(50000), AmountReader.read("Rs.500/-."))
    }

    @Test
    fun non_ascii_digits_are_accepted() { // DATA-011
        assertEquals(rupees120, AmountReader.read("₹१२०"))
        assertEquals(rupees120, AmountReader.read("１２０"))
    }

    @Test
    fun zero_amounts_are_dropped() { // FLAW-012
        assertNull(AmountReader.read("₹0"))
        assertNull(AmountReader.read("Rs.0.00"))
        assertNull(AmountReader.read("INR 0/-"))
    }

    @Test
    fun formats_that_already_worked_still_work() {
        listOf("Rs.120", "Rs 120.00", "INR 120", "₹120", "₹ 120", "120").forEach {
            assertEquals(rupees120, AmountReader.read(it), "input: '$it'")
        }
    }

    @Test
    fun garbage_is_still_rejected() {
        listOf("", "Rs.", "/-", "abc", "12.345", "-120", "1,,200", "Rs.120/-/-", "120 INR").forEach {
            assertNull(AmountReader.read(it), "input: '$it'")
        }
    }

    @Test
    fun every_format_from_the_simulation_is_read_correctly() = runTest {
        // The 10 formats from the core-model report simulation, which lost 50 % of samples before.
        val styles = listOf<(Long) -> String>(
            { "Rs.$it" }, { "Rs $it.00" }, { "INR $it" }, { Money(it * 100).format() },
            { "Rs.$it/-" }, { "₹ $it" }, { "INR. $it" }, { "Rs.$it." }, { "₹ $it" }, { "Rs:$it" },
        )
        checkAll(Arb.long(1L..2_00_000L), Arb.element(styles)) { amount, style ->
            val text = style(amount)
            assertEquals(Money(amount * 100), AmountReader.read(text), "text='$text'")
        }
    }
}
