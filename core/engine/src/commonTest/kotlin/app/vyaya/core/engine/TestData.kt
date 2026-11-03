package app.vyaya.core.engine

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Split
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

val IST = TimeZone.of("Asia/Kolkata")
val T = Instant.parse("2026-10-27T10:00:00Z")
val FOOD = EntityId.random()
val RENT = EntityId.random()

fun rupees(r: Long) = Money(r * 100)

/** An entry at [at] (UTC instant) in Kolkata time. */
fun entry(
    type: EntryType,
    amount: Money,
    at: String,
    category: EntityId? = FOOD,
    splits: List<Split> = emptyList(),
    deleted: Boolean = false,
    zone: TimeZone = IST,
) = Entry(
    EntityId.random(), type, amount, CapturedAt(Instant.parse(at), zone), null, null, category, null, "", emptyList(),
    EntrySource.MANUAL, null, splits, null, T, T, deleted,
)
