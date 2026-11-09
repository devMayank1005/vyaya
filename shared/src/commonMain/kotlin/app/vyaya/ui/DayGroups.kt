package app.vyaya.ui

import app.vyaya.core.model.Entry
import app.vyaya.core.model.Money
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** One day on the home screen: its entries (newest first), what went out and what came in. */
data class DayGroup(val day: LocalDate, val label: String, val entries: List<Entry>, val spent: Money, val received: Money)

/** Entries grouped by their own day (each in its own zone, as the month function does), newest day first. */
fun dayGroups(entries: List<Entry>, today: LocalDate): List<DayGroup> =
    entries.groupBy { it.occurredAt.instant.toLocalDateTime(it.occurredAt.timeZone).date }
        .entries.sortedByDescending { it.key } // common code: no toSortedMap outside the JVM
        .map { (day, list) ->
            val sorted = list.sortedByDescending { it.occurredAt.instant }
            DayGroup(
                day, dayLabel(day, today), sorted,
                sorted.filter { it.countsAsSpending }.fold(Money(0)) { s, e -> s + e.amount },
                sorted.filter { it.countsAsIncome }.fold(Money(0)) { s, e -> s + e.amount },
            )
        }

/** "Today", "Yesterday", "Wed 4 Nov", or "Wed 31 Dec 2025" in another year. */
fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
    else -> {
        val weekday = day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        val month = day.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        "$weekday ${day.day} $month" + if (day.year != today.year) " ${day.year}" else ""
    }
}
