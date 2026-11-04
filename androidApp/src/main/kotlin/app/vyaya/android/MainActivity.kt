package app.vyaya.android

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.vyaya.android.ui.Setup
import app.vyaya.android.ui.VyayaScreen
import kotlinx.coroutines.launch
import kotlin.time.Clock

class MainActivity : ComponentActivity() {

    private val setup = mutableStateOf(Setup(notificationAccess = false, smsAvailable = SmsFeature.AVAILABLE, smsGranted = false))

    private val askSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        refresh()
        if (granted.values.all { it }) importSms() // what the user asked for when allowing it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VyayaApp
        setContent {
            MaterialTheme {
                VyayaScreen(
                    app.inbox, setup.value, clock = { Clock.System.now() },
                    onNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onSms = { askSms.launch(SmsFeature.permissions) },
                    onImport = ::importSms,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // the user may be back from the notification access settings
    }

    private fun refresh() {
        setup.value = Setup(
            notificationAccess = packageName in NotificationManagerCompat.getEnabledListenerPackages(this),
            smsAvailable = SmsFeature.AVAILABLE,
            smsGranted = SmsFeature.permissions.isNotEmpty() &&
                SmsFeature.permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED },
        )
    }

    private fun importSms() {
        lifecycleScope.launch {
            val added = SmsFeature.import(applicationContext)
            Toast.makeText(this@MainActivity, "Added $added payments from the last 30 days", Toast.LENGTH_SHORT).show()
        }
    }
}
