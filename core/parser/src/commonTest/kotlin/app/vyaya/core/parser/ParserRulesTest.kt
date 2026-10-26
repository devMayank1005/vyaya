package app.vyaya.core.parser

import app.vyaya.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserRulesTest {

    private val rules = ParserRules.default

    private fun rule(id: String) = rules.rules.single { it.id == id }

    @Test
    fun default_rules_load_with_the_supported_version_and_unique_ids() {
        assertEquals(ParserRules.SUPPORTED_VERSION, rules.version)
        assertTrue(rules.rules.isNotEmpty())
        assertEquals(rules.rules.size, rules.rules.map { it.id }.toSet().size)
    }

    @Test
    fun an_unknown_version_fails_loudly() {
        assertFailsWith<IllegalArgumentException> { ParserRules.load("""{"version": 99, "rules": []}""") }
    }

    @Test
    fun malformed_json_fails_loudly() {
        assertFails { ParserRules.load("""{"version": 1, "rules": [ {"id": "x" """) }
    }

    @Test
    fun an_invalid_regex_fails_at_load_time_not_at_parse_time() {
        val json = """{"version": 1, "rules": [{"id": "bad", "pattern": "(unclosed", "direction": "DEBIT", "amountGroup": 1}]}"""
        assertFails { ParserRules.load(json) }
    }

    @Test
    fun a_rule_can_be_limited_to_a_source_and_a_sender() {
        val json = """{"version": 1, "rules": [{"id": "only-sbi-sms", "pattern": "paid (\\d+)", "direction": "DEBIT",
            "amountGroup": 1, "sources": ["SMS"], "senderPattern": "SBI"}]}"""
        val only = ParserRules.load(json).rules.single()
        assertEquals(Money(1000), only.match("paid 10", Source.SMS, "JD-SBIUPI-S")?.amount)
        assertNull(only.match("paid 10", Source.NOTIFICATION, "JD-SBIUPI-S"))
        assertNull(only.match("paid 10", Source.SMS, "VM-HDFCBK"))
    }

    // --- the default rules, each against the format it was written for -------------------

    @Test
    fun sbi_upi_debit_without_a_currency_prefix() {
        val hit = rule("sbi-upi-debit").match(
            "Dear UPI user A/C X1234 debited by 120.0 on date 26Oct26 trf to SWIGGY Refno 429112345678. If not u? call 1800111109. -SBI",
            Source.SMS, "JD-SBIUPI-S",
        )
        assertEquals(Money(12000), hit?.amount)
        assertEquals("SWIGGY", hit?.counterparty)
    }

    @Test
    fun sbi_upi_credit() {
        val hit = rule("sbi-upi-credit").match(
            "Dear SBI UPI User, ur A/cX1234 credited by Rs500 on 26Oct26 by (Ref no 429112345678)", Source.SMS, "JD-SBIUPI-S",
        )
        assertEquals(Money(50000), hit?.amount)
        assertNull(hit?.counterparty)
    }

    @Test
    fun icici_debit_names_the_payee_as_credited() {
        val hit = rule("icici-debit").match(
            "ICICI Bank Acct XX123 debited for Rs 120.00 on 26-Oct-26; SWIGGY credited. UPI:429112345678. Call 18002662 for dispute.",
            Source.SMS, "AX-ICICIT-S",
        )
        assertEquals(Money(12000), hit?.amount)
        assertEquals("SWIGGY", hit?.counterparty)
    }

    @Test
    fun card_spend_ignores_the_available_limit() {
        val hit = rule("card-spend").match(
            "Spent INR 500 Axis Bank Card no. XX1234 26-10-26 10:00:00 IST SWIGGY Avl Limit: INR 45,000", Source.SMS, "AD-AXISBK-S",
        )
        assertEquals(Money(50000), hit?.amount)
        assertEquals("SWIGGY", hit?.counterparty)
    }

    @Test
    fun bhim_payment_successful() {
        val hit = rule("bhim-payment").match(
            "Payment of ₹120 to swiggy@icici is successful", Source.NOTIFICATION, "in.org.npci.upiapp",
        )
        assertEquals(Money(12000), hit?.amount)
        assertEquals("swiggy@icici", hit?.counterparty)
    }

    @Test
    fun a_failed_bhim_payment_does_not_match() {
        assertNull(rule("bhim-payment").match("Payment of ₹120 to swiggy@icici failed", Source.NOTIFICATION, "in.org.npci.upiapp"))
    }
}
