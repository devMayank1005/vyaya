package app.vyaya.core.data

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntryType
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The Detected inbox: what capture added by itself (to edit or undo) and what waits for review. */
class DetectedInboxTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val t0 = Instant.parse("2026-10-26T06:00:00Z")
    private fun sms(sender: String, text: String, time: Instant = t0) = RawMessage(Source.SMS, sender, text, CapturedAt(time, ist))
    private val swiggy = sms("VM-HDFCBK-S", "Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy On 26/10/26 Ref 429100000001 Not You?")
    private val unknownBank = sms("VM-NEWBNK-S", "Rs.500.00 debited from A/c XX9999 to Zomato on 26-10-26. UPI Ref 429100000002")

    @Test
    fun auto_added_payments_are_listed_and_can_be_undone() = runTest {
        val db = inMemoryDatabase()
        val inbox = DetectedInbox(db)
        CaptureStore(db, inbox).process(swiggy, now = t0)
        val item = inbox.items().single()
        assertEquals(DetectedKind.ADDED, item.kind)
        assertEquals(250_00, item.entry.amount.paise)
        // Undo: the entry is soft-deleted (so the import won't bring it back) and leaves the inbox.
        inbox.undo(item.id, now = t0 + 1.minutes)
        assertTrue(EntryStore(db).byId(item.entry.id)!!.deleted)
        assertEquals(emptyList(), inbox.items())
        CaptureStore(db, inbox).process(swiggy, now = t0 + 2.minutes)
        assertEquals(emptyList(), inbox.items(), "re-reading the SMS doesn't bring it back")
        db.close()
    }

    @Test
    fun a_review_item_is_saved_only_when_confirmed() = runTest {
        val db = inMemoryDatabase()
        val inbox = DetectedInbox(db)
        val capture = CaptureStore(db, inbox)
        capture.process(unknownBank, now = t0)
        capture.process(unknownBank, now = t0 + 1.minutes) // read again: still one item
        val item = inbox.items().single()
        assertEquals(DetectedKind.REVIEW, item.kind)
        assertNull(EntryStore(db).byId(item.entry.id), "not an entry until confirmed")
        inbox.confirm(item.id, now = t0 + 2.minutes)
        assertEquals(EntryType.EXPENSE, EntryStore(db).byId(item.entry.id)!!.type)
        assertEquals(emptyList(), inbox.items())
        db.close()
    }

    @Test
    fun a_review_item_can_be_dismissed() = runTest {
        val db = inMemoryDatabase()
        val inbox = DetectedInbox(db)
        CaptureStore(db, inbox).process(unknownBank, now = t0)
        val item = inbox.items().single()
        inbox.dismiss(item.id)
        assertEquals(emptyList(), inbox.items())
        assertNull(EntryStore(db).byId(item.entry.id))
        CaptureStore(db, inbox).process(unknownBank, now = t0 + 1.minutes)
        assertEquals(emptyList(), inbox.items(), "a dismissed message stays dismissed")
        db.close()
    }

    @Test
    fun actions_on_the_wrong_item_do_nothing() = runTest {
        val db = inMemoryDatabase()
        val inbox = DetectedInbox(db)
        val capture = CaptureStore(db, inbox)
        capture.process(swiggy, now = t0)
        capture.process(unknownBank, now = t0)
        val added = inbox.items().single { it.kind == DetectedKind.ADDED }
        val review = inbox.items().single { it.kind == DetectedKind.REVIEW }
        inbox.confirm(added.id, now = t0); inbox.dismiss(added.id) // only review items are confirmed or dismissed
        inbox.undo(review.id, now = t0) // only added items are undone
        inbox.undo("no-such-item", now = t0); inbox.confirm("no-such-item", now = t0); inbox.dismiss("no-such-item")
        assertEquals(2, inbox.items().size)
        assertTrue(!EntryStore(db).byId(added.entry.id)!!.deleted)
        assertNull(EntryStore(db).byId(review.entry.id))
        db.close()
    }

    @Test
    fun duplicates_transfers_and_ignored_messages_are_not_inbox_items() = runTest {
        val db = inMemoryDatabase()
        val inbox = DetectedInbox(db)
        val capture = CaptureStore(db, inbox)
        capture.process(sms("AX-HDFCBK-S", "Sent Rs.14999.00\nFrom HDFC Bank A/C *1234\nTo Rahul\nOn 30/10/26\nRef 219300000004\nNot You?"), now = t0)
        capture.process(sms("AX-AIRBNK-S", "Airtel Payments Bank a/c is credited with Rs.14999.00. Txn ID: 219300000004. Call 180023400",
            time = t0 + 1.minutes), now = t0 + 1.minutes) // transfer
        capture.process(sms("VM-HDFCBK-S", "482913 is your OTP for txn of Rs.500.00 at AMAZON. Do not share."), now = t0) // ignored
        // DATA-005: the first side's item now shows the transfer, and can't be undone as if it were an expense.
        val item = inbox.items().single()
        assertEquals(DetectedKind.TRANSFER, item.kind)
        assertEquals(EntryType.TRANSFER, item.entry.type)
        inbox.undo(item.id, now = t0 + 2.minutes)
        assertEquals(EntryType.TRANSFER, EntryStore(db).byId(item.entry.id)!!.type)
        assertTrue(!EntryStore(db).byId(item.entry.id)!!.deleted)
        db.close()
    }

    @Test
    fun one_bad_row_does_not_break_the_inbox() = runTest { // FLAW-010 (branch review)
        val db = inMemoryDatabase()
        val inbox = DetectedInbox(db)
        CaptureStore(db, inbox).process(swiggy, now = t0)
        db.detected().add(DetectedRow("bad", "ADDED", "OPEN", "{not an entry", null, null, 0, entryId = null))
        assertEquals(1, inbox.items().size, "the bad row is skipped")
        inbox.undo(inbox.items().single().id, now = t0 + 1.minutes)
        assertEquals(emptyList(), inbox.items())
        db.close()
    }
}
