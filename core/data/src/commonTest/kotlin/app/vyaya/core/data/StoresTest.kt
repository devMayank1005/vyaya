package app.vyaya.core.data

import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Entries on the database, and capture end to end: message → parse → filter → de-dup → database (CLAUDE.md §2). */
class StoresTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val t0 = Instant.parse("2026-10-26T06:00:00Z")
    private fun at(i: Instant) = CapturedAt(i, ist)
    private fun sms(text: String, time: Instant = t0, sender: String = "VM-HDFCBK-S") = RawMessage(Source.SMS, sender, text, at(time))
    private fun notification(pkg: String, text: String, time: Instant) = RawMessage(Source.NOTIFICATION, pkg, text, at(time))

    private val hdfc = sms("Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy On 26/10/26 Ref 429100000001 Not You? Call 18002586161")
    private val phonepe = notification("com.phonepe.app", "₹250 paid to Swiggy", t0 - 2.minutes)
    private val gpay = notification("com.google.android.apps.nbu.paisa.user", "You paid ₹250 to Swiggy", t0 - 2.minutes + 10.seconds)

    @Test
    fun entries_are_saved_and_read_back_exactly() = runTest {
        val db = inMemoryDatabase()
        val store = EntryStore(db)
        val key = Generation(EntityId.random(), YearMonth(2026, 10))
        val rent = Entry(key.entryId, EntryType.EXPENSE, Money(1_500_000), at(t0), null, null, null, null, "Rent", emptyList(),
            EntrySource.RECURRING, null, emptyList(), null, t0, t0, false, key)
        val coffee = Entry(EntityId.random(), EntryType.EXPENSE, Money(15_000), at(t0), null, null, null, "Blue Tokai", "", listOf("office"),
            EntrySource.MANUAL, null, emptyList(), null, t0, t0, false)
        store.save(listOf(rent, coffee))
        assertEquals(setOf(rent, coffee), store.all().toSet())
        assertEquals(rent, store.byId(rent.id))
        val row = db.entries().byId(rent.id.value)!!
        assertEquals(key.ruleId.value to "2026-10", row.ruleId to row.month)

        val edited = coffee.copy(note = "with Priya", updatedAt = t0 + 1.minutes)
        store.save(listOf(edited))
        assertEquals(setOf(rent, edited), store.all().toSet(), "saving again replaces")
        db.close()
    }

    @Test
    fun one_payment_from_sms_and_two_apps_is_one_entry_and_the_import_adds_nothing() = runTest {
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        for (m in listOf(phonepe, gpay, hdfc)) capture.process(m, now = t0 + 1.minutes)
        assertEquals(1, EntryStore(db).all().size)
        // The 30-day import reads the SMS again, days later.
        assertIs<Outcome.Duplicate>(capture.process(hdfc, now = t0 + 3.days).single())
        assertEquals(1, EntryStore(db).all().size)
        db.close()
    }

    @Test
    fun the_same_reference_days_later_is_still_the_same_payment() = runTest { // found by reference, outside the time window
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        capture.process(hdfc, now = t0)
        val late = sms("Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy On 26/10/26 Ref 429100000001 Not You?", time = t0 + 10.days)
        assertIs<Outcome.Duplicate>(capture.process(late, now = t0 + 10.days).single())
        db.close()
    }

    @Test
    fun a_transfer_between_your_accounts_replaces_the_first_side() = runTest {
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        capture.process(sms("Sent Rs.14999.00\nFrom HDFC Bank A/C *1234\nTo Rahul Sharma\nOn 30/10/26\nRef 219300000004\nNot You?"), now = t0)
        capture.process(sms("Airtel Payments Bank a/c is credited with Rs.14999.00. Txn ID: 219300000004. Call 180023400 for help",
            time = t0 + 1.minutes, sender = "AX-AIRBNK-S"), now = t0 + 2.minutes)
        val only = EntryStore(db).all().single()
        assertEquals(EntryType.TRANSFER, only.type)
        db.close()
    }

    @Test
    fun a_card_summary_sms_saves_one_entry_per_payment() = runTest {
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        val summary = sms("Airtel Payments Bank Txns card 1234 \n26-10-26 DELHI METR Rs 77.6\n 26-10-26 DELHI METR Rs 57.6\n Call 180023400",
            sender = "AX-AIRBNK-S")
        capture.process(summary, now = t0)
        capture.process(summary, now = t0 + 1.days)
        assertEquals(listOf(Money(5_760), Money(7_760)), EntryStore(db).all().map { it.amount }.sortedBy { it.paise })
        db.close()
    }

    @Test
    fun re_reading_a_card_summary_with_old_rides_adds_nothing() = runTest { // DATA-002 (branch review)
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        val tenDaysLater = t0 + 10.days // the summary arrives 10 days after the ride on 26 Oct
        val summary = sms("Airtel Payments Bank Txns card 1234 \n26-10-26 DELHI METR Rs 77.6\n Call 180023400", time = tenDaysLater, sender = "AX-AIRBNK-S")
        capture.process(summary, now = tenDaysLater)
        capture.process(summary, now = tenDaysLater + 1.days)
        assertEquals(1, EntryStore(db).all().size)
        db.close()
    }

    @Test
    fun the_sms_and_the_notification_at_the_same_moment_are_one_entry() = kotlinx.coroutines.runBlocking { // DATA-004
        val db = inMemoryDatabase()
        val capture = CaptureStore(db)
        coroutineScope {
            for (i in 0 until 40) {
                val time = t0 + (i * 30).minutes
                val ref = (429100000100L + i).toString()
                val bank = sms("Sent Rs.${100 + i}.00 From HDFC Bank A/C *1234 To Swiggy On 26/10/26 Ref $ref Not You?", time = time)
                val app = notification("com.phonepe.app", "₹${100 + i} paid to Swiggy", time - 1.minutes)
                launch(Dispatchers.Default) { capture.process(bank, now = time) }
                launch(Dispatchers.Default) { capture.process(app, now = time) }
            }
        }
        assertEquals(40, EntryStore(db).all().size)
        db.close()
    }

    @Test
    fun catalog_flaw_004_captured_payments_are_saved_on_the_account_with_those_last_digits() = runTest {
        val db = inMemoryDatabase()
        val account = (CatalogStore(db).addAccount("HDFC", app.vyaya.core.model.AccountKind.BANK, "1234", t0) as CatalogResult.Saved).item
        val capture = CaptureStore(db)
        val added = assertIs<Outcome.Added>(capture.process(phonepe, t0).single()).entry
        assertEquals(null, EntryStore(db).byId(added.id)?.accountId, "the app's notification has no digits")
        assertIs<Outcome.Duplicate>(capture.process(hdfc, t0).single())
        assertEquals(account.id, EntryStore(db).byId(added.id)?.accountId, "the bank's SMS fills it in")
        db.close()
    }
}
