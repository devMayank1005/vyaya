package app.vyaya.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth

/** Recurring payments and income (rent, subscriptions, salary): listed, paused and resumed. */
@Composable
fun RulesScreen(
    rules: List<RecurringRule>,
    categoryNames: Map<EntityId, String>,
    onAdd: () -> Unit,
    onOpen: (RecurringRule) -> Unit,
    onPause: (RecurringRule) -> Unit,
    missed: suspend (RecurringRule) -> List<YearMonth>,
    onResume: (RecurringRule, addMissed: Boolean) -> Unit,
) {
    var asking by remember { mutableStateOf<Pair<RecurringRule, List<YearMonth>>?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        if (rules.isEmpty()) {
            Text("No recurring payments yet: rent, subscriptions, salary…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rules, key = { it.id.value }) { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.note.ifEmpty { "Recurring" }, style = MaterialTheme.typography.bodyLarge)
                        val sign = if (r.type == EntryType.INCOME) "+" else ""
                        val details = listOfNotNull("$sign${r.amount.format()} · on day ${r.dayOfMonth}", r.categoryId?.let(categoryNames::get), if (r.paused) "Paused" else null)
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.paused) {
                        TextButton({
                            scope.launch {
                                val months = missed(r)
                                if (months.isEmpty()) onResume(r, true) else asking = r to months
                            }
                        }) { Text("Resume") }
                    } else {
                        TextButton({ onPause(r) }) { Text("Pause") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("Add rule") }
    }
    asking?.let { (rule, months) ->
        // FLAW-003 (engine report): the months due while paused are added only if the user says so.
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Resume ${rule.note}") },
            text = { Text("${months.size} month${if (months.size == 1) " was" else "s were"} missed while it was paused (${months.joinToString(", ")}). Add them?") },
            confirmButton = { TextButton({ asking = null; onResume(rule, true) }) { Text("Add them") } },
            dismissButton = { TextButton({ asking = null; onResume(rule, false) }) { Text("Skip them") } },
        )
    }
}

/** What the rule form holds. */
data class RuleInput(val type: EntryType, val amount: String, val day: String, val categoryId: EntityId?, val note: String) {
    val money: Money? get() = Money.parse(amount.trim())?.takeIf { it.paise > 0 }
    val dayOfMonth: Int? get() = day.trim().toIntOrNull()?.takeIf { it in 1..31 }

    fun problem(): String? = when {
        money == null -> "Enter an amount (up to 2 decimals, at most ₹100 crore)"
        dayOfMonth == null -> "A day from 1 to 31 (in shorter months, the last day)"
        note.isBlank() -> "Name it (for example Rent)"
        note.trim().length > 100 -> "Name: at most 100 characters"
        else -> null
    }
}

/** Add or change a recurring rule. An existing rule keeps its type: its past entries must keep matching it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleForm(existing: RecurringRule?, categories: List<Category>, onSave: (RuleInput) -> Boolean, onDelete: () -> Unit, onClose: () -> Unit) {
    var type by remember { mutableStateOf(existing?.type ?: EntryType.EXPENSE) }
    var amount by remember { mutableStateOf(existing?.amount?.let { if (it.paise % 100 == 0L) "${it.paise / 100}" else "${it.paise / 100}.${(it.paise % 100).toString().padStart(2, '0')}" } ?: "") }
    var day by remember { mutableStateOf(existing?.dayOfMonth?.toString() ?: "") }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add recurring" else "Change recurring", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val input = RuleInput(type, amount, day, categoryId, note)
                error = input.problem() ?: if (onSave(input)) null else "This rule can't be saved"
            }) { Text("Save") }
            OutlinedButton(onClose) { Text("Cancel") }
            if (existing != null) TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (existing != null) Text("Its type stays: the entries it already made must keep matching it", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == EntryType.EXPENSE, { type = EntryType.EXPENSE; categoryId = null }, { Text("Expense") }, enabled = existing == null)
            FilterChip(type == EntryType.INCOME, { type = EntryType.INCOME; categoryId = null }, { Text("Income") }, enabled = existing == null)
        }
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount") }, prefix = { Text("₹") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("Day of the month") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(note, { note = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        val kind = if (type == EntryType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.filter { it.kind == kind && !it.deleted }.forEach { c ->
                FilterChip(categoryId == c.id, { categoryId = if (categoryId == c.id) null else c.id }, { Text(c.name) })
            }
        }
    }
}
