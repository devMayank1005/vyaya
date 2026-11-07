package app.vyaya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown instead of everything else while Vyaya is locked (SEC-003). */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Vyaya is locked", style = MaterialTheme.typography.headlineSmall)
        Button(onUnlock, Modifier.padding(top = 16.dp)) { Text("Unlock") }
    }
}
