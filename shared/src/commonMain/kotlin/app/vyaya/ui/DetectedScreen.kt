package app.vyaya.ui

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
    "UNVERIFIED_APP" -> "From an app the phone couldn't confirm is the real one"
    else -> "Check this payment"
}

/** Why a message was skipped, in plain words (the Ignored tab). */
fun ignoredWhy(reason: app.vyaya.core.filters.IgnoreReason): String = when (reason) {
    app.vyaya.core.filters.IgnoreReason.UNTRUSTED_APP -> "From an app Vyaya doesn't read payments from"
    app.vyaya.core.filters.IgnoreReason.PERSONAL_NUMBER -> "From a phone number, not a bank: often a scam"
    app.vyaya.core.filters.IgnoreReason.UNKNOWN_SENDER -> "From a sender Vyaya doesn't know"
    app.vyaya.core.filters.IgnoreReason.PROMOTIONAL -> "A promotional message"
    app.vyaya.core.filters.IgnoreReason.OTP -> "A one-time password, not a payment"
    app.vyaya.core.filters.IgnoreReason.FAILED -> "Failed or reversed: no money moved"
    app.vyaya.core.filters.IgnoreReason.NOT_YET_HAPPENED -> "A reminder, or money that hasn't moved yet"
    app.vyaya.core.filters.IgnoreReason.REQUEST -> "Someone asking for money, not a payment"
    app.vyaya.core.filters.IgnoreReason.NOT_A_TRANSACTION -> "Vyaya couldn't read a payment in it"
}

/**
 * The Ignored tab (Phase 3): messages with an amount that weren't logged, newest first, as a summary (the sender, the
 * amount, why, when). "This was a payment" adds it (the form opens with the amount and day); "Not a payment" puts it away.
 */
@Composable
fun IgnoredList(
    items: List<app.vyaya.core.data.IgnoredItem>,
    onWasPayment: (app.vyaya.core.data.IgnoredItem) -> Unit,
    onNotPayment: (app.vyaya.core.data.IgnoredItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Messages with an amount that Vyaya didn't log. Only the sender, amount and reason are kept, for 30 days; never the message.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (items.isEmpty()) item { Text("Nothing skipped in the last 30 days", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
        items(items, key = { it.id }) { item ->
            androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val from = app.vyaya.core.parser.UpiApps.default.apps.firstOrNull { it.packageName == item.sender }?.name ?: item.sender
                    Text("${item.amount.format()} · $from", style = MaterialTheme.typography.titleMedium)
                    Text(ignoredWhy(item.reason), style = MaterialTheme.typography.bodyMedium)
                    val local = item.receivedAt.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    Text("${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedButton({ onWasPayment(item) }) { Text("This was a payment") }
                        androidx.compose.material3.TextButton({ onNotPayment(item) }) { Text("Not a payment") }
                    }
                }
            }
        }
    }
}
