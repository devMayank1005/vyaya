package app.vyaya.android

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.vyaya.android.ui.Setup
import android.net.Uri
import app.vyaya.android.ui.BackupStatus
import app.vyaya.android.ui.VyayaRoot
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.AppOpen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

class MainActivity : ComponentActivity() {

    private val setup = mutableStateOf(Setup(notificationAccess = false, smsAvailable = SmsFeature.AVAILABLE, smsGranted = false))

    /** Counts the open routine's runs, so the screens read categories and settings again after it. */
    private val opened = mutableIntStateOf(0)

    private val backupStatus = mutableStateOf<BackupStatus>(BackupStatus.Idle)
    private val restoreFlow: RestoreFlow<Uri> = RestoreFlow(
        open = { uri -> runCatching { contentResolver.openInputStream(uri) }.getOrNull() },
        pick = { restoreFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        restore = { input, mode, anyway -> backupFiles().restore(input, mode, Clock.System.now(), anyway) },
    )

    private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        backupStatus.value = BackupStatus.Working("Writing the backup…")
        lifecycleScope.launch {
            val result = contentResolver.openOutputStream(uri)?.use { backupFiles().export(it, Clock.System.now()) }
            backupStatus.value = when (result) {
                EncodeResult.Success -> BackupStatus.Done("Backup saved")
                is EncodeResult.Failure -> BackupStatus.Refused(DecodeResult.Failure(listOf(result.reason)))
                null -> BackupStatus.Refused(DecodeResult.Failure(listOf("The file couldn't be opened")))
            }
        }
    }

    private val restoreFrom: ActivityResultLauncher<Array<String>> = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        showRestore { restoreFlow.picked(uri) }
    }

    private val askSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        refresh()
        if (granted.values.all { it }) importSms() // what the user asked for when allowing it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VyayaApp
        setContent {
            MaterialTheme {
                VyayaRoot(
                    app.database, setup.value, clock = { Clock.System.now() }, opened = opened.intValue,
                    onNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onSms = { askSms.launch(SmsFeature.permissions) },
                    onImport = ::importSms,
                    onBattery = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    backupStatus = backupStatus.value,
                    onExport = { exportTo.launch("vyaya-backup-${java.time.LocalDate.now()}.json") },
                    onRestore = { mode -> restoreFlow.start(mode) },
                    onRestoreAnyway = { showRestore { restoreFlow.restoreAnyway() } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Each time the app comes to the front: first-open categories, clock repair, recurring catch-up (FLAW-004).
        val app = application as VyayaApp
        lifecycleScope.launch {
            AppOpen(app.database).run(Clock.System.now())
            opened.intValue++
        }
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

    private fun backupFiles() = BackupFiles((application as VyayaApp).database, cacheDir, appVersion = BuildInfo.versionName(this))

    /** Runs a restore step and shows its result; a null result means the file is being asked for again. */
    private fun showRestore(step: suspend () -> RestoreResult?) {
        backupStatus.value = BackupStatus.Working("Restoring…")
        lifecycleScope.launch {
            val result = step()
            backupStatus.value = when (result) {
                is RestoreResult.Restored -> {
                    // A restore can bring rules, settings and change times: run the open routine on what is now there.
                    AppOpen((application as VyayaApp).database).run(Clock.System.now())
                    opened.intValue++
                    BackupStatus.Done("Restored")
                }
                is RestoreResult.Refused -> BackupStatus.Refused(result.failure)
                null -> BackupStatus.Working("Pick the backup file again to restore it anyway")
            }
        }
    }

    private fun importSms() {
        lifecycleScope.launch {
            val added = SmsFeature.import(applicationContext)
            Toast.makeText(this@MainActivity, "Added $added payments from the last 30 days", Toast.LENGTH_SHORT).show()
        }
    }
}
