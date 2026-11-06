package app.vyaya.android.ui

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
        existing?.copy(type = type, amount = money, occurredAt = occurredAt, categoryId = categoryId, payee = payee, note = note.trim(), updatedAt = now)
            ?: Entry(EntityId.random(), type, money, occurredAt, null, null, categoryId, payee, note.trim(), emptyList(), EntrySource.MANUAL, null,
                emptyList(), null, now, now, false)
    }.getOrNull()
}
