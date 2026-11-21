package app.vyaya.android

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Widget report FLAW-004: the SMS build installs without a phone radio, and offers SMS only where there is one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsHardwareTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun widget_flaw_004_telephony_is_declared_optional() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
        val telephony = info.reqFeatures.orEmpty().singleOrNull { it.name == PackageManager.FEATURE_TELEPHONY }
        assertTrue("declared", telephony != null)
        assertEquals("optional", 0, telephony!!.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
    }

    @Test
    fun widget_flaw_004_sms_is_offered_only_on_a_device_that_has_it() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        assertFalse(SmsFeature.available(context))
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        assertTrue(SmsFeature.available(context))
    }
}
