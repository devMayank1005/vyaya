package app.vyaya.core.data

import app.vyaya.core.filters.IgnoreReason
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The Ignored tab (Phase 3; the user's choice): messages with an amount that capture didn't log, kept 30 days as a
 * summary only (sender, amount, reason, time); never the message text.
 */
class IgnoredStoreTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val t0 = Instant.parse("2026-11-16T06:00:00Z")
    private fun sms(sender: String, text: String, at: Instant = t0) = RawMessage(Source.SMS, sender, text, CapturedAt(at, ist))
    private val failed = sms("VM-HDFCBK-S", "Txn of Rs.2,000.00 debited from A/c XX1234 failed. Amount will be reversed")

    @Test
    fun a_skipped_message_with_an_amount_is_kept_as_a_summary() = runTest {
        val db = inMemoryDatabase()
        CaptureStore(db).process(failed, t0)
        val item = IgnoredStore(db).items(t0).single()
        assertEquals(Triple("VM-HDFCBK-S", Money(2_000_00), IgnoreReason.FAILED), Triple(item.sender, item.amount, item.reason))
        assertEquals(t0, item.receivedAt)
        // Nothing of the message's own words is stored, in any column.
        val stored = db.ignored().all().single().toString()
        assertTrue("debited" !in stored && "XX1234" !in stored && "reversed" !in stored, stored)
        db.close()
    }

    @Test
    fun no_amount_nothing_kept_and_the_same_message_once() = runTest {
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        capture.process(sms("VM-HDFCBK-S", "Your OTP for login is 482913. Do not share it."), t0)
        assertEquals(0, IgnoredStore(db).items(t0).size, "no amount: not a payment anyone would look for")
        capture.process(failed, t0)
        capture.process(failed, t0 + 1.minutes) // read again by the SMS import
        assertEquals(1, IgnoredStore(db).items(t0).size)
        db.close()
    }

    @Test
    fun a_phone_number_sender_is_masked() = runTest {
        val db = inMemoryDatabase()
        CaptureStore(db).process(sms("+919876543210", "Rs 5,000 credited to your account. Click to claim"), t0)
        assertEquals("+91••••••3210", IgnoredStore(db).items(t0).single().sender)
        db.close()
    }

    @Test
    fun kept_30_days_and_put_away_when_answered() = runTest {
        val db = inMemoryDatabase()
        val store = IgnoredStore(db)
        CaptureStore(db).process(failed, t0)
        CaptureStore(db).process(sms("VM-HDFCBK-S", "Txn of Rs.300.00 to Zomato declined", t0 + 1.minutes), t0)
        assertEquals(2, store.items(t0).size)
        val (a, b) = store.items(t0)
        store.wasPayment(a.id)
        store.notPayment(b.id)
        assertEquals(0, store.items(t0).size)
        CaptureStore(db).process(sms("VM-HDFCBK-S", "Txn of Rs.700.00 failed", t0 + 2.minutes), t0)
        assertEquals(1, store.items(t0 + 29.days).size)
        assertEquals(0, store.items(t0 + 31.days).size, "after 30 days it's gone")
        assertEquals(0, db.ignored().all().size, "removed from the phone, not only hidden")
        db.close()
    }

    @Test
    fun ignored_flaw_004_whether_money_came_in_or_went_out_is_kept() = runTest {
        val db = inMemoryDatabase()
        CaptureStore(db).process(sms("+919876543210", "Rs 5,000 credited to your account. Click to claim"), t0)
        CaptureStore(db).process(failed, t0)
        val byAmount = IgnoredStore(db).items(t0).associate { it.amount to it.credit }
        assertEquals(mapOf(Money(5_000_00) to true, Money(2_000_00) to false), byAmount)
        db.close()
    }

    @Test
    fun ignored_data_002_an_entry_already_logged_for_it_is_found() = runTest {
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        capture.process(failed, t0)
        val item = IgnoredStore(db).items(t0).single()
        assertEquals(null, IgnoredStore(db).alreadyLogged(item))
        // The bank's retry went through a minute later and was captured.
        capture.process(sms("VM-HDFCBK-S", "Rs.2,000.00 debited from A/c XX1234 to Swiggy. UPI Ref 429100000077", t0 + 1.minutes), t0)
        assertEquals(Money(2_000_00), IgnoredStore(db).alreadyLogged(item)?.amount)
        db.close()
    }

    @Test
    fun review_data_008_already_logged_means_the_same_direction() = runTest {
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        capture.process(failed, t0) // a payment out that failed
        val item = IgnoredStore(db).items(t0).single()
        // Money of the same amount came in a minute later: not the same payment.
        capture.process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "Received ₹2,000 from Rahul", CapturedAt(t0 + 1.minutes, ist)), t0)
        assertEquals(null, IgnoredStore(db).alreadyLogged(item))
        db.close()
    }
}
