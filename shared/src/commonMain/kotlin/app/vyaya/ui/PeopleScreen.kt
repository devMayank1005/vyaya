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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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
import app.vyaya.core.engine.Balances
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.Money
import app.vyaya.core.model.Person

/** Lent and borrowed: who owes you, whom you owe. Never part of spending. */
@Composable
fun PeopleScreen(people: List<Person>, balances: Balances, onAddPerson: () -> Unit, onOpen: (Person) -> Unit) {
    val money = LocalMoneyColors.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Others owe you ${balances.owedToYou.format()}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("You owe ${balances.youOwe.format()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (people.isEmpty()) item {
                Text("Add the people you lend to or borrow from; Vyaya keeps the balance with each.", Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(people, key = { it.id.value }) { p ->
                val b = balances.of(p.id)
                Row(Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when { b.paise > 0 -> "owes you ${b.format()}"; b.paise < 0 -> "you owe ${(Money(0) - b).format()}"; else -> "settled" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when { b.paise > 0 -> money.income; b.paise < 0 -> money.overspent; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        ExtendedFloatingActionButton(onClick = onAddPerson, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("Add person") })
    }
}

/** One person: where you stand, what happened, and the actions. */
@Composable
fun PersonScreen(
    person: Person, balance: Money, entries: List<LedgerEntry>,
    onLent: () -> Unit, onBorrowed: () -> Unit, onPaidBack: () -> Unit, onSettleAll: () -> Unit, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ People") }
        }
        Text(person.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                balance.paise > 0 -> "${person.name} owes you ${balance.format()}"
                balance.paise < 0 -> "You owe ${person.name} ${(Money(0) - balance).format()}"
                else -> "All settled with ${person.name}"
            },
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium,
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onLent) { Text("I lent") }
            Button(onBorrowed) { Text("I borrowed") }
        }
        if (balance.paise != 0L) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onPaidBack) { Text("Paid back") }
            OutlinedButton(onSettleAll) { Text("Settle all") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(entries, key = { it.id.value }) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.note.ifEmpty { if (e.direction == LedgerDirection.LENT) "Lent" else "Borrowed" }, style = MaterialTheme.typography.bodyLarge)
                        Text("${e.date}${if (e.settled) " · settled" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text((if (e.direction == LedgerDirection.LENT) "lent " else "borrowed ") + e.amount.format(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** A person's name. */
@Composable
fun PersonForm(onSave: (String) -> Boolean, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add person", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ error = if (name.isBlank()) "Enter a name" else if (onSave(name)) null else "This name can't be saved" }) { Text("Save") }
            OutlinedButton(onClose) { Text("Cancel") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
    }
}

/** An amount and a note: what was lent, borrowed or paid back. */
@Composable
fun LedgerForm(title: String, onSave: (Money, String) -> Unit, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val m = Money.parse(amount.trim())?.takeIf { it.paise > 0 }
                if (m == null) error = "Enter an amount (up to 2 decimals, at most ₹100 crore)" else onSave(m, note)
            }) { Text("Save") }
            OutlinedButton(onClose) { Text("Cancel") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Amount") }, prefix = { Text("₹") },
            textStyle = MaterialTheme.typography.headlineMedium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Note") })
    }
}
