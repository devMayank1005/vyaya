package app.vyaya.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vyaya.core.data.DetectedItem
import app.vyaya.core.data.DetectedKind
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntryType
import kotlinx.datetime.toLocalDateTime

/** Payments capture found: added ones to undo, review ones to add or dismiss. */
@Composable
fun DetectedScreen(items: List<DetectedItem>, onUndo: (String) -> Unit, onAdd: (String) -> Unit, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Text("Nothing detected yet", modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { item -> DetectedCard(item, onUndo, onAdd, onDismiss) }
    }
}

@Composable
private fun DetectedCard(item: DetectedItem, onUndo: (String) -> Unit, onAdd: (String) -> Unit, onDismiss: (String) -> Unit) {
    val e = item.entry
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(amountText(e), style = MaterialTheme.typography.titleLarge)
                    Text(e.payee ?: "No payee", style = MaterialTheme.typography.bodyMedium)
                    Text(whenText(e), style = MaterialTheme.typography.bodySmall)
                }
                if (item.kind == DetectedKind.ADDED) TextButton({ onUndo(item.id) }) { Text("Undo") }
            }
            if (item.kind == DetectedKind.TRANSFER) Text("Between your own accounts", style = MaterialTheme.typography.bodySmall)
            if (item.kind == DetectedKind.REVIEW) {
                Text(reasonText(item.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (item.possibleDuplicateOf != null) Text("May be a payment you already have", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 8.dp)) {
                    Button({ onAdd(item.id) }) { Text("Add") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton({ onDismiss(item.id) }) { Text("Not mine") }
                }
            }
        }
    }
}

internal fun amountText(e: Entry): String = (if (e.type == EntryType.INCOME) "+" else "") + e.amount.format()

internal fun whenText(e: Entry): String {
    val t = e.occurredAt.instant.toLocalDateTime(e.occurredAt.timeZone)
    val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val hour = (t.hour % 12).let { if (it == 0) 12 else it }
    return "${t.day} $month, $hour:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "am" else "pm"}"
}

/** Why an item waits for the user (the filters' review reasons). */
internal fun reasonText(reason: String?): String = when (reason) {
    "UNKNOWN_HEADER" -> "From a sender not on the bank list"
    "BARE_HEADER" -> "Sender ID without the operator prefix"
    "SAVED_CONTACT" -> "From a saved contact, not a bank"
    else -> "Check this payment"
}
