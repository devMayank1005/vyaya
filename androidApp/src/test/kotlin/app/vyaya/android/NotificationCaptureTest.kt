package app.vyaya.android

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.DetectedKind
import app.vyaya.core.data.VyayaDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Real notifications, as the system hands them to the listener, through capture into the database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val inbox = DetectedInbox(db)
    private val now = Instant.parse("2026-11-04T10:00:00Z")
    private val capture = NotificationCapture(
        CaptureStore(db, inbox), { NotificationReader(smsApps = setOf("com.google.android.apps.messaging")) },
        clock = { now }, zone = { TimeZone.of("Asia/Kolkata") },
    )

    @After fun close() = db.close()

    private fun sbn(pkg: String, n: Notification, id: Int = 1) =
        StatusBarNotification(pkg, pkg, id, null, 10_001, 0, 0, n, Process.myUserHandle(), now.toEpochMilliseconds())

    private fun phonePe(text: String) = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Payment successful").setContentText("₹120 paid…")
        .setStyle(NotificationCompat.BigTextStyle().bigText(text)).build()

    @Test
    fun a_notification_is_read_with_its_expanded_text_and_people() {
        val c = NotificationContent.of(sbn("com.phonepe.app", phonePe("₹120 paid to Swiggy")))
        assertEquals("com.phonepe.app", c.packageName)
        assertEquals("Payment successful", c.title)
        assertEquals("₹120 paid to Swiggy", c.bigText)
        assertEquals(now.toEpochMilliseconds(), c.postTimeMs)

        val bank = Person.Builder().setName("VM-HDFCBK-S").setUri("tel:VM-HDFCBK-S").build()
        val sms = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage("Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy", now.toEpochMilliseconds() - 5_000, bank))
            .addPerson(bank).build()
        val m = NotificationContent.of(sbn("com.google.android.apps.messaging", sms))
        assertEquals(listOf(NotificationContent.Message("VM-HDFCBK-S", "Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy", now.toEpochMilliseconds() - 5_000, "tel:VM-HDFCBK-S")), m.messages)
        assertEquals(listOf("tel:VM-HDFCBK-S"), m.people)
    }

    @Test
    fun a_upi_payment_notification_is_added_and_listed_in_the_inbox() = runTest {
        capture.handle(sbn("com.phonepe.app", phonePe("₹120 paid to Swiggy")))
        val item = inbox.items().single()
        assertEquals(DetectedKind.ADDED, item.kind)
        assertEquals(120_00L, item.entry.amount.paise)
        // The app posts the same notification again (updated): still one entry.
        capture.handle(sbn("com.phonepe.app", phonePe("₹120 paid to Swiggy")))
        assertEquals(1, inbox.items().size)
    }

    @Test
    fun a_summary_or_another_apps_notification_adds_nothing() = runTest {
        val summary = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText("₹120 paid to Swiggy").setGroup("g").setGroupSummary(true).build()
        capture.handle(sbn("com.phonepe.app", summary))
        capture.handle(sbn("com.whatsapp", phonePe("₹500 paid to Rahul")))
        assertEquals(emptyList<Any>(), inbox.items())
    }

    @Test
    fun a_contact_named_like_a_bank_goes_to_review() = runTest {
        val fake = Person.Builder().setName("VM-HDFCBK-S").setUri("content://com.android.contacts/contacts/lookup/x/7").build()
        val n = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage("Rs.5,000.00 credited to HDFC Bank A/c XX1234 on 04-11-26. UPI Ref 429100000009", now.toEpochMilliseconds(), fake))
            .addPerson(fake).build()
        capture.handle(sbn("com.google.android.apps.messaging", n))
        val item = inbox.items().single()
        assertEquals(DetectedKind.REVIEW, item.kind)
    }

    @Test
    fun a_failure_inside_capture_never_crashes_the_listener() = runTest {
        db.close() // the database is gone (e.g. process being torn down)
        capture.handle(sbn("com.phonepe.app", phonePe("₹120 paid to Swiggy")))
        assertTrue(true)
    }

    @Test
    fun review_data_005_an_app_updating_its_notification_minutes_later_adds_nothing() = runTest {
        val shown = now.toEpochMilliseconds() - 10_000
        fun paid(extra: String) = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(shown).setContentTitle("PhonePe").setContentText("₹500 paid successfully$extra").build()
        fun posted(n: Notification, at: Long) = StatusBarNotification("com.phonepe.app", "com.phonepe.app", 1, null, 10_001, 0, 0, n, Process.myUserHandle(), at)
        assertEquals(shown, NotificationContent.of(posted(paid(""), now.toEpochMilliseconds())).whenMs)
        capture.handle(posted(paid(""), now.toEpochMilliseconds()))
        capture.handle(posted(paid(". Scratch card won!"), now.toEpochMilliseconds() + 3 * 60_000))
        assertEquals(1, inbox.items().size)
    }
}
