package app.vyaya.core.filters

import app.vyaya.core.fixtures.Channel
import app.vyaya.core.fixtures.NoiseCategory
import app.vyaya.core.fixtures.Synthetic
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.parser.MessageParser
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** Which parsed messages become entries (plan.md Part B1; parser report SEC-001, FLAW-004, SEC-006, SEC-007). */
class FiltersTest {

    private val at = CapturedAt(Instant.parse("2026-10-26T06:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private fun raw(channel: Channel, sender: String, text: String) =
        RawMessage(if (channel == Channel.SMS) Source.SMS else Source.NOTIFICATION, sender, text, at)
    private fun judge(m: RawMessage) = Filters.judge(m, MessageParser.parse(m))
    private fun sms(sender: String, text: String) = judge(raw(Channel.SMS, sender, text))
    private fun notification(pkg: String, text: String) = judge(raw(Channel.NOTIFICATION, pkg, text))

    @Test
    fun real_bank_sms_and_upi_app_payments_become_entries() = runTest {
        checkAll(100, Arb.long()) { seed ->
            val fields = Synthetic.fields(Random(seed))
            for (t in Synthetic.transactions) {
                val verdict = judge(raw(t.channel, t.sender, t.text(fields)))
                assertIs<Verdict.Accept>(verdict, "${t.id} seed=$seed: $verdict")
            }
        }
    }

    @Test
    fun noise_never_becomes_an_entry_and_says_why() = runTest {
        val expected = mapOf(
            NoiseCategory.OTP to IgnoreReason.OTP,
            NoiseCategory.PROMO to IgnoreReason.PROMOTIONAL,
            NoiseCategory.FAILED to IgnoreReason.FAILED,
            NoiseCategory.REQUEST to IgnoreReason.REQUEST,
            NoiseCategory.AUTOPAY_REMINDER to IgnoreReason.NOT_YET_HAPPENED,
            NoiseCategory.FAKE_CREDIT to IgnoreReason.PERSONAL_NUMBER,
        )
        checkAll(100, Arb.long()) { seed ->
            val fields = Synthetic.fields(Random(seed))
            for (n in Synthetic.noise) {
                val verdict = judge(raw(n.channel, n.sender, n.text(fields)))
                assertEquals(Verdict.Ignore(expected.getValue(n.category)), verdict, "${n.id} seed=$seed")
            }
        }
    }

    @Test
    fun spoofed_senders_cannot_create_money() { // SEC-001
        assertEquals(Verdict.Ignore(IgnoreReason.UNTRUSTED_APP), notification("com.evil.lookalike", "₹50,000 received from Rahul Sharma"))
        assertEquals(Verdict.Ignore(IgnoreReason.PERSONAL_NUMBER),
            sms("+919812345678", "Dear UPI user A/C X1234 debited by 5000.0 on date 26Oct26 trf to Rahul Refno 429112345678. If not u? call 1800111109. -SBI"))
        assertEquals(Verdict.Ignore(IgnoreReason.UNKNOWN_SENDER), sms("Bank Alert", "Rs.500.00 debited from A/c XX1234"))
    }

    @Test
    fun a_lookalike_or_unknown_bank_header_waits_for_review() { // SEC-007: never automatic
        val v = sms("VM-HDFCBNK-S", "Sent Rs.500.00 From HDFC Bank A/C *1234 To Rahul On 26/10/26 Ref 429112345678 Not You? Call 18002586161")
        assertEquals(ReviewReason.UNKNOWN_HEADER, assertIs<Verdict.Review>(v).reason)
    }

    @Test
    fun failed_reversed_and_not_yet_happened_money_is_ignored() { // FLAW-004
        assertEquals(Verdict.Ignore(IgnoreReason.FAILED), sms("VM-HDFCBK-S", "Txn of Rs.500.00 debited from A/c XX1234 failed. Amount will be reversed"))
        assertEquals(Verdict.Ignore(IgnoreReason.FAILED), notification("com.phonepe.app", "Payment of ₹500 to Rahul declined by bank"))
        assertEquals(Verdict.Ignore(IgnoreReason.NOT_YET_HAPPENED),
            sms("VM-HDFCBK-S", "Refund of Rs.500 initiated. It will be credited to your A/c XX1234 in 5-7 days"))
        assertEquals(Verdict.Ignore(IgnoreReason.NOT_YET_HAPPENED),
            sms("VM-HDFCBK-S", "Reminder: Rs.1,499.00 for your credit card is due on 05-11-26"))
    }

    @Test
    fun promotional_headers_are_ignored_even_from_a_known_sender() { // SEC-006
        assertEquals(Verdict.Ignore(IgnoreReason.PROMOTIONAL), sms("VM-HDFCBK-P", "Rs.500.00 credited to your HDFC Bank A/c XX1234 as cashback"))
    }

    @Test
    fun hidden_characters_cannot_hide_a_failure() { // the same normalisation the parser uses
        assertEquals(Verdict.Ignore(IgnoreReason.FAILED), notification("com.phonepe.app", "Payment of ₹500 to Rahul fa​iled"))
    }
}
