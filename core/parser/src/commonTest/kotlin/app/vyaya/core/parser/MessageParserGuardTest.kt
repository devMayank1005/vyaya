package app.vyaya.core.parser

import app.vyaya.core.model.CapturedAt
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MessageParserGuardTest {

    private val at = CapturedAt(Instant.parse("2026-10-26T10:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private fun sms(text: String) = RawMessage(Source.SMS, "VM-HDFCBK", text, at)

    @Test
    fun empty_or_blank_text_is_not_a_transaction() {
        assertEquals(ParseResult.NotTransaction(NotTransactionReason.EMPTY), MessageParser.parse(sms("")))
        assertEquals(ParseResult.NotTransaction(NotTransactionReason.EMPTY), MessageParser.parse(sms("  \n")))
    }

    @Test
    fun text_over_the_length_cap_is_rejected_before_any_parsing() { // PEN-010
        val huge = "Rs.120 debited " + "x".repeat(MessageParser.MAX_TEXT_LENGTH)
        assertEquals(ParseResult.NotTransaction(NotTransactionReason.TOO_LONG), MessageParser.parse(sms(huge)))
    }

    @Test
    fun text_at_the_length_cap_is_still_parsed() {
        val atCap = "x".repeat(MessageParser.MAX_TEXT_LENGTH)
        assertEquals(ParseResult.Unrecognized, MessageParser.parse(sms(atCap)))
    }
}
