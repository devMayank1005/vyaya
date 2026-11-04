package app.vyaya.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.parser.Source
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The full build: SMS as they arrive, and the last 30 days imported, through capture into the database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsCaptureTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-04T10:00:00Z")
    private val ms = now.toEpochMilliseconds()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val capture = CaptureStore(db, DetectedInbox(db))

    @After fun close() = db.close()

    private val hdfc = "Sent Rs.250.00 From HDFC Bank A/C *1234 To Swiggy On 04/11/26 Ref 429100000001 Not You? Call 18002586161"

    @Test
    fun the_parts_of_a_long_sms_are_one_message_per_sender() {
        val parts = listOf(
            SmsParts.Part("VM-HDFCBK-S", hdfc.take(60), ms), SmsParts.Part("AX-AIRBNK-S", "Rs.80 debited", ms + 1),
            SmsParts.Part("VM-HDFCBK-S", hdfc.drop(60), ms + 2), SmsParts.Part("VM-ICICIB-S", " ", ms),
        )
        val raws = SmsParts.join(parts, ist)
        assertEquals(listOf("VM-HDFCBK-S" to hdfc, "AX-AIRBNK-S" to "Rs.80 debited"), raws.map { it.sender to it.text })
        assertEquals(listOf(ms, ms + 1), raws.map { it.receivedAt.instant.toEpochMilliseconds() })
        assertEquals(setOf(Source.SMS), raws.map { it.source }.toSet())
    }

    @Test
    fun an_arriving_sms_is_added() = runTest {
        SmsCapture(capture, { now }, { ist }).handle(listOf(SmsParts.Part("VM-HDFCBK-S", hdfc, ms)))
        assertEquals(250_00L, EntryStore(db).all().single().amount.paise)
    }

    @Test
    fun the_import_reads_the_last_30_days_once() = runTest {
        val source = object : SmsSource {
            var since: Long? = null
            override fun inbox(sinceMs: Long): Sequence<SmsParts.Part> {
                since = sinceMs
                return sequenceOf(SmsParts.Part("VM-HDFCBK-S", hdfc, ms - 2.days.inWholeMilliseconds), SmsParts.Part("+919800000000", "hi", ms))
            }
        }
        val import = SmsImport(source, SmsCapture(capture, { now }, { ist }))
        assertEquals(1, import.run(now))
        assertEquals(ms - 30.days.inWholeMilliseconds, source.since)
        assertEquals("importing again adds nothing", 0, import.run(now))
        assertEquals(1, EntryStore(db).all().size)
    }

    @Test
    fun the_inbox_is_read_from_the_sms_provider_newest_window_only() {
        Robolectric.buildContentProvider(FakeSmsProvider::class.java).create("sms").get().rows = listOf(
            Triple("VM-HDFCBK-S", hdfc, ms - 1.days.inWholeMilliseconds),
            Triple("VM-HDFCBK-S", "old", ms - 40.days.inWholeMilliseconds),
        )
        val parts = ContentResolverSmsSource(context.contentResolver).inbox(ms - 30.days.inWholeMilliseconds).toList()
        assertEquals(listOf(hdfc), parts.map { it.body })
        assertFalse(parts.any { it.body == "old" })
    }

    @Test
    fun data_001_the_full_build_reads_the_sms_apps_notifications_until_sms_is_allowed() {
        assertEquals(true, Channel.READS_SMS)
        // Not allowed (or taken back): the SMS app's notifications are the only way bank SMS arrive.
        assertTrue("com.google.android.apps.messaging" in CaptureListener.smsAppsToRead(context))
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.RECEIVE_SMS)
        // Allowed: each SMS comes itself, with the sender's real address; its notification would be a second copy.
        assertEquals(emptySet<String>(), CaptureListener.smsAppsToRead(context))
    }

    @Test
    fun flaw_002_the_import_reads_the_inbox_off_the_main_thread() = runTest {
        var onMain: Boolean? = null
        val source = object : SmsSource {
            override fun inbox(sinceMs: Long): Sequence<SmsParts.Part> {
                onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                return emptySequence()
            }
        }
        assertTrue("this test runs on the main thread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        SmsImport(source, SmsCapture(capture, { now }, { ist })).run(now)
        assertEquals(false, onMain)
    }

    @Test
    fun data_007_an_arriving_sms_is_dated_when_the_phone_received_it() {
        // The network's sent time can be hours off (late delivery, a wrong clock at the SMS centre); the inbox's own
        // date column is the receive time, so the import and the live read must agree on it.
        val sentHoursAgo = ms - 5 * 3_600_000
        val parts = SmsParts.delivered(listOf(SmsParts.Delivered("VM-HDFCBK-S", hdfc.take(60), sentHoursAgo), SmsParts.Delivered("VM-HDFCBK-S", hdfc.drop(60), sentHoursAgo)), receivedAtMs = ms)
        assertEquals(listOf(ms, ms), parts.map { it.timeMs })
        assertEquals(hdfc, SmsParts.join(parts, ist).single().text)
    }
}

/** A stand-in for the system's SMS provider, honouring the `date >= ?` selection. */
class FakeSmsProvider(var rows: List<Triple<String, String, Long>> = emptyList()) : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        require(uri == Telephony.Sms.Inbox.CONTENT_URI) { "unexpected $uri" }
        require(selection == "${Telephony.Sms.DATE} >= ?") { "unexpected $selection" }
        val since = args!!.single().toLong()
        val c = MatrixCursor(arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE))
        rows.filter { it.third >= since }.sortedBy { it.third }.forEach { c.addRow(arrayOf<Any>(it.first, it.second, it.third)) }
        return c
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
