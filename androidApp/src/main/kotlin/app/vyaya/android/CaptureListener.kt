package app.vyaya.android

import android.content.Context
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** Notification access: the system hands every posted notification here; [NotificationReader] keeps only payments. */
class CaptureListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val capture by lazy {
        val app = application as VyayaApp
        NotificationCapture(app.capture, { NotificationReader(smsAppsToRead(this)) }, { Clock.System.now() }, { TimeZone.currentSystemDefault() })
    }

    override fun onListenerConnected() {
        ListenerHealth(this).connected()
    }

    /** FLAW-005: noted for the app to show, and a new connection asked for. */
    override fun onListenerDisconnected() {
        ListenerHealth(this).disconnected()
        requestRebind(component(this))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { capture.handle(sbn) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun component(context: Context) = android.content.ComponentName(context, CaptureListener::class.java)

        /**
         * The SMS apps whose notifications are read, asked at each notification: none while this build receives SMS
         * itself (each SMS would otherwise come twice, once without its real sender address). DATA-001: until the user
         * allows SMS, or after they take it back, the notifications are the only way bank SMS arrive.
         */
        fun smsAppsToRead(context: Context): Set<String> {
            val receivesSms = Channel.READS_SMS &&
                context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return if (receivesSms) emptySet() else smsApps(context)
        }

        /** The phone's SMS app (it posts the SMS notifications) and the common ones, in case it changes. */
        fun smsApps(context: Context): Set<String> = setOfNotNull(
            Telephony.Sms.getDefaultSmsPackage(context),
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        )
    }
}
