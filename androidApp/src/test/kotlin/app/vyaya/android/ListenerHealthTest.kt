package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.vyaya.ui.Setup
import app.vyaya.ui.SetupCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FLAW-005: phones that stop background apps also stop the listener; the app must notice and say so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenerHealthTest {

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @org.junit.Before fun freshProcess() = ListenerHealth.newProcessForTest()

    @Test
    fun the_listener_state_follows_the_system_connecting_and_dropping_it() {
        val health = ListenerHealth(context)
        assertEquals(ListenerHealth.Status.NO_ACCESS, health.status(accessGranted = false))
        assertEquals("access but never connected", ListenerHealth.Status.STOPPED, health.status(accessGranted = true))
        val listener = Robolectric.buildService(CaptureListener::class.java).create().get()
        listener.onListenerConnected()
        assertEquals(ListenerHealth.Status.RUNNING, ListenerHealth(context).status(accessGranted = true))
        listener.onListenerDisconnected()
        assertEquals(ListenerHealth.Status.STOPPED, ListenerHealth(context).status(accessGranted = true))
    }

    @Test
    fun review_flaw_006_a_killed_app_starts_not_connected_whatever_was_saved() {
        val listener = Robolectric.buildService(CaptureListener::class.java).create().get()
        listener.onListenerConnected()
        assertEquals(ListenerHealth.Status.RUNNING, ListenerHealth(context).status(accessGranted = true))
        // The phone kills the app: no disconnect callback runs. The next process must not think it is connected.
        ListenerHealth.newProcessForTest()
        assertEquals(ListenerHealth.Status.STOPPED, ListenerHealth(context).status(accessGranted = true))
    }

    @Test
    fun a_stopped_listener_is_shown_with_the_way_to_fix_it() {
        var opened = 0
        compose.setContent {
            SetupCard(Setup(notificationAccess = true, smsAvailable = false, smsGranted = false, listenerStopped = true), {}, {}, {}, onBattery = { opened++ })
        }
        compose.onNodeWithText("Vyaya isn't receiving notifications", substring = true).assertExists()
        compose.onNodeWithText("Battery settings").performClick()
        assertEquals(1, opened)
    }
}
