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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
fun DetectedScreen(
    items: List<DetectedItem>, onUndo: (String) -> Unit, onAdd: (String) -> Unit, onDismiss: (String) -> Unit, modifier: Modifier = Modifier,
    /** Branch review #3: "Is this your Rent?" answered yes (the payment is that bill) or no (the bill is made as usual). */
    onYesBill: (String) -> Unit = {},
    onNoBill: (String) -> Unit = {},
) {
    if (items.isEmpty()) {
        Text("Nothing detected yet", modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { item -> if (item.kind == DetectedKind.BILL) BillCard(item, onYesBill, onNoBill) else DetectedCard(item, onUndo, onAdd, onDismiss) }
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

/** A recurring bill whose month may have been paid by a payment already in another category: the user decides. */
@Composable
private fun BillCard(item: DetectedItem, onYes: (String) -> Unit, onNo: (String) -> Unit) {
    val bill = item.entry.note.ifEmpty { "bill" }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Is this your $bill?", style = MaterialTheme.typography.titleMedium)
            Text("A payment of ${item.entry.amount.format()} came a few days from when $bill is due, but it's in another category. " +
                "Yes: it counts as this month's $bill. No: $bill is added as usual.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp)) {
                Button({ onYes(item.id) }) { Text("Yes, it's $bill") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton({ onNo(item.id) }) { Text("No, a different payment") }
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

/** Promotions and codes: folded away in the Ignored tab (FLAW-003), and not counted in its title. */
fun foldedAway(reason: app.vyaya.core.filters.IgnoreReason): Boolean =
    reason == app.vyaya.core.filters.IgnoreReason.PROMOTIONAL || reason == app.vyaya.core.filters.IgnoreReason.OTP

/**
 * The Ignored tab (Phase 3): messages with an amount that weren't logged, newest first, as a summary (the sender, the
 * amount, why, when, in the home [zone]). Promotions and codes are folded away (FLAW-003). "This was a payment" adds
 * it; from a phone number or an unverified app there's a warning instead (SEC-001). "Not a payment" puts it away.
 */
@Composable
fun IgnoredList(
    items: List<app.vyaya.core.data.IgnoredItem>,
    onWasPayment: (app.vyaya.core.data.IgnoredItem) -> Unit,
    onNotPayment: (app.vyaya.core.data.IgnoredItem) -> Unit,
    zone: kotlinx.datetime.TimeZone,
    modifier: Modifier = Modifier,
) {
    var showFolded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val (folded, main) = items.partition { foldedAway(it.reason) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Messages with an amount that Vyaya didn't log. Only the sender, amount and reason are kept, for 30 days; never the message.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (main.isEmpty()) item { Text("Nothing skipped in the last 30 days", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
        items(main, key = { it.id }) { IgnoredCard(it, onWasPayment, onNotPayment, zone) }
        if (folded.isNotEmpty()) item(key = "folded") {
            TextButton({ showFolded = !showFolded }, Modifier.padding(horizontal = 8.dp)) {
                Text(if (showFolded) "Hide promotions and codes" else "Show promotions and codes (${folded.size})")
            }
        }
        if (showFolded) items(folded, key = { it.id }) { IgnoredCard(it, onWasPayment, onNotPayment, zone) }
    }
}

@Composable
private fun IgnoredCard(
    item: app.vyaya.core.data.IgnoredItem,
    onWasPayment: (app.vyaya.core.data.IgnoredItem) -> Unit,
    onNotPayment: (app.vyaya.core.data.IgnoredItem) -> Unit,
    zone: kotlinx.datetime.TimeZone,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val from = app.vyaya.core.parser.UpiApps.default.apps.firstOrNull { it.packageName == item.sender }?.name ?: item.sender
            Text("${item.amount.format()} · $from", style = MaterialTheme.typography.titleMedium)
            Text(ignoredWhy(item.reason), style = MaterialTheme.typography.bodyMedium)
            val local = item.receivedAt.toLocalDateTime(zone)
            Text("${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val risky = item.reason == app.vyaya.core.filters.IgnoreReason.PERSONAL_NUMBER || item.reason == app.vyaya.core.filters.IgnoreReason.UNTRUSTED_APP
            if (risky) {
                // SEC-001: a scam's amount isn't one tap from the user's books.
                Text("Banks never send from phone numbers, and this app couldn't be verified as the real one: don't add it unless " +
                    "you're sure. You can still add a payment by hand from Home.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!risky) OutlinedButton({ onWasPayment(item) }) { Text("This was a payment") }
                TextButton({ onNotPayment(item) }) { Text("Not a payment") }
            }
        }
    }
}
