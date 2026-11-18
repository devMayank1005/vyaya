package app.vyaya.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a form says when an action failed (branch review 2026-11-18 #7). */
const val ACTION_FAILED = "That didn't work. Nothing was changed."

/**
 * Branch review 2026-11-18 #7: every screen action runs here. A failure (the data refused a change, a damaged row, a
 * storage error) calls [onFail] instead of crashing the app, and the screen's scope carries on. Cancellation goes on as
 * cancellation. Nothing is logged: the error may hold the user's data.
 */
fun CoroutineScope.launchSafely(onFail: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFail()
    }
}
