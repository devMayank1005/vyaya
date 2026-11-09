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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
fun CategoryForm(existing: Category?, onSave: (String, CategoryKind) -> Boolean, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: CategoryKind.EXPENSE) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add category" else "Category", style = MaterialTheme.typography.headlineSmall)
        FormActions(existing != null, onSave = { error = if (name.isBlank()) "Enter a name" else if (onSave(name, kind)) null else "A category of this type already has that name" },
            onDelete = onDelete, onClose = onClose)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == CategoryKind.EXPENSE, { kind = CategoryKind.EXPENSE }, { Text("Spending") }, enabled = existing == null)
            FilterChip(kind == CategoryKind.INCOME, { kind = CategoryKind.INCOME }, { Text("Income") }, enabled = existing == null)
        }
    }
}

/** Add an account (bank, card, cash, wallet; the last digits seen in bank SMS), or rename or delete one. */
@Composable
fun AccountForm(existing: Account?, onSave: (String, AccountKind, String) -> Boolean, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: AccountKind.BANK) }
    var mask by remember { mutableStateOf(existing?.mask ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add account" else "Account", style = MaterialTheme.typography.headlineSmall)
        FormActions(existing != null, onSave = {
            error = when {
                name.isBlank() -> "Enter a name"
                mask.isNotEmpty() && mask.length !in 3..6 -> "Last digits: 3 to 6 digits"
                onSave(name, kind, mask) -> null
                else -> "An account already has that name"
            }
        }, onDelete = onDelete, onClose = onClose)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        OutlinedTextField(mask, { mask = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Last digits") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    }
}

@Composable
private fun FormActions(canDelete: Boolean, onSave: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onSave) { Text("Save") }
        OutlinedButton(onClose) { Text("Cancel") }
        if (canDelete) TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}

/** "HDFC ·1234": an account as a chip or a line. */
fun accountLabel(a: Account): String = a.name + (a.mask?.let { " ·$it" } ?: "")
