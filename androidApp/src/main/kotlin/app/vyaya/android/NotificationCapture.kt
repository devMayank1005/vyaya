package app.vyaya.android

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.vyaya.core.data.CaptureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** The parts of a posted notification capture uses. */
fun NotificationContent.Companion.of(sbn: StatusBarNotification): NotificationContent {
    val n = sbn.notification
    val extras = n.extras
    fun text(key: String) = extras.getCharSequence(key)?.toString()
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
    val messages = style?.messages.orEmpty().mapNotNull { m ->
        val body = m.text?.toString() ?: return@mapNotNull null
        NotificationContent.Message(m.person?.name?.toString(), body, m.timestamp, m.person?.uri)
    }
    return NotificationContent(
        packageName = sbn.packageName,
        title = text(Notification.EXTRA_TITLE) ?: text(Notification.EXTRA_CONVERSATION_TITLE),
        text = text(Notification.EXTRA_TEXT),
        bigText = text(Notification.EXTRA_BIG_TEXT),
        textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() },
        messages = messages,
        postTimeMs = sbn.postTime,
        people = people(n),
        isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        isOngoing = sbn.isOngoing,
        whenMs = n.`when`,
    )
}

private fun people(n: Notification): List<String> {
    val extras = n.extras
    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java)
    } else {
        @Suppress("DEPRECATION") extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
    }
    @Suppress("DEPRECATION")
    val legacy = extras.getStringArray(Notification.EXTRA_PEOPLE).orEmpty().toList()
    return list.orEmpty().mapNotNull { it.uri } + legacy
}

/**
 * A posted notification through capture. Never throws: the listener runs inside the system's notification service
 * connection, and a crash there loses every later notification until the user re-enables access. Nothing is logged
 * (a log would hold payment and SMS text).
 */
class NotificationCapture(
    private val capture: CaptureStore,
    /** Made for each notification: which apps it reads can change (SMS allowed or taken back). */
    private val reader: () -> NotificationReader,
    private val clock: () -> Instant,
    private val zone: () -> TimeZone,
) {
    suspend fun handle(sbn: StatusBarNotification) {
        try {
            for (raw in reader().read(NotificationContent.of(sbn), zone())) capture.process(raw, clock())
        } catch (e: CancellationException) {
            // Our own cancellation goes on; one from inside (a closed database cancels its work) is a failure like any other.
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            // Dropped: the same payment's SMS, or the 30-day import, can still bring it in.
        }
    }
}
