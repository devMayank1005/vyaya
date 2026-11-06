package app.vyaya.android

import android.content.Context

/**
 * FLAW-005: whether the system has the notification listener connected. Phones that stop background apps (Xiaomi,
 * Oppo, Vivo, some Samsung builds) drop it silently, and payments are then missed with no sign.
 *
 * Review 2026-11-06 #6: kept in this process's memory only. When the phone kills the app no disconnect callback
 * runs, so a saved flag would stay "connected" for ever; a new process starts not connected until the system binds
 * the listener again (the app asks it to when opened, and checks a few seconds later).
 */
class ListenerHealth(@Suppress("UNUSED_PARAMETER") context: Context) {

    enum class Status { NO_ACCESS, RUNNING, STOPPED }

    fun connected() { live = true }

    fun disconnected() { live = false }

    /** Access granted but not connected (dropped, or not bound again yet) is [Status.STOPPED]. */
    fun status(accessGranted: Boolean): Status = when {
        !accessGranted -> Status.NO_ACCESS
        live -> Status.RUNNING
        else -> Status.STOPPED
    }

    companion object {
        @Volatile private var live = false

        /** Tests: what a new process sees after the phone killed the app. */
        internal fun newProcessForTest() { live = false }
    }
}
