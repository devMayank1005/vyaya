package app.vyaya.core.dedup

import app.vyaya.core.filters.Filters
import app.vyaya.core.filters.IgnoreReason
import app.vyaya.core.filters.ReviewReason
import app.vyaya.core.filters.Verdict
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.parser.Direction
import app.vyaya.core.parser.MessageParser
import app.vyaya.core.parser.ParsedTxn
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What capture did with one message. */
sealed interface Outcome {
    /** A new entry, to be saved. */
    data class Added(val entry: Entry) : Outcome

    /** The same payment is already there (another app's notification, the bank SMS, or this message read again). */
    data class Duplicate(val of: EntityId) : Outcome

    /** Waits in the review inbox: an unknown sender ([reason]) or a manual or recurring entry that may be the same payment. */
    data class Review(val draft: Entry, val reason: ReviewReason?, val possibleDuplicateOf: EntityId?) : Outcome

    data class Ignored(val reason: IgnoreReason) : Outcome
}

/**
 * The capture pipeline (plan.md Part C): message → parser → filters → de-dup → entry. Works the same whether the
 * message came from a live SMS, the SMS import, a UPI app's notification, or the SMS app's notification.
 * Plan.md invariant 1: no payment logged twice.
 */
object Capture {

    /** Notifications and bank SMS for one payment arrive within minutes of each other. */
    private val SAME_PAYMENT = 10.minutes

    /**
     * Two apps' notifications for one payment arrive within seconds. An entry doesn't record which app it came
     * from, so two notifications this close with the same amount and payee are taken as one payment.
     */
    private val SAME_PAYMENT_TWO_APPS = 2.minutes

    /** How far a typed-in entry's time may be from the bank's message and still be the same payment. */
    private val MANUAL_NEARBY = 24.hours

    /** Recurring entries are dated on the due day; the real payment may be a few days off. */
    private val RECURRING_NEARBY = 5.days

    fun process(raw: RawMessage, existing: List<Entry>, now: Instant): Outcome {
        val verdict = Filters.judge(raw, MessageParser.parse(raw))
        val txn = when (verdict) {
            is Verdict.Ignore -> return Outcome.Ignored(verdict.reason)
            is Verdict.Accept -> verdict.txn
            is Verdict.Review -> verdict.txn
        }
        val entry = toEntry(txn, raw.source, now) ?: return Outcome.Ignored(IgnoreReason.NOT_A_TRANSACTION)
        if (verdict is Verdict.Accept) duplicateOf(entry, existing)?.let { return Outcome.Duplicate(it) }
        val possible = possibleDuplicateOf(entry, existing)
        return when {
            verdict is Verdict.Review -> Outcome.Review(entry, verdict.reason, possible)
            possible != null -> Outcome.Review(entry, null, possible)
            else -> Outcome.Added(entry)
        }
    }

    /** The entry a parsed payment becomes; null if it can't be a valid entry. */
    internal fun toEntry(txn: ParsedTxn, source: Source, now: Instant): Entry? = runCatching {
        Entry(
            EntityId.random(), if (txn.direction == Direction.DEBIT) EntryType.EXPENSE else EntryType.INCOME, txn.amount, txn.occurredAt,
            null, null, null, txn.counterparty?.take(100), "", emptyList(),
            when (source) {
                Source.SMS -> EntrySource.SMS
                Source.NOTIFICATION -> EntrySource.NOTIFICATION
                Source.SCREENSHOT_OCR -> EntrySource.SCREENSHOT
                Source.SHORTCUT -> EntrySource.SHORTCUT
            },
            txn.upiRef?.take(64), emptyList(), null, now, now, false,
        )
    }.getOrNull()

    private val CAPTURED = setOf(EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.SCREENSHOT, EntrySource.SHORTCUT)

    /**
     * An entry captured earlier for the same payment. Deleted ones count too: a payment the user deleted stays
     * deleted when its bank SMS or the import brings it again.
     */
    private fun duplicateOf(c: Entry, existing: List<Entry>): EntityId? = existing.firstOrNull { e ->
        e.source in CAPTURED && e.type == c.type && e.amount == c.amount && when {
            e.upiRef != null && c.upiRef != null -> e.upiRef == c.upiRef // the bank's reference decides
            e.source == c.source && e.occurredAt.instant == c.occurredAt.instant -> true // the same message read again
            e.source == c.source -> c.source == EntrySource.NOTIFICATION && within(e, c, SAME_PAYMENT_TWO_APPS) && samePayee(e, c)
            else -> within(e, c, SAME_PAYMENT) // the app's notification and the bank's SMS
        }
    }?.id

    /** A typed-in or recurring entry that may be this payment: the user decides (never dropped silently). */
    private fun possibleDuplicateOf(c: Entry, existing: List<Entry>): EntityId? = existing.firstOrNull { e ->
        !e.deleted && e.type == c.type && e.amount == c.amount && when (e.source) {
            EntrySource.MANUAL -> within(e, c, MANUAL_NEARBY)
            EntrySource.RECURRING -> within(e, c, RECURRING_NEARBY)
            else -> false
        }
    }?.id

    private fun samePayee(a: Entry, b: Entry) = a.payee != null && a.payee.equals(b.payee, ignoreCase = true)

    private fun within(a: Entry, b: Entry, window: Duration) = (a.occurredAt.instant - b.occurredAt.instant).absoluteValue <= window
}
