package app.vyaya.core.parser

import app.vyaya.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtractorsTest {

    // --- direction ------------------------------------------------------------

    @Test
    fun debit_keywords() {
        listOf("Rs.120 debited from A/c", "Paid ₹120 to Swiggy", "Sent Rs.120 From HDFC", "Spent INR 120 on card",
            "Rs.2000 withdrawn at ATM").forEach { assertEquals(Direction.DEBIT, Extractors.direction(it)?.direction, it) }
    }

    @Test
    fun credit_keywords() {
        listOf("Rs.500 credited to A/c", "Received ₹500 from Rahul", "Rs 500 deposited in your account",
            "Refunded Rs.120 to your account").forEach { assertEquals(Direction.CREDIT, Extractors.direction(it)?.direction, it) }
    }

    @Test
    fun paid_you_and_sent_you_are_credits() {
        assertEquals(Direction.CREDIT, Extractors.direction("Rahul S paid you ₹500")?.direction)
        assertEquals(Direction.CREDIT, Extractors.direction("Priya sent you ₹200")?.direction)
    }

    @Test
    fun the_first_keyword_decides_when_both_appear() {
        // ICICI style: the user's account is debited; the payee is "credited".
        assertEquals(Direction.DEBIT, Extractors.direction("Acct XX123 debited for Rs 120.00; SWIGGY credited.")?.direction)
    }

    @Test
    fun keywords_must_be_whole_words() {
        assertNull(Extractors.direction("Get Rs.100 cashback on paying via UPI"))
        assertNull(Extractors.direction("123456 is your OTP for txn of Rs.500"))
        assertNull(Extractors.direction("unpaid bill of Rs.500"))
    }

    // --- amount ---------------------------------------------------------------

    @Test
    fun amount_with_any_rupee_prefix() {
        assertEquals(Money(12000), Extractors.amount("Paid ₹120 to Swiggy"))
        assertEquals(Money(12050), Extractors.amount("Rs.120.50 debited"))
        assertEquals(Money(50000), Extractors.amount("credited with INR 500.00 on"))
        assertEquals(Money(50000), Extractors.amount("Rs.500/- debited"))
        assertEquals(Money(12_000_000), Extractors.amount("credited by Rs 1,20,000 on"))
    }

    @Test
    fun balance_and_limit_amounts_are_skipped() {
        assertEquals(Money(12000), Extractors.amount("Avl bal Rs.10,000. Rs.120 debited"))
        assertEquals(Money(12000), Extractors.amount("Rs.120 debited. Avl Bal: Rs.10,000"))
        assertEquals(Money(50000), Extractors.amount("Spent INR 500 on card. Avl Limit: INR 45,000"))
        assertEquals(Money(50000), Extractors.amount("Rs.500 debited. Available balance Rs.9,500"))
    }

    @Test
    fun the_amount_nearest_the_direction_keyword_wins() {
        val text = "Cashback Rs.10 on hold. Rs.120 debited from A/c XX1234"
        val keyword = Extractors.direction(text)!!
        assertEquals(Money(12000), Extractors.amount(text, near = keyword.index))
    }

    @Test
    fun no_amount() {
        assertNull(Extractors.amount("Your account has been debited"))
        assertNull(Extractors.amount("Avl bal Rs.10,000"))
    }

    // --- UPI reference ----------------------------------------------------------

    @Test
    fun upi_reference_in_common_forms() {
        listOf("UPI Ref 429112345678", "UPI Ref No 429112345678", "Ref No. 429112345678", "Refno 429112345678",
            "RRN:429112345678", "(UPI 429112345678)", "UPI:429112345678", "Ref 429112345678").forEach {
            assertEquals("429112345678", Extractors.upiRef("Paid. $it."), it)
        }
    }

    @Test
    fun upi_reference_must_be_12_digits() {
        assertNull(Extractors.upiRef("Ref 12345"))
        assertNull(Extractors.upiRef("Ref 1234567890123"))
        assertNull(Extractors.upiRef("Call 18002586161"))
    }

    // --- account mask -----------------------------------------------------------

    @Test
    fun account_and_card_masks() {
        mapOf(
            "From HDFC Bank A/C *1234 To" to "1234", "to A/c XX1234 on" to "1234", "ur A/cX1234 credited" to "1234",
            "Acct XX123 debited" to "123", "Card no. XX9876 at" to "9876", "a/c XXXXX5678." to "5678",
            "Account XX4321 credited" to "4321",
        ).forEach { (text, mask) -> assertEquals(mask, Extractors.accountMask(text), text) }
    }

    @Test
    fun unmasked_numbers_are_not_account_masks() {
        assertNull(Extractors.accountMask("Call 18002586161 for help"))
        assertNull(Extractors.accountMask("account number 123456789012"))
    }

    // --- counterparty -----------------------------------------------------------

    @Test
    fun payee_of_a_debit() {
        assertEquals("Swiggy", Extractors.counterparty("Paid ₹120 to Swiggy", Direction.DEBIT))
        assertEquals("SWIGGY", Extractors.counterparty("Sent Rs.120 From HDFC Bank A/C *1234 To SWIGGY On 26/10/26 Ref 1", Direction.DEBIT))
        assertEquals("swiggy@icici", Extractors.counterparty("debited from a/c XX1234 to swiggy@icici on 26-10-26.", Direction.DEBIT))
        assertEquals("ATM", Extractors.counterparty("Rs.2000 withdrawn at ATM from A/c XX1234", Direction.DEBIT))
        assertEquals("Big Bazaar", Extractors.counterparty("Paid ₹450 to Big Bazaar using UPI", Direction.DEBIT))
    }

    @Test
    fun payer_of_a_credit() {
        assertEquals("Rahul S", Extractors.counterparty("Received ₹500 from Rahul S", Direction.CREDIT))
        assertEquals("rahul@okaxis", Extractors.counterparty("credited to A/c XX1234 from VPA rahul@okaxis (UPI 1)", Direction.CREDIT))
        assertEquals("NEFT-ACME CORP SALARY", Extractors.counterparty("credited to A/c XX1234 by NEFT-ACME CORP SALARY. Avl Bal", Direction.CREDIT))
        assertEquals("Rahul S", Extractors.counterparty("Rahul S paid you ₹500", Direction.CREDIT))
    }

    @Test
    fun no_counterparty() {
        assertNull(Extractors.counterparty("Rs.120 debited", Direction.DEBIT))
    }
}
