package app.vyaya.android

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** SMS parts as the system delivers them, joined into whole messages. */
object SmsParts {
    data class Part(val address: String, val body: String, val timeMs: Long)

    /** A long SMS arrives in parts; one message per sender, its parts in order, at its first part's time. */
    fun join(parts: List<Part>, zone: TimeZone): List<RawMessage> =
        parts.groupBy { it.address }.mapNotNull { (address, ps) ->
            val text = ps.joinToString("") { it.body }
            if (text.isBlank()) null else RawMessage(Source.SMS, address, text, CapturedAt(Instant.fromEpochMilliseconds(ps.first().timeMs), zone))
        }
}

/** SMS through capture. Never throws and logs nothing (an SMS log would hold the user's messages). */
class SmsCapture(private val capture: CaptureStore, private val clock: () -> Instant, private val zone: () -> TimeZone) {

    /** One arriving SMS (its parts); the number of entries added. */
    suspend fun handle(parts: List<SmsParts.Part>): Int = process(SmsParts.join(parts, zone()))

    /** Whole messages; the number of entries added. */
    suspend fun process(raws: List<RawMessage>): Int {
        var added = 0
        for (raw in raws) {
            try {
                added += capture.process(raw, clock()).count { it is Outcome.Added }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                // Dropped; the next import reads it again.
            }
        }
        return added
    }
}

/** The SMS inbox, from a time on, oldest first. */
interface SmsSource {
    fun inbox(sinceMs: Long): Sequence<SmsParts.Part>
}

class ContentResolverSmsSource(private val resolver: ContentResolver) : SmsSource {
    override fun inbox(sinceMs: Long): Sequence<SmsParts.Part> {
        val parts = mutableListOf<SmsParts.Part>()
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(sinceMs.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val a = c.getString(address) ?: continue
                parts += SmsParts.Part(a, c.getString(body).orEmpty(), c.getLong(date))
            }
        }
        return parts.asSequence()
    }
}

/** Reads the last [DAYS] days of SMS into capture; reading again adds nothing (de-dup). */
class SmsImport(private val source: SmsSource, private val capture: SmsCapture, private val io: CoroutineDispatcher = Dispatchers.IO) {

    /** The number of entries added. */
    suspend fun run(now: Instant): Int {
        val since = (now - DAYS.days).toEpochMilliseconds()
        // FLAW-002: a month of SMS can be thousands of rows; never read them on the main thread. Each stored SMS is whole.
        val messages = withContext(io) { source.inbox(since).flatMap { SmsParts.join(listOf(it), TimeZone.currentSystemDefault()) }.toList() }
        return capture.process(messages)
    }

    companion object {
        const val DAYS = 30

        fun forApp(context: Context): SmsImport {
            val app = context.applicationContext as VyayaApp
            return SmsImport(ContentResolverSmsSource(context.contentResolver), app.smsCapture())
        }
    }
}

fun VyayaApp.smsCapture() = SmsCapture(capture, { Clock.System.now() }, { TimeZone.currentSystemDefault() })

/** SMS as they arrive. Only the system can send it this broadcast (the manifest asks for BROADCAST_SMS). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty().mapNotNull { m ->
            SmsParts.Part(m?.originatingAddress ?: return@mapNotNull null, m.messageBody.orEmpty(), m.timestampMillis)
        }
        if (parts.isEmpty()) return
        val app = context.applicationContext as VyayaApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { app.smsCapture().handle(parts) } finally { pending.finish() }
        }
    }
}
