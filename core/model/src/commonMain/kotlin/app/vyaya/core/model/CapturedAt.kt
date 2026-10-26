package app.vyaya.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * When an entry happened, together with the zone the phone was in when it was logged (FLAW-004).
 *
 * The month an entry belongs to is always worked out in this zone. A later trip abroad or a zone change
 * on the phone never moves old entries between months.
 */
data class CapturedAt(val instant: Instant, val timeZone: TimeZone) {
    val localDate: LocalDate get() = instant.toLocalDateTime(timeZone).date
}

/** The budget month an entry belongs to, using the entry's own capture zone. */
fun MonthSchedule.monthOf(capturedAt: CapturedAt): BudgetMonth = monthContaining(capturedAt.localDate)
