package app.vyaya.core.backup

import app.vyaya.core.model.MerchantRule
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlin.time.Instant

/**
 * The phone's clock was ahead for a while and is right again (SIM-001, SIM-002 in the core-engine report).
 *
 * Whatever was stamped while it was ahead has change times in the future, and a backup refuses those (SEC-005)
 * until real time catches up, which can be months. Recurring catch-up may also have made months that haven't
 * started. The app runs [repair] when it opens and [needed] says so:
 * - change times after now become now (the change itself stays);
 * - generated entries for months after this one go, deleted and edited ones too, because any key for a future
 *   month blocks backups until that month. Catch-up makes them again, with the same id, when they come due.
 */
object ClockRepair {

    fun needed(s: Snapshot, now: Instant): Boolean = changeTimes(s).any { it > now } || futureKeys(s, now)

    fun repair(s: Snapshot, now: Instant): Snapshot {
        if (!needed(s, now)) return s
        fun Instant.notAfterNow() = if (this > now) now else this
        val thisMonth = now.toLocalDateTime(s.settings.homeTimeZone).date.yearMonth
        return s.copy(
            accounts = s.accounts.map { it.copy(updatedAt = it.updatedAt.notAfterNow()) },
            categories = s.categories.map { it.copy(updatedAt = it.updatedAt.notAfterNow()) },
            entries = s.entries.filter { e -> e.generatedBy.let { it == null || it.month <= thisMonth } }
                .map { it.copy(createdAt = it.createdAt.notAfterNow(), updatedAt = it.updatedAt.notAfterNow()) },
            recurring = s.recurring.map { it.copy(updatedAt = it.updatedAt.notAfterNow()) },
            people = s.people.map { it.copy(updatedAt = it.updatedAt.notAfterNow()) },
            ledger = s.ledger.map { it.copy(updatedAt = it.updatedAt.notAfterNow()) },
            budgets = s.budgets.map { it.copy(updatedAt = it.updatedAt.notAfterNow()) },
            merchantRules = s.merchantRules.map { MerchantRule(it.id, it.payee, it.categoryId, it.updatedAt.notAfterNow(), it.deleted) },
        )
    }

    private fun futureKeys(s: Snapshot, now: Instant): Boolean {
        val thisMonth = now.toLocalDateTime(s.settings.homeTimeZone).date.yearMonth
        return s.entries.any { e -> e.generatedBy.let { it != null && it.month > thisMonth } }
    }

    private fun changeTimes(s: Snapshot): Sequence<Instant> = sequence {
        s.accounts.forEach { yield(it.updatedAt) }
        s.categories.forEach { yield(it.updatedAt) }
        s.entries.forEach { yield(it.createdAt); yield(it.updatedAt) }
        s.recurring.forEach { yield(it.updatedAt) }
        s.people.forEach { yield(it.updatedAt) }
        s.ledger.forEach { yield(it.updatedAt) }
        s.budgets.forEach { yield(it.updatedAt) }
        s.merchantRules.forEach { yield(it.updatedAt) }
    }
}
