package app.vyaya.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import app.vyaya.core.data.MonthView
import app.vyaya.core.model.BudgetMonth
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry

/** This month: what's left (in − out), and its entries. [view] is null while it loads. */
@Composable
fun HomeScreen(
    view: MonthView?,
    categoryNames: Map<EntityId, String>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onPrevious, Modifier.semantics { contentDescription = "Previous month" }) { Text("‹") }
                Text(view?.let { monthLabel(it.month) } ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onNext, Modifier.semantics { contentDescription = "Next month" }) { Text("›") }
            }
            if (view == null) return@Column
            val s = view.summary
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Left", style = MaterialTheme.typography.labelLarge)
                Text(s.left.format(), style = MaterialTheme.typography.displaySmall,
                    color = if (s.left.paise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("In ${s.income.format()}", style = MaterialTheme.typography.bodyMedium)
                    Text("Out ${s.spent.format()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            if (view.entries.isEmpty()) {
                Text("No entries this month", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(view.entries, key = { it.id.value }) { e -> EntryRow(e, e.categoryId?.let(categoryNames::get), onOpen) }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("Add") }
    }
}

@Composable
private fun EntryRow(e: Entry, category: String?, onOpen: (Entry) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.payee ?: category ?: e.note.ifEmpty { "Entry" }, style = MaterialTheme.typography.bodyLarge)
            Text(listOfNotNull(category, whenText(e)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        Text(amountText(e), style = MaterialTheme.typography.bodyLarge)
    }
}

/** "November 2026" for calendar months; "25 Oct – 24 Nov" when the month starts on another day. */
internal fun monthLabel(m: BudgetMonth): String {
    fun name(month: kotlinx.datetime.Month) = month.name.lowercase().replaceFirstChar { it.uppercase() }
    return if (m.start.day == 1 && m.endExclusive.day == 1) "${name(m.start.month)} ${m.start.year}"
    else "${m.start.day} ${name(m.start.month).take(3)} – ${m.lastDay.day} ${name(m.lastDay.month).take(3)}"
}
