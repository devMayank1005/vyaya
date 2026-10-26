package app.vyaya.core.parser

import app.vyaya.core.model.CapturedAt
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/** Integration: RawMessage → normaliser → rules / generic extractors → ParseResult, over the golden corpus. */
class MessageParserTest {

    private val at = CapturedAt(Instant.parse("2026-10-26T10:00:00Z"), TimeZone.of("Asia/Kolkata"))

    @Test
    fun every_golden_case_parses_exactly_as_expected() {
        val failures = GOLDEN.mapNotNull { case ->
            val result = MessageParser.parse(RawMessage(case.source, case.sender, case.text, at))
            val problem = check(case.expected, result)
            problem?.let { "${case.name}: $it\n    text: ${case.text}\n    got:  $result" }
        }
        if (failures.isNotEmpty()) fail("${failures.size} golden case(s) failed:\n" + failures.joinToString("\n"))
    }

    private fun check(expected: Expect, result: ParseResult): String? = when (expected) {
        Expect.NoTxn -> if (result is ParseResult.Transaction) "expected no transaction" else null
        is Expect.Txn -> {
            val txn = (result as? ParseResult.Transaction)?.txn
            when {
                txn == null -> "expected a transaction"
                txn.amount != expected.amount -> "amount ${txn.amount} != ${expected.amount}"
                txn.direction != expected.direction -> "direction ${txn.direction} != ${expected.direction}"
                txn.counterparty != expected.counterparty -> "counterparty '${txn.counterparty}' != '${expected.counterparty}'"
                txn.upiRef != expected.upiRef -> "upiRef ${txn.upiRef} != ${expected.upiRef}"
                txn.accountMask != expected.accountMask -> "accountMask ${txn.accountMask} != ${expected.accountMask}"
                txn.ruleId != expected.ruleId -> "ruleId ${txn.ruleId} != ${expected.ruleId}"
                else -> null
            }
        }
    }

    @Test
    fun the_transaction_keeps_the_capture_time_and_zone_and_the_sender_kind() {
        val result = MessageParser.parse(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹120 paid to Swiggy", at))
        val txn = (result as ParseResult.Transaction).txn
        assertEquals(at, txn.occurredAt)
        assertEquals(SenderKind.App(UpiApp.PHONEPE), txn.sender)
    }

    @Test
    fun a_rule_limited_to_sms_is_not_used_for_notifications() {
        // SBI's no-prefix format must not turn "debited by 120" in a notification into a rule hit.
        val result = MessageParser.parse(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "debited by 120 on date trf to X Refno 1", at))
        assertTrue(result !is ParseResult.Transaction || result.txn.ruleId != "sbi-upi-debit")
    }

    @Test
    fun the_corpus_covers_every_default_rule() {
        val used = GOLDEN.mapNotNull { (it.expected as? Expect.Txn)?.ruleId }.toSet()
        ParserRules.default.rules.forEach { assertTrue(it.id in used, "rule ${it.id} has no golden case") }
    }
}
