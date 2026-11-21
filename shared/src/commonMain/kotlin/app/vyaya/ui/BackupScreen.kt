package app.vyaya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.vyaya.shared.PasswordCheck
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.RestoreMode

/** Where a backup or restore is. */
sealed interface BackupStatus {
    data object Idle : BackupStatus
    data class Working(val what: String) : BackupStatus
    data class Done(val message: String) : BackupStatus
    /** Nothing was changed; [failure] says why (and whether "Restore anyway" may be offered). */
    data class Refused(val failure: DecodeResult.Failure) : BackupStatus
    /** The picked backup is password-protected; [problem] when the last password didn't open it. */
    data class NeedsPassword(val problem: String?) : BackupStatus
}

/**
 * Export a backup file, protected by a password unless the user says no twice (SEC-002); restore one by merging
 * (newer wins, your settings stay) or replacing everything.
 */
@Composable
fun BackupScreen(
    status: BackupStatus,
    onExport: (CharArray?) -> Unit,
    onRestore: (RestoreMode) -> Unit,
    onRestoreAnyway: () -> Unit,
    onPassword: (CharArray) -> Unit = {},
    lockEnabled: Boolean = false,
    onLockEnabled: (Boolean) -> Unit = {},
    deviceSecure: Boolean = true,
    onCategories: () -> Unit = {},
    onAccounts: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    onPayees: () -> Unit = {},
    onBudgets: () -> Unit = {},
    /** The current month's start day, a change to come (from, day), and how to set it (safe-to-spend FLAW-002). */
    startDay: Int? = null,
    nextStart: Pair<kotlinx.datetime.LocalDate, Int>? = null,
    onStartDay: (Int) -> Unit = {},
    /** Phase 3: whether the home-screen widget shows safe to spend (off by default; never while the lock is on). */
    widgetAmounts: Boolean = false,
    onWidgetAmounts: (Boolean) -> Unit = {},
) {
    val busy = status is BackupStatus.Working
    var secretText by remember { mutableStateOf("") }
    var secretAgain by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var plainAsked by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup and privacy", style = MaterialTheme.typography.headlineSmall)
        // The result first, where it is seen without scrolling.
        when (status) {
            BackupStatus.Idle -> {}
            is BackupStatus.Working -> { CircularProgressIndicator(); Text(status.what) }
            is BackupStatus.Done -> Text(status.message, color = MaterialTheme.colorScheme.primary)
            is BackupStatus.Refused -> {
                Text("Nothing was changed:", color = MaterialTheme.colorScheme.error)
                status.failure.problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (status.failure.clockBehind) Button(onRestoreAnyway) { Text("Restore anyway") }
            }
            is BackupStatus.NeedsPassword -> {
                Text("This backup is protected by a password.", style = MaterialTheme.typography.bodyMedium)
                status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(restorePassword, { restorePassword = it }, Modifier.fillMaxWidth(), label = { Text("Backup password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button({ onPassword(restorePassword.toCharArray()); restorePassword = "" }, enabled = restorePassword.isNotEmpty()) {
                    Text("Restore with this password")
                }
            }
        }
        Text("A backup is a file you keep (on Drive, a computer, another phone). Nothing is uploaded by Vyaya.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(secretText, { secretText = it; problem = null; plainAsked = false }, Modifier.fillMaxWidth(), label = { Text("Password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(secretAgain, { secretAgain = it; problem = null }, Modifier.fillMaxWidth(), label = { Text("Repeat the password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = problem != null, supportingText = problem?.let { { Text(it) } })
        Text("There is no way to recover a forgotten password: without it the backup can't be opened. Keep it somewhere safe.",
            style = MaterialTheme.typography.bodySmall)
        TextButton({ PasswordCheck.passphrase().let { secretText = it; secretAgain = it; problem = null; shown = it } }) { Text("Suggest a passphrase") }
        shown?.takeIf { it == secretText }?.let { Text("Write it down: $it", style = MaterialTheme.typography.bodyMedium) }
        Button({
            when {
                secretText.isEmpty() -> plainAsked = true
                PasswordCheck.problem(secretText) != null -> problem = PasswordCheck.problem(secretText)
                secretText != secretAgain -> problem = "The passwords don't match"
                else -> { onExport(secretText.toCharArray()); secretText = ""; secretAgain = "" }
            }
        }, enabled = !busy) { Text("Export backup") }
        if (plainAsked) {
            Text("Without a password anyone who gets the file can read it: every payee, amount and note.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ plainAsked = false; onExport(null) }, enabled = !busy) { Text("Export without a password") }
        }
        Text("Restore", style = MaterialTheme.typography.titleMedium)
        Text("Merge keeps what's on this phone; where both have the same entry, the newer change wins.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ onRestore(RestoreMode.MERGE) }, enabled = !busy) { Text("Restore: merge") }
        Text("Replace removes everything on this phone and puts the backup in its place.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ onRestore(RestoreMode.REPLACE) }, enabled = !busy) { Text("Restore: replace everything") }
        if (startDay != null) {
            var asking by remember { mutableStateOf(false) }
            var dayText by remember { mutableStateOf("") }
            var dayProblem by remember { mutableStateOf<String?>(null) }
            Text("Your month", style = MaterialTheme.typography.titleMedium)
            Text("Your month starts on the ${ordinal(startDay)}" +
                (nextStart?.let { (from, d) -> ". From $from, on the ${ordinal(d)}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text("Start it on your salary day, so each month begins with its money. A change starts with your next month; " +
                "the month in between is shorter or longer, so nothing is counted twice.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ asking = true; dayText = ""; dayProblem = null }) { Text("Change start day") }
            if (asking) AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text("Month start day") },
                text = {
                    Column {
                        OutlinedTextField(dayText, { dayText = it.filter(Char::isDigit).take(2); dayProblem = null }, label = { Text("Start day (1–31)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            singleLine = true)
                        dayProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton({
                        val d = dayText.toIntOrNull()
                        if (d == null || d !in 1..31) dayProblem = "A day from 1 to 31 (in shorter months, the last day)"
                        else { onStartDay(d); asking = false }
                    }) { Text("Save start day") }
                },
                dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
            )
        }
        Text("Spreadsheet", style = MaterialTheme.typography.titleMedium)
        Text("All your entries as a CSV file for Excel or Google Sheets. No password: anyone with the file can read it. " +
            "It can't be restored; use a backup for that.", style = MaterialTheme.typography.bodySmall)
        var csvAsk by remember { mutableStateOf(false) }
        // csv report #4: with no screen lock the phone can't confirm it's you, so say plainly what leaves.
        OutlinedButton({ if (deviceSecure) onExportCsv() else csvAsk = true }, enabled = !busy) { Text("Export spreadsheet (CSV)") }
        if (csvAsk) AlertDialog(
            onDismissRequest = { csvAsk = false },
            title = { Text("Export without a check?") },
            text = {
                Text("This phone has no screen lock, so anyone holding it can make this file. It lists every payee, amount and " +
                    "UPI reference in plain text, with no password.")
            },
            confirmButton = { TextButton({ csvAsk = false; onExportCsv() }) { Text("Export anyway") } },
            dismissButton = { TextButton({ csvAsk = false }) { Text("Cancel") } },
        )
        Text("Your lists", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onCategories) { Text("Categories") }
            OutlinedButton(onAccounts) { Text("Accounts") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onBudgets) { Text("Budgets") }
            OutlinedButton(onPayees) { Text("Remembered payees") }
        }
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().toggleable(lockEnabled, role = Role.Switch, onValueChange = onLockEnabled), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask to unlock Vyaya")
                Text("With your fingerprint, face or the phone's PIN, when Vyaya opens or was away for 30 seconds.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(lockEnabled, onCheckedChange = null)
        }
        // Anyone who sees the home screen sees the widget, so the lock keeps amounts off it.
        Row(Modifier.fillMaxWidth().toggleable(widgetAmounts && !lockEnabled, enabled = !lockEnabled, role = Role.Switch, onValueChange = onWidgetAmounts),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show amounts on the home-screen widget")
                Text(if (lockEnabled) "Hidden while the app lock is on. The widget shows only an Add button."
                    else "Off: the widget shows only an Add button. On: it also shows what's safe to spend today, to anyone who sees your home screen.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(widgetAmounts && !lockEnabled, onCheckedChange = null, enabled = !lockEnabled)
        }
        if (!deviceSecure) {
            Text("This phone has no screen lock, so the app lock has nothing to ask. Set a screen lock in the phone's Settings first.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

