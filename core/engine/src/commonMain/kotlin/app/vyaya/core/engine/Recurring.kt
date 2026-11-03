package app.vyaya.core.engine

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.Generation
import app.vyaya.core.model.RecurringRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
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
        val added = mutableListOf<Entry>()
        for (rule in rules) {
            if (rule.deleted || rule.paused) continue
            var month = rule.startsOn.yearMonth
            while (month <= today.yearMonth) {
                val due = dueOn(rule, month)
                val key = Generation(rule.id, month)
                if (due >= rule.startsOn && due <= today && key !in done) {
                    added += Entry(
                        key.entryId, rule.type, rule.amount, CapturedAt(due.atTime(NOON).toInstant(zone), zone), rule.accountId, null,
                        rule.categoryId, null, rule.note, emptyList(), EntrySource.RECURRING, null, emptyList(), null, now, now, false, key,
                    )
                }
                month = month.plusMonth()
            }
        }
        return added
    }
}
