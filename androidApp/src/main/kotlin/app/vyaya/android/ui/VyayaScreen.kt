package app.vyaya.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vyaya.core.data.DetectedInbox
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** The first screen: what's still to allow, then the Detected inbox, kept up to date as payments arrive. */
@Composable
fun VyayaScreen(
    inbox: DetectedInbox,
    setup: Setup,
    clock: () -> Instant,
    onNotificationAccess: () -> Unit,
    onSms: () -> Unit,
    onImport: () -> Unit,
    onBattery: () -> Unit = {},
) {
    val items = remember(inbox) { inbox.changes() }.collectAsState(emptyList()).value
    val scope = rememberCoroutineScope()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("Detected", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            SetupCard(setup, onNotificationAccess, onSms, onImport, onBattery = onBattery)
            DetectedScreen(
                items,
                onUndo = { id -> scope.launch { inbox.undo(id, clock()) } },
                onAdd = { id -> scope.launch { inbox.confirm(id, clock()) } },
                onDismiss = { id -> scope.launch { inbox.dismiss(id) } },
            )
        }
    }
}
