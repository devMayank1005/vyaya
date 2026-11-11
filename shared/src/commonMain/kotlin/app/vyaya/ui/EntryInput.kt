package app.vyaya.ui

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What the add/edit form holds. [amount] is as typed; [date] is the day it happened. */
data class EntryInput(
    val type: EntryType,
    val amount: String,
    val categoryId: EntityId?,
    val payee: String,
    val note: String,
    val date: LocalDate,
    val accountId: EntityId? = null,
    /** Merchant memory: also put this payee's later detected payments in [categoryId]. */
    val alwaysForPayee: Boolean = false,
) {
    /**
     * The entry to save, or null when the amount isn't one (empty, zero, more than 2 decimals, too large) or a field is
     * too long. A new entry is typed in (MANUAL) and happens now if [date] is today, at noon otherwise. An edit keeps
     * the entry's id, source and creation time, and its time of day when the day is unchanged.
     */
    fun toEntry(existing: Entry?, now: Instant, zone: TimeZone): Entry? = runCatching {
        val money = Money.parse(amount.trim()) ?: return null
        val sameDay = existing != null && existing.occurredAt.instant.toLocalDateTime(existing.occurredAt.timeZone).date == date
        val occurredAt = when {
            sameDay -> existing!!.occurredAt
            date == now.toLocalDateTime(zone).date -> CapturedAt(now, zone)
            else -> CapturedAt(date.atTime(LocalTime(12, 0)).toInstant(zone), zone)
        }
        val payee = payee.trim().ifEmpty { null }
        // MVP DATA-001: an entry a rule made keeps the rule's type, or no backup would be accepted again.
        val type = if (existing?.generatedBy != null) existing.type else type
        existing?.copy(type = type, amount = money, occurredAt = occurredAt, accountId = accountId, categoryId = categoryId, payee = payee, note = note.trim(), updatedAt = now)
            ?: Entry(EntityId.random(), type, money, occurredAt, accountId, null, categoryId, payee, note.trim(), emptyList(), EntrySource.MANUAL, null,
                emptyList(), null, now, now, false)
    }.getOrNull()

    /** What would stop [toEntry] from saving, said plainly; null when it saves (FLAW-004, FLAW-005 of the MVP report). */
    fun problem(existing: Entry?, zone: TimeZone): String? {
        val money = Money.parse(amount.trim())
        return when {
            money == null || money.paise <= 0 -> "Enter an amount (up to 2 decimals, at most ₹100 crore)"
            payee.trim().length > MAX_PAYEE -> "Payee: at most $MAX_PAYEE characters"
            note.trim().length > MAX_NOTE -> "Note: at most $MAX_NOTE characters"
            existing?.generatedBy != null && kotlin.math.abs(monthIndex(date) - existing.generatedBy!!.month.let { it.year * 12 + it.month.ordinal }) > 1 ->
                "A recurring entry stays in its month or the month either side (${existing.generatedBy!!.month})"
            else -> null
        }
    }

    private fun monthIndex(d: LocalDate) = d.year * 12 + d.month.ordinal

    companion object {
        const val MAX_PAYEE = 100
        const val MAX_NOTE = 500
    }
}
