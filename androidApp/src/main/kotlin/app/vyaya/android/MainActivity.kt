package app.vyaya.android

import app.vyaya.shared.AppLock
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.vyaya.ui.Setup
import android.net.Uri
import app.vyaya.ui.BackupStatus
import app.vyaya.ui.VyayaRoot
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.AppOpen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

class MainActivity : FragmentActivity() {

    private val setup = mutableStateOf(Setup(notificationAccess = false, smsAvailable = SmsFeature.AVAILABLE, smsGranted = false))

    /** Counts the open routine's runs, so the screens read categories and settings again after it. */
    private val opened = mutableIntStateOf(0)

    private val backupStatus = mutableStateOf<BackupStatus>(BackupStatus.Idle)

    // SEC-003: the app lock, and the phone's own check before a backup leaves or replaces the data.
    private val appLock by lazy { (application as VyayaApp).appLock }
    private val authenticator by lazy { BiometricAuthenticator(this) }
    private val locked = mutableStateOf(false)
    private val lockEnabled = mutableStateOf(false)
    private val restoreFlow: RestoreFlow<Uri> = RestoreFlow(
        open = { uri -> runCatching { contentResolver.openInputStream(uri) }.getOrNull() },
        pick = { restoreFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        restore = { input, mode, anyway, password -> backupFiles().restore(input, mode, Clock.System.now(), anyway, password) },
    )

    /** The password chosen for the export being saved; cleared once used. */
    private var exportPassword: CharArray? = null

    private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = exportPassword.also { exportPassword = null }
        if (uri == null) { password?.fill(' '); return@registerForActivityResult }
        backupStatus.value = BackupStatus.Working("Writing the backup…")
        lifecycleScope.launch {
            val result = contentResolver.openOutputStream(uri)?.use { backupFiles().export(it, Clock.System.now(), password) }
            backupStatus.value = when (result) {
                EncodeResult.Success -> BackupStatus.Done("Backup saved")
                is EncodeResult.Failure -> BackupStatus.Refused(DecodeResult.Failure(listOf(result.reason)))
                null -> BackupStatus.Refused(DecodeResult.Failure(listOf("The file couldn't be opened")))
            }
        }
    }

    private val exportCsvTo = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        backupStatus.value = BackupStatus.Working("Writing the spreadsheet…")
        lifecycleScope.launch {
            val written = runCatching { contentResolver.openOutputStream(uri)?.use { backupFiles().exportCsv(it) } }.getOrNull()
            backupStatus.value = if (written != null) BackupStatus.Done(csvSavedMessage(written))
            else {
                // csv report #2: no cut-off spreadsheet is left in the user's folder looking complete.
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                BackupStatus.Refused(DecodeResult.Failure(listOf("The spreadsheet couldn't be written. Nothing was saved.")))
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
                    onExport = { password ->
                        AppLock.confirmThen(authenticator, "Export a backup of all your data") {
                            exportPassword = password
                            // Protected: the .vyaya file (docs/encrypted-backup-v1.md); plain: readable JSON lines.
                            exportTo.launch("vyaya-backup-${java.time.LocalDate.now()}.${if (password != null) "vyaya" else "json"}")
                        }
                    },
                    // The spreadsheet has no password, so it leaves the app only after the phone's own check (SEC-003).
                    onExportCsv = {
                        AppLock.confirmThen(authenticator, "Export your entries as a spreadsheet") {
                            exportCsvTo.launch("vyaya-entries-${java.time.LocalDate.now()}.csv")
                        }
                    },
                    onPassword = { pw -> showRestore { restoreFlow.withPassword(pw) } },
                    locked = locked.value,
                    onUnlock = ::askToUnlock,
                    lockEnabled = lockEnabled.value,
                    deviceSecure = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true,
                    // A rule added, changed or resumed: catch-up makes its due entries now, not at the next open.
                    onRulesChanged = { lifecycleScope.launch { AppOpen(app.database).run(Clock.System.now()); opened.intValue++ } },
                    onLockEnabled = { on ->
                        // Turning it off is a change to protection too: confirm it's you.
                        AppLock.confirmThen(authenticator, if (on) "Turn on the app lock" else "Turn off the app lock") {
                            appLock.enabled = on
                            lockEnabled.value = on
                            secureWindow(on)
                        }
                    },
                    onRestore = { mode ->
                        AppLock.confirmThen(authenticator, "Restore a backup over your data") { restoreFlow.start(mode) }
                    },
                    onRestoreAnyway = { AppLock.confirmThen(authenticator, "Restore a backup over your data") { showRestore { restoreFlow.restoreAnyway() } } },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lockEnabled.value = appLock.enabled
        secureWindow(appLock.enabled)
        appLock.onStart()
        locked.value = appLock.locked
        if (locked.value) askToUnlock()
    }

    override fun onStop() {
        appLock.onStop()
        super.onStop()
    }

    private fun askToUnlock() = authenticator.authenticate("Unlock Vyaya") { ok ->
        if (ok) { appLock.unlocked(); locked.value = false }
    }

    /** With the lock on, the app's screen is left out of the recent-apps preview and screenshots. */
    private fun secureWindow(on: Boolean) =
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

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
                is RestoreResult.Refused -> when {
                    result.needsPassword() -> BackupStatus.NeedsPassword(null)
                    result.failure.problems.firstOrNull()?.startsWith("Wrong password") == true -> BackupStatus.NeedsPassword(result.failure.problems.first())
                    else -> BackupStatus.Refused(result.failure)
                }
                null -> BackupStatus.Working("Pick the backup file again")
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
