package app.vyaya.android

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import app.vyaya.core.parser.UpiApps
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What capture uses of a posted notification (taken from a `StatusBarNotification` by [NotificationContent.of]). */
data class NotificationContent(
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    /** InboxStyle lines. */
    val textLines: List<String>,
    /** MessagingStyle messages (an SMS app's conversation). */
    val messages: List<Message>,
    val postTimeMs: Long,
    /** URIs of the notification's people (`tel:…`, or a contact's `content://com.android.contacts/…`). */
    val people: List<String> = emptyList(),
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
) {
    data class Message(val sender: String?, val text: String, val timeMs: Long, val senderUri: String?)

    companion object
}

/**
 * Decides which notifications become messages for capture. Only the listed UPI and bank apps and the SMS apps are
 * read at all; every other app's notifications are dropped here, unread (nothing else is looked at or kept).
 */
class NotificationReader(
    private val smsApps: Set<String>,
    private val upiApps: Set<String> = UpiApps.default.apps.mapTo(HashSet()) { it.packageName },
) {
    fun read(c: NotificationContent, zone: TimeZone): List<RawMessage> {
        if (c.isGroupSummary || c.isOngoing) return emptyList()
        return when (c.packageName) {
            in smsApps -> fromSmsApp(c, zone)
            in upiApps -> fromUpiApp(c, zone)
            else -> emptyList()
        }
    }

    private fun at(ms: Long, zone: TimeZone) = CapturedAt(Instant.fromEpochMilliseconds(ms), zone)

    private fun fromUpiApp(c: NotificationContent, zone: TimeZone): List<RawMessage> {
        val bodies = c.textLines.ifEmpty { listOfNotNull(c.bigText?.takeIf { it.isNotBlank() } ?: c.text) }
        return bodies.mapNotNull { body ->
            val text = listOfNotNull(c.title, body).filter { it.isNotBlank() }.joinToString("\n")
            if (text.isBlank()) null else RawMessage(Source.NOTIFICATION, c.packageName, text, at(c.postTimeMs, zone))
        }
    }

    /**
     * An SMS app's notification is titled with the sender: a bank's header, or the name of a saved contact. SEC-002:
     * a contact saved as `VM-HDFCBK-S` must not pass as the bank, so a sender counts as a contact when the
     * notification points at a contact, and also when it gives no people at all (it can't be told apart).
     */
    private fun fromSmsApp(c: NotificationContent, zone: TimeZone): List<RawMessage> {
        val conversationIsContact = c.people.isEmpty() || c.people.any(::isContact)
        if (c.messages.isNotEmpty()) {
            return c.messages.mapNotNull { m ->
                val sender = m.sender ?: c.title ?: return@mapNotNull null
                if (m.text.isBlank()) return@mapNotNull null
                RawMessage(Source.SMS_APP_NOTIFICATION, sender, m.text, at(m.timeMs, zone), m.senderUri?.let(::isContact) ?: conversationIsContact)
            }
        }
        val sender = c.title ?: return emptyList()
        val text = c.bigText?.takeIf { it.isNotBlank() } ?: c.text
        if (text.isNullOrBlank()) return emptyList()
        return listOf(RawMessage(Source.SMS_APP_NOTIFICATION, sender, text, at(c.postTimeMs, zone), conversationIsContact))
    }

    private fun isContact(uri: String) = uri.startsWith("content://com.android.contacts/")
}
