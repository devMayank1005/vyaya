package app.vyaya.android

import android.content.Context

/**
 * FLAW-005: whether the system has the notification listener connected. Phones that stop background apps (Xiaomi,
 * Oppo, Vivo, some Samsung builds) drop it silently, and payments are then missed with no sign.
 * Kept in the app's own preferences (excluded from backup and transfer).
 */
class ListenerHealth(context: Context) {

    enum class Status { NO_ACCESS, RUNNING, STOPPED }

    private val prefs = context.applicationContext.getSharedPreferences("listener", Context.MODE_PRIVATE)

    fun connected() = prefs.edit().putBoolean(KEY, true).apply()

    fun disconnected() = prefs.edit().putBoolean(KEY, false).apply()

    /** Access granted but not connected (dropped, or never bound yet) is [Status.STOPPED]. */
    fun status(accessGranted: Boolean): Status = when {
        !accessGranted -> Status.NO_ACCESS
        prefs.getBoolean(KEY, false) -> Status.RUNNING
        else -> Status.STOPPED
    }

    private companion object {
        const val KEY = "connected"
    }
}
