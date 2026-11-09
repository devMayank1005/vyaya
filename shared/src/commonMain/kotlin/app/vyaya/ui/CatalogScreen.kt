package app.vyaya.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.launch
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind

/** One row of a list of categories or accounts. */
data class CatalogRow(val key: String, val title: String, val subtitle: String)

/** The user's categories or accounts, to add, rename or delete. */
@Composable
fun CatalogList(title: String, rows: List<CatalogRow>, addLabel: String, onAdd: () -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onBack, Modifier.padding(8.dp)) { Text("‹ Settings") }
            Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.key }) { r ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(r.key) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge)
                        if (r.subtitle.isNotEmpty()) Text(r.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
        ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text(addLabel) })
    }
}

/** Add a category (spending or income), or rename or delete one. Its type stays once made (its entries keep matching). */
@Composable
fun CategoryForm(
    existing: Category?, onSave: suspend (String, CategoryKind) -> String?, deleteNote: suspend () -> String,
    onDelete: () -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: CategoryKind.EXPENSE) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add category" else "Category", style = MaterialTheme.typography.headlineSmall)
        // Catalog report #9: the store checks everything, and its answer is what the form says.
        FormActions(existing?.name, onSave = { scope.launch { error = onSave(name, kind) } }, deleteNote, onDelete, onClose)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it.take(100); error = null }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == CategoryKind.EXPENSE, { kind = CategoryKind.EXPENSE }, { Text("Spending") }, enabled = existing == null)
            FilterChip(kind == CategoryKind.INCOME, { kind = CategoryKind.INCOME }, { Text("Income") }, enabled = existing == null)
        }
    }
}

/** Add an account (bank, card, cash, wallet; the last digits seen in bank SMS), or change or delete one. */
@Composable
fun AccountForm(
    existing: Account?, onSave: suspend (String, AccountKind, String) -> String?, deleteNote: suspend () -> String,
    onDelete: () -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: AccountKind.BANK) }
    var mask by remember { mutableStateOf(existing?.mask ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add account" else "Account", style = MaterialTheme.typography.headlineSmall)
        FormActions(existing?.name, onSave = { scope.launch { error = onSave(name, kind, mask) } }, deleteNote, onDelete, onClose)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it.take(100); error = null }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        OutlinedTextField(mask, { mask = it.filter(Char::isDigit).take(6); error = null }, Modifier.fillMaxWidth(), label = { Text("Last digits") },
            supportingText = { Text("As in your bank's SMS (A/c XX1234): payments it detects go to this account") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    }
}

/** Save, Cancel and, for an existing item ([deleting] is its name), Delete, which asks first (catalog report #5). */
@Composable
private fun FormActions(deleting: String?, onSave: () -> Unit, deleteNote: suspend () -> String, onDelete: () -> Unit, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onSave) { Text("Save") }
        OutlinedButton(onClose) { Text("Cancel") }
        if (deleting != null) TextButton({ scope.launch { confirm = deleteNote() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
    confirm?.let { note ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete $deleting?") },
            text = { Text(note) },
            confirmButton = { TextButton({ confirm = null; onDelete() }) { Text("Yes, delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep it") } },
        )
    }
}

/** What a delete leaves behind, for the confirmation (catalog report #5, #7). */
fun deleteNote(uses: CatalogStore.Uses): String {
    val entries = when (uses.entries) {
        0 -> "No entries use it."
        1 -> "1 entry keeps it."
        else -> "${uses.entries} entries keep it."
    }
    val rules = when (uses.rules) {
        0 -> ""
        1 -> " 1 recurring rule still uses it: change it in Recurring."
        else -> " ${uses.rules} recurring rules still use it: change them in Recurring."
    }
    return "It won't be offered any more. $entries$rules"
}

/** "HDFC ·1234": an account as a chip or a line. */
fun accountLabel(a: Account): String = a.name + (a.mask?.let { " ·$it" } ?: "")
