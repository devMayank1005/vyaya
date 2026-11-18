package app.vyaya.core.engine

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.onDay
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toInstant
import kotlinx.datetime.yearMonth
import kotlin.time.Instant

/**
 * Recurring entries: one per rule per calendar month (CLAUDE.md rule 8.4), however often catch-up runs
 * (plan.md invariant 4). The engine runs [catchUp] whenever the app opens; nothing is stored about it
 * (rule 8.5): which months are done is read from the entries' generation keys.
 */
object Recurring {

    /** Generated entries are dated at noon local time, well away from any day boundary. */
    private val NOON = LocalTime(12, 0)

    /**
     * The first due date of a rule created on [createdOn]: this month's due day if it hasn't passed, otherwise
     * next month's. So deleting a rule and adding it again in the same month can't make a second entry for
     * that month (FLAW-002, recurring-key-month report).
     */
    fun startsOnFor(createdOn: LocalDate, dayOfMonth: Int): LocalDate {
        val thisMonth = dueOn(dayOfMonth, createdOn.yearMonth)
        return if (thisMonth >= createdOn) thisMonth else dueOn(dayOfMonth, createdOn.yearMonth.plusMonth())
    }

    /** The rule's due date in [month]: its day, or the month's last day in short months. */
    fun dueOn(rule: RecurringRule, month: YearMonth): LocalDate = dueOn(rule.dayOfMonth, month)

    private fun dueOn(day: Int, month: YearMonth): LocalDate = month.onDay(minOf(day, month.numberOfDays))

    /**
     * The entries catch-up adds on [today] (the home time zone's date): for each active rule, every month from
     * its start whose due date has come and that has no entry with its key yet. A key the user deleted stays
     * done, and an entry moved to the next month still counts for its own month. Paused and deleted rules add
     * nothing. New entries are made at [now], dated at noon in [zone] on the due date.
     */
    fun catchUp(rules: List<RecurringRule>, entries: List<Entry>, today: LocalDate, zone: TimeZone, now: Instant): List<Entry> {
        val done = entries.mapNotNullTo(HashSet()) { it.generatedBy }
        return rules.filter { !it.deleted && !it.paused }.flatMap { rule ->
            missingMonths(rule, done, today).map { entryFor(rule, it, zone, now, deleted = false) }
        }
    }

    /** How far from a due date (in days) a payment can be and still be that bill, paid early or late (capture's window). */
    const val PAID_NEARBY_DAYS = 5

    private val BY_HAND_OR_CAPTURED = setOf(
        EntrySource.MANUAL, EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.SCREENSHOT, EntrySource.SHORTCUT,
    )

    /** What catch-up saves, and the bill payments it asks the user about ([draft] is the rule's entry not yet made). */
    data class LinkResult(val save: List<Entry>, val ask: List<Pair<Entry, Entry>>)

    /**
     * Safe-to-spend report DATA-001: a bill paid a few days early (by UPI, or typed in) and then made by catch-up was
     * counted twice. For each entry in [made], a payment in [nearby] of the same type and amount within
     * [PAID_NEARBY_DAYS] of its due date ([paymentFor]) may stand for it:
     * - when the payment has no category, or already has the rule's, it does: the rule's month is marked done (its key,
     *   deleted, pointing at the payment with linkedEntryId), and the payment takes the rule's category, account and
     *   note where it has none;
     * - when the payment is in another category, it may be a different payment of the same amount (branch review
     *   2026-11-18 #3): nothing is made yet, and the pair is returned in [LinkResult.ask] for the user ("Is this your
     *   Rent?").
     * A payment already standing for a rule month ([generated] keys pointing at it), or taken in this run, isn't used
     * again.
     */
    fun linkPaidOrAsk(made: List<Entry>, nearby: List<Entry>, generated: List<Entry>, now: Instant): LinkResult {
        val taken = generated.mapNotNullTo(HashSet()) { it.linkedEntryId }
        val save = mutableListOf<Entry>()
        val ask = mutableListOf<Pair<Entry, Entry>>()
        for (e in made) {
            if (e.deleted || e.generatedBy == null) { save += e; continue }
            val payment = paymentFor(e.type, e.amount, e.occurredAt.localDate, nearby, taken)
            if (payment == null) { save += e; continue }
            taken += payment.id
            if (payment.categoryId != null && payment.categoryId != e.categoryId) { ask += e to payment; continue }
            save += linked(e, payment, now)
        }
        return LinkResult(save, ask)
    }

    /** The rule's month [draft] marked done by [payment], and the payment with the rule's category, account and note. */
    fun linked(draft: Entry, payment: Entry, now: Instant): List<Entry> = listOf(
        draft.copy(deleted = true, linkedEntryId = payment.id),
        payment.copy(
            categoryId = payment.categoryId ?: draft.categoryId,
            accountId = payment.accountId ?: draft.accountId,
            note = payment.note.ifEmpty { draft.note },
            updatedAt = now,
        ),
    )

    /**
     * The payment among [nearby] that is a bill of [type] and [amount] due on [due], paid up to [PAID_NEARBY] early or
     * late: typed in or captured, not deleted, not a rule's own entry, not already standing for a bill ([taken]).
     * The nearest to the due date; then the smaller id, so every run picks the same one.
     */
    fun paymentFor(type: EntryType, amount: Money, due: LocalDate, nearby: List<Entry>, taken: Set<EntityId>): Entry? = nearby
        .filter { p ->
            !p.deleted && p.generatedBy == null && p.source in BY_HAND_OR_CAPTURED && p.id !in taken &&
                p.type == type && p.amount == amount && kotlin.math.abs(p.occurredAt.localDate.daysUntil(due)) <= PAID_NEARBY_DAYS
        }
        .minWithOrNull(compareBy({ kotlin.math.abs(it.occurredAt.localDate.daysUntil(due)) }, { it.id.value }))

    /**
     * The months [rule] missed while paused: due by [today] and with no entry. On resume the app asks whether to
     * add them ("yes": resume and catch-up adds them) or not ("no": [skip] them, then resume). FLAW-003.
     */
    fun missedWhilePaused(rule: RecurringRule, entries: List<Entry>, today: LocalDate): List<YearMonth> =
        missingMonths(rule, entries.mapNotNullTo(HashSet()) { it.generatedBy }, today)

    /** Marks [months] of [rule] as done without an entry: deleted entries with their keys, which catch-up skips. */
    fun skip(rule: RecurringRule, months: List<YearMonth>, zone: TimeZone, now: Instant): List<Entry> =
        months.map { entryFor(rule, it, zone, now, deleted = true) }

    private fun missingMonths(rule: RecurringRule, done: Set<Generation>, today: LocalDate): List<YearMonth> {
        val months = mutableListOf<YearMonth>()
        var month = rule.startsOn.yearMonth
        while (month <= today.yearMonth) {
            val due = dueOn(rule, month)
            if (due >= rule.startsOn && due <= today && Generation(rule.id, month) !in done) months += month
            month = month.plusMonth()
        }
        return months
    }

    private fun entryFor(rule: RecurringRule, month: YearMonth, zone: TimeZone, now: Instant, deleted: Boolean): Entry {
        val key = Generation(rule.id, month)
        return Entry(
            key.entryId, rule.type, rule.amount, CapturedAt(dueOn(rule, month).atTime(NOON).toInstant(zone), zone), rule.accountId, null,
            rule.categoryId, null, rule.note, emptyList(), EntrySource.RECURRING, null, emptyList(), null, now, now, deleted, key,
        )
    }
}
