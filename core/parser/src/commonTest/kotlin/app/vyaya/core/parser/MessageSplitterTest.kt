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
    fun each_part_is_dated_on_its_own_line_day_at_the_sms_time_of_day() { // DATA-001 (real-samples report)
        val ist = TimeZone.of("Asia/Kolkata")
        val nov1 = CapturedAt(Instant.parse("2026-11-01T03:00:00Z"), ist) // 1 Nov, 08:30 in Kolkata
        val sms = RawMessage(Source.SMS, "AX-AIRBNK-S",
            "Airtel Payments Bank Txns card 1234 \n26-10-31 DELHI METR Rs 77.6\n26-13-40 DELHI METR Rs 10\n26-11-05 DELHI METR Rs 20\n Call 180023400", nov1)
        val parts = MessageSplitter.split(sms)
        // 31 Oct at 08:30 Kolkata: the ride counts in October.
        assertEquals(Instant.parse("2026-10-31T03:00:00Z"), parts[0].receivedAt.instant)
        // An impossible date, or one after the SMS arrived, keeps the arrival time (plus the part's position).
        assertEquals(nov1.instant + 1.milliseconds, parts[1].receivedAt.instant)
        assertEquals(nov1.instant + 2.milliseconds, parts[2].receivedAt.instant)
        assertEquals(parts, MessageSplitter.split(sms), "reading it again gives the same parts")
    }

    @Test
    fun any_other_message_is_left_as_it_is() {
        val single = RawMessage(Source.SMS, "AX-AIRBNK-S", "Rs. 80.00 debited from Airtel Payments Bank a/c Txn ID 630200000001 Bal:732.43", at)
        assertEquals(listOf(single), MessageSplitter.split(single))
    }
}
