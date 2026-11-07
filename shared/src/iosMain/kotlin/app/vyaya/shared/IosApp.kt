package app.vyaya.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.ui.BackupStatus
import app.vyaya.ui.Setup
import app.vyaya.ui.VyayaRoot
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController
import kotlin.time.Clock

private val database: VyayaDatabase by lazy { IosDatabase.open() }
private val appLock: AppLock by lazy { iosAppLock() }

/** The iOS app's root view controller (shown by the Swift app, iosApp/). */
fun MainViewController(): UIViewController = ComposeUIViewController { IosApp() }

/**
 * The iOS app: the shared screens over the iOS database. Each time it comes to the front, the open routine runs (first
 * categories, clock repair, recurring catch-up) and the app lock is checked; while the lock is on, the app switcher
 * shows a blank screen (the iOS side of FLAG_SECURE). No Detected tab: iOS gives apps no access to notifications or SMS.
 */
@Composable
fun IosApp() {
    val authenticator = remember { IosAuthenticator() }
    var opened by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var lockEnabled by remember { mutableStateOf(appLock.enabled) }
    var obscured by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<BackupStatus>(BackupStatus.Idle) }
    val scope = rememberCoroutineScope()

    fun open() = scope.launch { AppOpen(database).run(Clock.System.now()); opened++ }
    fun askToUnlock() = authenticator.authenticate("Unlock Vyaya") { ok -> if (ok) { appLock.unlocked(); locked = false } }
    fun started() {
        appLock.onStart()
        locked = appLock.locked
        if (locked) askToUnlock()
        open()
    }

    LaunchedEffect(Unit) { started() }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> started() },
            center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> appLock.onStop() },
            center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _ -> obscured = appLock.enabled },
            center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ -> obscured = false },
        )
        onDispose { observers.forEach { center.removeObserver(it) } }
    }

    MaterialTheme {
        if (obscured) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            return@MaterialTheme
        }
        VyayaRoot(
            database, Setup(notificationAccess = true, smsAvailable = false, smsGranted = false), clock = { Clock.System.now() }, opened = opened,
            onNotificationAccess = {}, onSms = {}, onImport = {},
            backupStatus = backupStatus,
            onExport = { _ -> backupStatus = BackupStatus.Refused(DecodeResult.Failure(listOf("Backups on iPhone come in the next update"))) },
            onRestore = { _ -> backupStatus = BackupStatus.Refused(DecodeResult.Failure(listOf("Backups on iPhone come in the next update"))) },
            locked = locked, onUnlock = { askToUnlock() },
            lockEnabled = lockEnabled,
            onLockEnabled = { on ->
                AppLock.confirmThen(authenticator, if (on) "Turn on the app lock" else "Turn off the app lock") { appLock.enabled = on; lockEnabled = on }
            },
            deviceSecure = IosAuthenticator.deviceSecure(),
            onRulesChanged = { open() },
            showDetected = false,
        )
    }
}
