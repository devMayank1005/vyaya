package app.vyaya.android.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.vyaya.android.PasswordCheck
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
) {
    val busy = status is BackupStatus.Working
    var secretText by remember { mutableStateOf("") }
    var secretAgain by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var plainAsked by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup", style = MaterialTheme.typography.headlineSmall)
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
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().toggleable(lockEnabled, role = Role.Switch, onValueChange = onLockEnabled), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask to unlock Vyaya")
                Text("With your fingerprint, face or the phone's PIN, when Vyaya opens or was away for 30 seconds.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(lockEnabled, onCheckedChange = null)
        }
        if (!deviceSecure) {
            Text("This phone has no screen lock, so the app lock has nothing to ask. Set a screen lock in the phone's Settings first.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

