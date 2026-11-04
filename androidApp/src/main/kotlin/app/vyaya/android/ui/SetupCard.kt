package app.vyaya.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What capture has been allowed to read. [smsAvailable]: the full build, which can read SMS itself. */
data class Setup(val notificationAccess: Boolean, val smsAvailable: Boolean, val smsGranted: Boolean) {
    val complete: Boolean get() = notificationAccess && (!smsAvailable || smsGranted)
}

/** Asks for what's missing; once all is allowed, the full build offers the 30-day import. */
@Composable
fun SetupCard(setup: Setup, onNotificationAccess: () -> Unit, onSms: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    if (setup.complete && !setup.smsAvailable) return
    Card(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (!setup.notificationAccess) {
                Text("Vyaya reads payment notifications from UPI and bank apps on this phone. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onNotificationAccess, Modifier.padding(top = 8.dp)) { Text("Allow notification access") }
            }
            if (setup.smsAvailable && !setup.smsGranted) {
                Text("Bank SMS can be read as they arrive, and the last 30 days imported.", style = MaterialTheme.typography.bodyMedium)
                Button(onSms, Modifier.padding(top = 8.dp)) { Text("Allow SMS") }
            }
            if (setup.smsAvailable && setup.smsGranted) {
                OutlinedButton(onImport) { Text("Import last 30 days") }
            }
        }
    }
}
