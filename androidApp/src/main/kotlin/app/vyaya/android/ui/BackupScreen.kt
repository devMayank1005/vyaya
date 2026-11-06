package app.vyaya.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
}

/** Export a backup file; restore one by merging (newer wins, your settings stay) or replacing everything. */
@Composable
fun BackupScreen(status: BackupStatus, onExport: () -> Unit, onRestore: (RestoreMode) -> Unit, onRestoreAnyway: () -> Unit) {
    val busy = status is BackupStatus.Working
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
        }
        Text("A backup is a file you keep (on Drive, a computer, another phone). Nothing is uploaded by Vyaya.",
            style = MaterialTheme.typography.bodyMedium)
        Button(onExport, enabled = !busy) { Text("Export backup") }
        Text("Restore", style = MaterialTheme.typography.titleMedium)
        Text("Merge keeps what's on this phone; where both have the same entry, the newer change wins.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ onRestore(RestoreMode.MERGE) }, enabled = !busy) { Text("Restore: merge") }
        Text("Replace removes everything on this phone and puts the backup in its place.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ onRestore(RestoreMode.REPLACE) }, enabled = !busy) { Text("Restore: replace everything") }
    }
}
