package app.vyaya.core.dedup

import app.vyaya.core.filters.Filters
import app.vyaya.core.filters.IgnoreReason
import app.vyaya.core.filters.ReviewReason
import app.vyaya.core.filters.Verdict
import app.vyaya.core.model.Account
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.parser.Direction
import app.vyaya.core.parser.MessageParser
import app.vyaya.core.parser.MessageSplitter
import app.vyaya.core.parser.ParseResult
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

    /**
     * The same payment is already there (another app's notification, the bank SMS, or this message read again).
     * [update] is that entry with what this message adds (the account, from the bank SMS's last digits), to be saved.
     */
    data class Duplicate(val of: EntityId, val update: Entry? = null) : Outcome

    /** Waits in the review inbox: an unknown sender ([reason]) or a manual or recurring entry that may be the same payment. */
    data class Review(val draft: Entry, val reason: ReviewReason?, val possibleDuplicateOf: EntityId?) : Outcome

    data class Ignored(val reason: IgnoreReason) : Outcome

    /**
     * Money moved between the user's own accounts: [entry] is the earlier entry for one side, now a transfer, to be
     * saved over it. Nothing is added: a transfer is neither spending nor income.
     */
    data class Transfer(val entry: Entry) : Outcome
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

    /** One SMS read again (live, from the SMS app's notification, by the import): the stamps differ by seconds at most. */
    private val SAME_SMS_AGAIN = 2.minutes

    /** How far a typed-in entry with the same payee may be from the bank's message and still be the same payment. */
    private val MANUAL_NEARBY = 24.hours

    /** A typed-in entry with no payee is only a possible duplicate this close in time (FLAW-005). */
    private val MANUAL_NO_PAYEE_NEARBY = 2.hours

    /** Recurring entries are dated on the due day; the real payment may be a few days off. */
    private val RECURRING_NEARBY = 5.days

    /**
     * Captures every payment in [raw]: one, or several when the message lists several (a card summary SMS, see
     * [MessageSplitter]). Each later part is checked against the entries the earlier parts added.
     */
    fun processAll(
        raw: RawMessage, existing: List<Entry>, now: Instant, accounts: List<Account> = emptyList(), merchants: MerchantMemory = MerchantMemory.NONE,
    ): List<Outcome> {
        var entries = existing
        return MessageSplitter.split(raw).map { part ->
            process(part, entries, now, accounts, merchants).also { if (it is Outcome.Added) entries = entries + it.entry }
        }
    }

    fun process(
        raw: RawMessage, existing: List<Entry>, now: Instant, accounts: List<Account> = emptyList(), merchants: MerchantMemory = MerchantMemory.NONE,
    ): Outcome = process(raw, MessageParser.parse(raw), existing, now, accounts, merchants)

    /** As [process], for a message already parsed (the database store parses once, to look up by reference). */
    fun process(
        raw: RawMessage, parsed: ParseResult, existing: List<Entry>, now: Instant, accounts: List<Account> = emptyList(),
        merchants: MerchantMemory = MerchantMemory.NONE,
    ): Outcome {
        val verdict = Filters.judge(raw, parsed)
        val txn = when (verdict) {
            is Verdict.Ignore -> return Outcome.Ignored(verdict.reason)
            is Verdict.Accept -> verdict.txn
            is Verdict.Review -> verdict.txn
        }
        val entry = toEntry(txn, raw.source, now)?.copy(accountId = accountFor(txn.accountMask, accounts))
            ?.let { it.copy(categoryId = merchants.categoryFor(it)) }
            ?: return Outcome.Ignored(IgnoreReason.NOT_A_TRANSACTION)
        if (verdict is Verdict.Accept) {
            otherSideOf(entry, existing)?.let { return Outcome.Transfer(it.copy(type = EntryType.TRANSFER, updatedAt = now)) }
            duplicateOf(entry, existing)?.let { id -> return Outcome.Duplicate(id, withAccountFrom(entry, existing.first { it.id == id }, now)) }
        }
        // DATA-003: a review item for a payment already captured points at it, so confirming can't log it twice.
        val possible = (if (verdict is Verdict.Review) duplicateOf(entry, existing) else null) ?: possibleDuplicateOf(entry, existing)
        return when {
            verdict is Verdict.Review -> Outcome.Review(entry, verdict.reason, possible)
            possible != null -> Outcome.Review(entry, null, possible)
            else -> Outcome.Added(entry)
        }
    }

    /**
     * The one account (not deleted) whose last digits match the message's masked number (catalog report #4). The user
     * may have typed more or fewer digits than the bank shows, so one may end with the other. Two matches: no guess.
     */
    fun accountFor(mask: String?, accounts: List<Account>): EntityId? {
        if (mask == null || mask.length < 3) return null
        return accounts.filter { a -> !a.deleted && a.mask?.let { it.endsWith(mask) || mask.endsWith(it) } == true }.singleOrNull()?.id
    }

    /** [existing], captured without an account, takes the one [entry] found (only captured entries are duplicates). */
    private fun withAccountFrom(entry: Entry, existing: Entry, now: Instant): Entry? =
        if (entry.accountId != null && existing.accountId == null) {
            existing.copy(accountId = entry.accountId, updatedAt = now)
        } else null

    /** The entry a parsed payment becomes; null if it can't be a valid entry. */
    internal fun toEntry(txn: ParsedTxn, source: Source, now: Instant): Entry? = runCatching {
        Entry(
            EntityId.random(), if (txn.direction == Direction.DEBIT) EntryType.EXPENSE else EntryType.INCOME, txn.amount, txn.occurredAt,
            null, null, null, txn.counterparty?.take(100), "", emptyList(),
            when (source) {
                // The SMS app's notification is the bank's SMS, read without SMS permission (Play build). The Play build
                // has no SMS import and the full build doesn't listen to it, so the two never meet for one payment.
                Source.SMS, Source.SMS_APP_NOTIFICATION -> EntrySource.SMS
                Source.NOTIFICATION -> EntrySource.NOTIFICATION
                Source.SCREENSHOT_OCR -> EntrySource.SCREENSHOT
                Source.SHORTCUT -> EntrySource.SHORTCUT
            },
            txn.upiRef?.take(64), emptyList(), null, now, now, false,
        )
    }.getOrNull()

    private val CAPTURED = setOf(EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.SCREENSHOT, EntrySource.SHORTCUT)

    /** An entry already captured for the same payment as [c] (the rules capture uses), or null. */
    fun capturedCopyOf(c: Entry, existing: List<Entry>): EntityId? = duplicateOf(c, existing)

    /**
     * An entry captured earlier for the same payment. Deleted ones count too: a payment the user deleted stays
     * deleted when its bank SMS or the import brings it again.
     */
    private fun duplicateOf(c: Entry, existing: List<Entry>): EntityId? = existing.firstOrNull { e ->
        // DATA-001: a transfer stands for both of its sides; re-reading either side's message is the same money.
        e.source in CAPTURED && e.type == EntryType.TRANSFER && c.upiRef != null && e.upiRef == c.upiRef && e.amount == c.amount
    }?.id ?: existing.firstOrNull { e ->
        e.source in CAPTURED && e.type == c.type && e.amount == c.amount && when {
            e.upiRef != null && c.upiRef != null -> e.upiRef == c.upiRef // the bank's reference decides
            e.source == c.source && e.occurredAt.instant == c.occurredAt.instant -> true // the same message read again
            e.source == c.source -> when (c.source) {
                EntrySource.NOTIFICATION -> within(e, c, SAME_PAYMENT_TWO_APPS) && samePayee(e, c)
                // DATA-001 (review 2026-11-06): one SMS read live, from the SMS app's notification and by the import is
                // stamped a few milliseconds or seconds apart each time.
                EntrySource.SMS -> within(e, c, SAME_SMS_AGAIN) && (e.payee == null && c.payee == null || samePayee(e, c))
                else -> false
            }
            else -> within(e, c, SAME_PAYMENT) // the app's notification and the bank's SMS
        }
    }?.id

    /**
     * The other side of a transfer between the user's own accounts: a captured entry with the same bank reference and
     * amount but the opposite direction. Both sides' messages reach this phone only when both accounts are the user's
     * (the user's sample: HDFC "Sent Rs.14999 Ref 2193…" and Airtel "credited with Rs.14999 Txn ID 2193…").
     */
    private fun otherSideOf(c: Entry, existing: List<Entry>): Entry? = existing.firstOrNull { e ->
        !e.deleted && e.source in CAPTURED && c.upiRef != null && e.upiRef == c.upiRef && e.amount == c.amount &&
            setOf(e.type, c.type) == setOf(EntryType.EXPENSE, EntryType.INCOME)
    }

    /** A typed-in or recurring entry that may be this payment: the user decides (never dropped silently). */
    private fun possibleDuplicateOf(c: Entry, existing: List<Entry>): EntityId? = existing.firstOrNull { e ->
        !e.deleted && e.type == c.type && e.amount == c.amount && when (e.source) {
            // FLAW-005: a ₹20 tea typed in this morning isn't the ₹20 Swiggy payment this evening.
            EntrySource.MANUAL -> if (e.payee == null) within(e, c, MANUAL_NO_PAYEE_NEARBY) else within(e, c, MANUAL_NEARBY) && samePayee(e, c)
            EntrySource.RECURRING -> within(e, c, RECURRING_NEARBY)
            else -> false
        }
    }?.id

    /**
     * Apps name one payee differently: BHIM shows `rahulsharma@okaxis`, PhonePe `Rahul Sharma` (SIM-001,
     * filters-dedup report). Compared loosely: a UPI ID's part before `@`, letters and digits only, any case.
     */
    private fun samePayee(a: Entry, b: Entry): Boolean {
        val x = payeeKey(a.payee ?: return false)
        return x.isNotEmpty() && x == payeeKey(b.payee ?: return false)
    }

    fun payeeKey(payee: String): String = payee.substringBefore('@').lowercase().filter { it.isLetterOrDigit() }

    private fun within(a: Entry, b: Entry, window: Duration) = (a.occurredAt.instant - b.occurredAt.instant).absoluteValue <= window
}
