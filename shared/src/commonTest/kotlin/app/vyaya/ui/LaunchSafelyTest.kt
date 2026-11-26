package app.vyaya.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Branch review 2026-11-18 #7: a screen action that fails is said, never a crash, and the screen's scope carries on. */
class LaunchSafelyTest {

    @Test
    fun a_failing_action_is_said_and_the_next_one_still_runs() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var failed = 0
        var ran = false
        scope.launchSafely({ failed++ }) { error("the database refused it") }
        advanceUntilIdle()
        scope.launchSafely({ failed++ }) { ran = true }
        advanceUntilIdle()
        assertEquals(1, failed)
        assertTrue(ran, "the scope wasn't cancelled by the first failure")
    }
}
