package app.vyaya.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /** The Ignored tab (Phase 3): what capture skipped, read again whenever [ignoredVersion] changes. */
    ignored: suspend () -> List<app.vyaya.core.data.IgnoredItem> = { emptyList() },
    ignoredVersion: Int = 0,
    onWasPayment: (app.vyaya.core.data.IgnoredItem) -> Unit = {},
    onNotPayment: (app.vyaya.core.data.IgnoredItem) -> Unit = {},
    /** The home time zone, for the times shown (FLAW-005). */
    homeZone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
) {
    val items = remember(inbox) { inbox.changes() }.collectAsState(emptyList()).value
    val skipped by androidx.compose.runtime.produceState(emptyList<app.vyaya.core.data.IgnoredItem>(), ignoredVersion, items) { value = ignored() }
    var showIgnored by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("Detected", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            SetupCard(setup, onNotificationAccess, onSms, onImport, onBattery = onBattery)
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(!showIgnored, { showIgnored = false }, { Text("Logged") })
                androidx.compose.material3.FilterChip(showIgnored, { showIgnored = true }, { Text("Ignored (${skipped.count { !foldedAway(it.reason) }})") })
            }
            if (showIgnored) IgnoredList(skipped, onWasPayment, onNotPayment, homeZone)
            else DetectedScreen(
                items,
                onUndo = { id -> scope.launchSafely { inbox.undo(id, clock()) } },
                onAdd = { id -> scope.launchSafely { inbox.confirm(id, clock()) } },
                onDismiss = { id -> scope.launchSafely { inbox.dismiss(id) } },
                onYesBill = { id -> scope.launchSafely { inbox.confirmBill(id, clock()) } },
                onNoBill = { id -> scope.launchSafely { inbox.denyBill(id, clock()) } },
            )
        }
    }
}
