package app.vyaya.core.dedup

import app.vyaya.core.filters.IgnoreReason
import app.vyaya.core.filters.ReviewReason
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A payment becomes exactly one entry, however many ways it arrives (plan.md invariant 1). */
class CaptureTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val t0 = Instant.parse("2026-10-26T06:00:00Z")
    private val now = Instant.parse("2026-10-26T07:00:00Z")
    private fun at(i: Instant) = CapturedAt(i, ist)

    private fun hdfcSms(rupees: String, ref: String, time: Instant = t0) = RawMessage(Source.SMS, "VM-HDFCBK-S",
        "Sent Rs.$rupees From HDFC Bank A/C *1234 To Swiggy On 26/10/26 Ref $ref Not You? Call 18002586161/SMS BLOCK UPI to 7308080808", at(time))
    private fun phonepe(rupees: String, to: String = "Swiggy", time: Instant = t0) =
        RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹$rupees paid to $to", at(time))
    private fun gpay(rupees: String, time: Instant = t0) =
        RawMessage(Source.NOTIFICATION, "com.google.android.apps.nbu.paisa.user", "You paid ₹$rupees to Swiggy", at(time))

    /** Runs messages through capture in order, keeping what it adds, like the database will. */
    private fun captureAll(messages: List<RawMessage>, start: List<Entry> = emptyList()): Pair<List<Entry>, List<Outcome>> {
        var entries = start
        val outcomes = messages.map { m -> Capture.process(m, entries, now).also { if (it is Outcome.Added) entries = entries + it.entry } }
        return entries to outcomes
    }

    @Test
    fun an_accepted_payment_becomes_an_entry() {
        val added = assertIs<Outcome.Added>(Capture.process(hdfcSms("250.00", "429112345678"), emptyList(), now)).entry
        assertEquals(EntryType.EXPENSE, added.type)
        assertEquals(Money(25_000), added.amount)
        assertEquals(EntrySource.SMS, added.source)
        assertEquals("429112345678", added.upiRef)
        assertEquals("Swiggy", added.payee)
        assertEquals(at(t0), added.occurredAt)
        assertEquals(now, added.createdAt)
        assertEquals(EntrySource.NOTIFICATION, assertIs<Outcome.Added>(Capture.process(phonepe("250"), emptyList(), now)).entry.source)
    }

    @Test
    fun the_same_payment_from_sms_phonepe_and_gpay_is_one_entry() { // the heavy UPI user's triple
        // Apps notify within seconds of each other; the bank's SMS can take minutes.
        val (entries, outcomes) = captureAll(listOf(phonepe("250", time = t0), hdfcSms("250.00", "429112345678", t0 + 4.minutes), gpay("250", t0 + 1.minutes)))
        assertEquals(1, entries.size)
        assertEquals(listOf(Outcome.Added::class, Outcome.Duplicate::class, Outcome.Duplicate::class), outcomes.map { it::class })
    }

    @Test
    fun a_delayed_bank_sms_matches_by_upi_reference() {
        val (entries, _) = captureAll(listOf(hdfcSms("250.00", "429112345678", t0), hdfcSms("250.00", "429112345678", t0 + 5.hours)))
        assertEquals(1, entries.size)
    }

    @Test
    fun the_same_sms_read_again_by_the_import_is_not_added_twice() {
        val sms = hdfcSms("250.00", "429112345678")
        val (entries, outcomes) = captureAll(listOf(sms, sms))
        assertEquals(1, entries.size)
        assertIs<Outcome.Duplicate>(outcomes[1])
    }

    @Test
    fun two_real_payments_of_the_same_amount_stay_two() {
        // Two teas a minute apart, each with its own UPI reference.
        assertEquals(2, captureAll(listOf(hdfcSms("20.00", "429100000001", t0), hdfcSms("20.00", "429100000002", t0 + 1.minutes))).first.size)
        // The same amount to the same shop 5 minutes apart, from the app only.
        assertEquals(2, captureAll(listOf(phonepe("20", time = t0), phonepe("20", time = t0 + 5.minutes))).first.size)
        // The same amount, one paid and one received.
        val received = RawMessage(Source.NOTIFICATION, "com.phonepe.app", "Received ₹250 from Swiggy", at(t0 + 1.minutes))
        assertEquals(2, captureAll(listOf(phonepe("250"), received)).first.size)
    }

    @Test
    fun a_card_summary_sms_gives_one_entry_per_payment_once() { // the user's Airtel sample, masked
        val summary = RawMessage(Source.SMS, "AX-AIRBNK-S",
            "Airtel Payments Bank Txns card 1234 \n26-10-27 DELHI METR Rs 77.6\n 26-10-27 DELHI METR Rs 57.6\n Call 180023400", at(t0))
        val first = Capture.processAll(summary, emptyList(), now)
        val added = first.map { assertIs<Outcome.Added>(it).entry }
        assertEquals(listOf(Money(7_760), Money(5_760)), added.map { it.amount })
        // The SMS import reads it again: nothing new.
        assertEquals(2, Capture.processAll(summary, added, now).count { it is Outcome.Duplicate })
    }

    @Test
    fun two_apps_naming_the_payee_differently_are_still_one_payment() { // SIM-001 (filters-dedup report)
        val bhim = RawMessage(Source.NOTIFICATION, "in.org.npci.upiapp", "Payment of ₹250 to rahulsharma@okaxis is successful", at(t0))
        val (entries, _) = captureAll(listOf(bhim, phonepe("250", to = "Rahul Sharma", time = t0 + 20.seconds)))
        assertEquals(1, entries.size, entries.toString())
        // Different people with the same amount are still two payments.
        assertEquals(2, captureAll(listOf(bhim, phonepe("250", to = "Priya", time = t0 + 20.seconds))).first.size)
    }

    private fun manual(payee: String?, time: Instant) = Entry(EntityId.random(), EntryType.EXPENSE, Money(2_000), at(time), null, null, null,
        payee, "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, time, time, false)

    @Test
    fun a_typed_in_entry_is_a_possible_duplicate_only_for_the_same_payee_or_when_close_in_time() { // FLAW-005
        val swiggy = phonepe("20") // ₹20 to Swiggy at t0
        assertIs<Outcome.Added>(Capture.process(swiggy, listOf(manual("Chai", t0 - 3.hours)), now), "a different payee")
        assertIs<Outcome.Review>(Capture.process(swiggy, listOf(manual("swiggy", t0 - 3.hours)), now), "the same payee")
        assertIs<Outcome.Added>(Capture.process(swiggy, listOf(manual(null, t0 - 5.hours)), now), "no payee, hours apart")
        assertIs<Outcome.Review>(Capture.process(swiggy, listOf(manual(null, t0 - 30.minutes)), now), "no payee, close in time")
    }

    @Test
    fun a_matching_manual_or_recurring_entry_sends_it_to_review() {
        val manual = Entry(EntityId.random(), EntryType.EXPENSE, Money(25_000), at(t0 - 20.minutes), null, null, null, "Swiggy", "", emptyList(),
            EntrySource.MANUAL, null, emptyList(), null, t0, t0, false)
        val review = assertIs<Outcome.Review>(Capture.process(hdfcSms("250.00", "429112345678"), listOf(manual), now))
        assertEquals(manual.id, review.possibleDuplicateOf)

        val key = Generation(EntityId.random(), YearMonth(2026, 10))
        val rent = Entry(key.entryId, EntryType.EXPENSE, Money(1_500_000), at(Instant.parse("2026-10-24T06:30:00Z")), null, null, null, null, "Rent",
            emptyList(), EntrySource.RECURRING, null, emptyList(), null, t0, t0, false, key)
        val rentSms = hdfcSms("15000.00", "429199999999")
        assertEquals(rent.id, assertIs<Outcome.Review>(Capture.process(rentSms, listOf(rent), now)).possibleDuplicateOf)
    }

    @Test
    fun ignored_and_review_verdicts_pass_through() {
        assertEquals(Outcome.Ignored(IgnoreReason.PERSONAL_NUMBER),
            Capture.process(RawMessage(Source.SMS, "+919812345678", "Dear customer Rs.25,000 credited to your account.", at(t0)), emptyList(), now))
        val lookalike = hdfcSms("250.00", "429112345678").copy(sender = "VM-HDFCBNK-S")
        assertEquals(ReviewReason.UNKNOWN_HEADER, assertIs<Outcome.Review>(Capture.process(lookalike, emptyList(), now)).reason)
    }
}
