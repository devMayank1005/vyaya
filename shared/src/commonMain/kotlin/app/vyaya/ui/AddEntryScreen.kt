package app.vyaya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Add an entry by hand, or edit one ([existing]). [onSave] gets what was typed and says whether it was saved (false:
 * the amount or a field isn't valid). Expense or income; a category of that kind; payee, note and the day.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    existing: Entry?,
    categories: List<Category>,
    today: LocalDate,
    onSave: (EntryInput) -> Boolean,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var type by remember { mutableStateOf(if (existing?.type == EntryType.INCOME) EntryType.INCOME else EntryType.EXPENSE) }
    var amount by remember { mutableStateOf(existing?.amount?.let(::plainAmount) ?: "") }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var payee by remember { mutableStateOf(existing?.payee ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var date by remember { mutableStateOf(existing?.occurredAt?.let { it.instant.toLocalDateTime(it.timeZone).date } ?: today) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add entry" else "Edit entry", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val input = EntryInput(type, amount, categoryId, payee, note, date)
                error = input.problem(existing, TimeZone.currentSystemDefault()) ?: if (onSave(input)) null else "This entry can't be saved"
            }) { Text("Save") }
            OutlinedButton(onClose) { Text("Cancel") }
            if (existing != null) TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        val fromRule = existing?.generatedBy != null
        if (fromRule) Text("From a recurring rule: its type stays as the rule's", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == EntryType.EXPENSE, { type = EntryType.EXPENSE; categoryId = null }, { Text("Expense") }, enabled = !fromRule)
            FilterChip(type == EntryType.INCOME, { type = EntryType.INCOME; categoryId = null }, { Text("Income") }, enabled = !fromRule)
        }
        OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Amount") },
            prefix = { Text("₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            isError = error != null, supportingText = error?.let { { Text(it) } })
        val kind = if (type == EntryType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.filter { it.kind == kind && !it.deleted }.forEach { c ->
                FilterChip(categoryId == c.id, { categoryId = if (categoryId == c.id) null else c.id }, { Text(c.name) })
            }
        }
        // FLAW-005: the limits stop the typing, rather than a refusal at Save.
        OutlinedTextField(payee, { payee = it.take(EntryInput.MAX_PAYEE) }, Modifier.fillMaxWidth(), label = { Text("Payee") }, singleLine = true)
        OutlinedTextField(note, { note = it.take(EntryInput.MAX_NOTE) }, Modifier.fillMaxWidth(), label = { Text("Note") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(date == today, { date = today }, { Text("Today") })
            val yesterday = today.minus(1, DateTimeUnit.DAY)
            FilterChip(date == yesterday, { date = yesterday }, { Text("Yesterday") })
            FilterChip(date != today && date != yesterday, { picking = true }, { Text(if (date != today && date != yesterday) date.toString() else "Pick date") })
        }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton({
                state.selectedDateMillis?.let { date = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
                picking = false
            }) { Text("OK") }
        }) { DatePicker(state) }
    }
}

/** An amount as the user would type it: `250.5` → "250.50", `120` → "120". */
private fun plainAmount(m: Money): String = if (m.paise % 100 == 0L) "${m.paise / 100}" else "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"
