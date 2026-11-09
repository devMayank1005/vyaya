package app.vyaya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vyaya.core.data.MonthView
import app.vyaya.core.data.SavingsSummary
import app.vyaya.core.engine.MonthEnd
import app.vyaya.core.model.BudgetMonth
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntryType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/** This month: what's left (in − out) at the top, then the month's entries day by day. [view] is null while it loads. */
@Composable
fun HomeScreen(
    view: MonthView?,
    categoryNames: Map<EntityId, String>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
    /** The month's result once it has ended (MonthEnd.of: calculated, never stored). */
    monthEnd: MonthEnd? = null,
    /** Saved so far over the finished months, shown on the current month. */
    savings: SavingsSummary? = null,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onPrevious, Modifier.semantics { contentDescription = "Previous month" }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                Text(view?.let { monthLabel(it.month) } ?: "", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                TextButton(onNext, Modifier.semantics { contentDescription = "Next month" }) { Text("›", style = MaterialTheme.typography.titleLarge) }
            }
            if (view == null) return@Column
            LazyColumn(Modifier.fillMaxSize()) {
                item { SummaryCard(view, monthEnd, savings) }
                if (view.entries.isEmpty()) {
                    item { EmptyMonth() }
                } else {
                    val day = today ?: view.entries.first().occurredAt.let { it.instant.toLocalDateTime(it.timeZone).date }
                    dayGroups(view.entries, day).forEach { g ->
                        item(key = "day-${g.day}") { DayHeader(g) }
                        items(g.entries, key = { it.id.value }) { e -> EntryRow(e, e.categoryId?.let(categoryNames::get), onOpen) }
                    }
                }
                item { Spacer(Modifier.height(88.dp)) } // room for the Add button
            }
        }
        ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("Add") })
    }
}

@Composable
private fun SummaryCard(view: MonthView, monthEnd: MonthEnd?, savings: SavingsSummary?) {
    val s = view.summary
    val money = LocalMoneyColors.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text("Left", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(s.left.format(), style = MaterialTheme.typography.displaySmall,
                color = if (s.left.paise < 0) money.overspent else MaterialTheme.colorScheme.onPrimaryContainer)
            if (s.income.paise > 0) {
                val used = (s.spent.paise.toDouble() / s.income.paise).coerceIn(0.0, 1.0).toFloat()
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator({ used }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = if (s.left.paise < 0) money.overspent else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface)
                Text("${(used * 100).toInt()}% of what came in is spent", Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            monthEnd?.let { end ->
                // The month has ended: what it saved, or by how much it went over (plan.md invariant 3).
                val text = if (end.overspent.paise > 0) "Overspent ${end.overspent.format()}" else "Saved ${end.saved.format()}"
                Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium,
                    color = if (end.overspent.paise > 0) money.overspent else money.income)
            }
            if (monthEnd == null && savings != null && savings.months > 0) {
                Text("Saved so far ${savings.saved.format()} over ${savings.months} month${if (savings.months == 1) "" else "s"}",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("In ${s.income.format()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Out ${s.spent.format()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun EmptyMonth() {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("No entries this month", style = MaterialTheme.typography.titleMedium)
        Text("Tap Add to note a payment, or let Vyaya pick them up from your bank SMS and UPI apps (Android).",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayHeader(g: DayGroup) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(g.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val totals = listOfNotNull(
            g.received.takeIf { it.paise > 0 }?.let { "+${it.format()}" },
            g.spent.takeIf { it.paise > 0 }?.let { "−${it.format()}" }, // a real minus sign
        )
        Text(totals.joinToString("  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryRow(e: Entry, category: String?, onOpen: (Entry) -> Unit) {
    val title = e.payee ?: category ?: e.note.ifEmpty { "Entry" }
    Row(Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text((category ?: title).take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(listOfNotNull(category, timeText(e)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(amountText(e), style = MaterialTheme.typography.titleMedium,
            color = if (e.type == EntryType.INCOME) LocalMoneyColors.current.income else MaterialTheme.colorScheme.onSurface)
    }
}

/** "2:45 pm", in the entry's own zone. */
internal fun timeText(e: Entry): String {
    val t = e.occurredAt.instant.toLocalDateTime(e.occurredAt.timeZone)
    val hour = (t.hour % 12).let { if (it == 0) 12 else it }
    return "$hour:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "am" else "pm"}"
}

/** "November 2026" for calendar months; "25 Oct – 24 Nov" when the month starts on another day. */
internal fun monthLabel(m: BudgetMonth): String {
    fun name(month: kotlinx.datetime.Month) = month.name.lowercase().replaceFirstChar { it.uppercase() }
    return if (m.start.day == 1 && m.endExclusive.day == 1) "${name(m.start.month)} ${m.start.year}"
    else "${m.start.day} ${name(m.start.month).take(3)} – ${m.lastDay.day} ${name(m.lastDay.month).take(3)}"
}
