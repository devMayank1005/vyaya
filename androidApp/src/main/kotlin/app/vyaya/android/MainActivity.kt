package app.vyaya.android

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
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
import kotlinx.coroutines.delay
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
                    onBattery = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh(checkListener = false) // the user may be back from the notification access settings
        val access = setup.value.notificationAccess
        // FLAW-005: ask the system to (re)connect the listener whenever the app is opened with access granted, and
        // look again once it had time to (review 2026-11-06 #6), so the warning doesn't flash while it binds.
        if (access) NotificationListenerService.requestRebind(CaptureListener.component(this))
        lifecycleScope.launch {
            delay(LISTENER_GRACE_MS)
            refresh(checkListener = true)
        }
    }

    private fun refresh(checkListener: Boolean = true) {
        val access = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        setup.value = Setup(
            notificationAccess = access,
            listenerStopped = if (checkListener) ListenerHealth(this).status(access) == ListenerHealth.Status.STOPPED else setup.value.listenerStopped,
            smsAvailable = SmsFeature.AVAILABLE,
            smsGranted = SmsFeature.permissions.isNotEmpty() &&
                SmsFeature.permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED },
        )
    }

    private companion object {
        const val LISTENER_GRACE_MS = 3_000L
    }

    private fun importSms() {
        lifecycleScope.launch {
            val added = SmsFeature.import(applicationContext)
            Toast.makeText(this@MainActivity, "Added $added payments from the last 30 days", Toast.LENGTH_SHORT).show()
        }
    }
}
