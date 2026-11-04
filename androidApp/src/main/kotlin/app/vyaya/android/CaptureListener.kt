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
        NotificationCapture(app.capture, NotificationReader(smsApps(this)), { Clock.System.now() }, { TimeZone.currentSystemDefault() })
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { capture.handle(sbn) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The phone's SMS app (it posts the SMS notifications) and the common ones, in case it changes. */
        fun smsApps(context: Context): Set<String> = setOfNotNull(
            Telephony.Sms.getDefaultSmsPackage(context),
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        )
    }
}
