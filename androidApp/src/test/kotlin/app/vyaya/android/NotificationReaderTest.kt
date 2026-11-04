package app.vyaya.android

import app.vyaya.core.parser.Source
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** Which notifications become messages for capture, and how (plain logic, no Android). */
class NotificationReaderTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val t = Instant.parse("2026-11-04T10:00:00Z").toEpochMilliseconds()
    private val reader = NotificationReader(smsApps = setOf("com.google.android.apps.messaging"))

    private fun upi(pkg: String = "com.phonepe.app", title: String? = "Payment successful", text: String? = "₹120 paid to Swiggy", bigText: String? = null) =
        NotificationContent(pkg, title, text, bigText, emptyList(), emptyList(), t)

    @Test
    fun a_upi_apps_notification_is_one_message_from_that_app() {
        val raw = reader.read(upi(), ist).single()
        assertEquals(Source.NOTIFICATION, raw.source)
        assertEquals("com.phonepe.app", raw.sender)
        assertEquals("Payment successful\n₹120 paid to Swiggy", raw.text)
        assertEquals(t, raw.receivedAt.instant.toEpochMilliseconds())
        assertEquals(ist, raw.receivedAt.timeZone)
    }

    @Test
    fun the_expanded_text_is_used_when_there_is_one() {
        assertEquals("Paid\n₹120 paid to Swiggy from A/c XX12", reader.read(upi(title = "Paid", text = "₹120 paid…", bigText = "₹120 paid to Swiggy from A/c XX12"), ist).single().text)
    }

    @Test
    fun other_apps_and_lookalikes_are_not_read() {
        assertEquals(emptyList<Any>(), reader.read(upi(pkg = "com.whatsapp"), ist))
        assertEquals(emptyList<Any>(), reader.read(upi(pkg = "com.phonepe.app.fake"), ist))
    }

    @Test
    fun summaries_ongoing_and_empty_notifications_are_not_read() {
        assertEquals(emptyList<Any>(), reader.read(upi().copy(isGroupSummary = true), ist))
        assertEquals(emptyList<Any>(), reader.read(upi().copy(isOngoing = true), ist))
        assertEquals(emptyList<Any>(), reader.read(upi(title = null, text = " ", bigText = null), ist))
    }

    @Test
    fun an_sms_apps_conversation_gives_each_message_from_its_sender() {
        val c = NotificationContent(
            "com.google.android.apps.messaging", "VM-HDFCBK-S", "latest", null, emptyList(),
            listOf(
                NotificationContent.Message("VM-HDFCBK-S", "Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy", t - 60_000, null),
                NotificationContent.Message("VM-HDFCBK-S", "Sent Rs.80.00 From HDFC Bank A/C *1234 To Chai", t, null),
            ),
            t, people = listOf("tel:VM-HDFCBK-S"),
        )
        val raws = reader.read(c, ist)
        assertEquals(listOf(Source.SMS_APP_NOTIFICATION, Source.SMS_APP_NOTIFICATION), raws.map { it.source })
        assertEquals(listOf("VM-HDFCBK-S", "VM-HDFCBK-S"), raws.map { it.sender })
        assertEquals(listOf(t - 60_000, t), raws.map { it.receivedAt.instant.toEpochMilliseconds() })
        assertTrue(raws.none { it.senderIsContact })
    }

    @Test
    fun sec_002_a_saved_contact_or_an_unknown_sender_counts_as_a_contact() {
        val base = NotificationContent("com.google.android.apps.messaging", "VM-HDFCBK-S", "Rs.5,000 credited to A/c XX12", null, emptyList(), emptyList(), t)
        // A contact saved under a bank's header name: the people list points at the contact.
        assertTrue(reader.read(base.copy(people = listOf("content://com.android.contacts/contacts/lookup/abc/1")), ist).single().senderIsContact)
        // No people at all: it can't be told apart, so it goes to Review.
        assertTrue(reader.read(base, ist).single().senderIsContact)
        assertEquals(false, reader.read(base.copy(people = listOf("tel:VM-HDFCBK-S")), ist).single().senderIsContact)
    }

    @Test
    fun a_message_with_a_sender_of_its_own_is_checked_by_that_sender() {
        val c = NotificationContent("com.google.android.apps.messaging", "Group", null, null, emptyList(),
            listOf(NotificationContent.Message("Rahul", "Rs.500 credited", t, "content://com.android.contacts/contacts/lookup/r/2")), t,
            people = listOf("tel:VM-HDFCBK-S"))
        val raw = reader.read(c, ist).single()
        assertEquals("Rahul", raw.sender)
        assertTrue(raw.senderIsContact)
    }
}
