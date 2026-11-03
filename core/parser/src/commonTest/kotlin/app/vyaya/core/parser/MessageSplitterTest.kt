package app.vyaya.core.parser

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Money
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** One SMS that lists several card payments (the user's Airtel Payments Bank samples, masked). */
class MessageSplitterTest {

    private val at = CapturedAt(Instant.parse("2026-10-27T15:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private val summary = RawMessage(Source.SMS, "AX-AIRBNK-S",
        "Airtel Payments Bank Txns card 1234 \n26-10-27 DELHI METR Rs 77.6\n 26-10-27 DELHI METR Rs 57.6\n Call 180023400", at)

    @Test
    fun each_payment_in_a_card_summary_becomes_its_own_message() {
        val parts = MessageSplitter.split(summary)
        assertEquals(2, parts.size)
        val txns = parts.map { assertIs<ParseResult.Transaction>(MessageParser.parse(it)).txn }
        assertEquals(listOf(Money(7_760), Money(5_760)), txns.map { it.amount })
        assertEquals(listOf(Direction.DEBIT, Direction.DEBIT), txns.map { it.direction })
        assertEquals(listOf("DELHI METR", "DELHI METR"), txns.map { it.counterparty })
        assertEquals(listOf("1234", "1234"), txns.map { it.accountMask })
        // Distinct but repeatable times: the same SMS read again gives the same messages.
        assertEquals(listOf(at.instant, at.instant + 1.milliseconds), parts.map { it.receivedAt.instant })
        assertEquals(parts, MessageSplitter.split(summary))
    }

    @Test
    fun any_other_message_is_left_as_it_is() {
        val single = RawMessage(Source.SMS, "AX-AIRBNK-S", "Rs. 80.00 debited from Airtel Payments Bank a/c Txn ID 630200000001 Bal:732.43", at)
        assertEquals(listOf(single), MessageSplitter.split(single))
    }
}
