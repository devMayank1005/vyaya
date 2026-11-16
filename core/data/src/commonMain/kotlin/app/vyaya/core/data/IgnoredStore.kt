package app.vyaya.core.data

import app.vyaya.core.filters.IgnoreReason
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** One message capture skipped, as the Ignored tab shows it. */
data class IgnoredItem(val id: String, val sender: String, val amount: Money, val reason: IgnoreReason, val receivedAt: Instant)

/**
 * The Ignored tab (Phase 3, plan B2 "Trust"): "why wasn't this logged?". Messages with an amount that capture skipped,
 * kept [KEEP] as a summary only (the user's choice): who sent it (a phone number masked), the amount, the reason and
 * when; never its text. The user can say it was a payment (then adds it) or not; either way it leaves the list.
 */
class IgnoredStore(private val db: VyayaDatabase) {

    /** Called by capture, in its transaction. */
    internal suspend fun record(message: RawMessage, reason: IgnoreReason, amount: Money, now: Instant) {
        db.ignored().purge((now - KEEP).toEpochMilliseconds())
        if (message.receivedAt.instant < now - KEEP) return // an old SMS read by the import: past keeping already
        db.ignored().add(IgnoredRow(DetectedInbox.idOf(message), masked(message.sender), amount.paise, reason.name,
            message.receivedAt.instant.toEpochMilliseconds(), OPEN))
    }

    /** What's waiting for an answer, newest first; anything older than [KEEP] is removed from the phone first. */
    suspend fun items(now: Instant): List<IgnoredItem> {
        db.ignored().purge((now - KEEP).toEpochMilliseconds())
        return db.ignored().open().mapNotNull { r ->
            val reason = IgnoreReason.entries.firstOrNull { it.name == r.reason } ?: return@mapNotNull null
            IgnoredItem(r.id, r.sender, Money(r.amountPaise), reason, Instant.fromEpochMilliseconds(r.receivedAtMs))
        }
    }

    /** The user says it was a payment (and adds it as an entry). */
    suspend fun wasPayment(id: String) = db.ignored().setStatus(id, "PAYMENT")

    suspend fun notPayment(id: String) = db.ignored().setStatus(id, "NOT_PAYMENT")

    companion object {
        val KEEP = 30.days
        private const val OPEN = "OPEN"

        /** "+919876543210" → "+91••••••3210"; a header ("VM-HDFCBK-S") or an app is kept as it is. */
        fun masked(sender: String): String {
            if (sender.count { it.isDigit() } < 7) return sender
            val head = if (sender.startsWith("+")) sender.take(3) else ""
            val tail = sender.takeLast(4)
            return head + "•".repeat((sender.length - head.length - tail.length).coerceAtLeast(0)) + tail
        }
    }
}
