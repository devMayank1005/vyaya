package app.vyaya.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Play build has no SMS permission: it reads the SMS app's notifications instead. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayChannelTest {
    @Test
    fun the_play_build_reads_the_sms_apps_notifications() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals(false, Channel.READS_SMS)
        assertTrue("com.google.android.apps.messaging" in CaptureListener.smsAppsFor(context, Channel.READS_SMS))
    }

    @Test
    fun the_play_build_asks_for_no_sms_permission() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val asked = info.requestedPermissions.orEmpty().toSet()
        assertTrue(asked.none { it.contains("SMS") })
    }
}
