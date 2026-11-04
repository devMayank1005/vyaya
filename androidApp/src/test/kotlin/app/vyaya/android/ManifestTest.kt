package app.vyaya.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** What the merged manifest allows: nothing leaves the phone (rule 8.8, SEC-007) and only the system reaches in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm = context.packageManager
    private val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
    private val info = pm.getPackageInfo(context.packageName, flags)

    @Test
    fun no_internet_or_network_permission() {
        val asked = info.requestedPermissions.orEmpty().toSet()
        assertFalse(asked.toString(), "android.permission.INTERNET" in asked)
        assertFalse(asked.toString(), "android.permission.ACCESS_NETWORK_STATE" in asked)
    }

    @Test
    fun sec_007_no_backup_and_no_device_transfer() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        // Android 12+: allowBackup=false no longer stops device-to-device transfer; the extraction rules must.
        val xml = context.resources.getXml(R.xml.data_extraction_rules)
        val excluded = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType != XmlPullParser.START_TAG) continue
            when (xml.name) {
                "cloud-backup", "device-transfer" -> section = xml.name
                "exclude" -> excluded.getOrPut(section!!) { mutableSetOf() } += xml.getAttributeValue(null, "domain")
                "include" -> error("nothing may be included")
            }
        }
        val all = setOf("root", "file", "database", "sharedpref", "external")
        assertEquals(all, excluded["cloud-backup"])
        assertEquals(all, excluded["device-transfer"])
    }

    @Test
    fun every_exported_component_is_the_launcher_or_guarded_by_a_system_permission() {
        val guarded = mapOf(
            CaptureListener::class.java.name to "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "app.vyaya.android.SmsReceiver" to "android.permission.BROADCAST_SMS",
            // From androidx.profileinstaller (merged in): installs startup profiles; DUMP is held only by the system and adb.
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        )
        info.services.orEmpty().filter { it.exported }.forEach { assertEquals(it.name, guarded[it.name], it.permission) }
        info.receivers.orEmpty().filter { it.exported }.forEach { assertEquals(it.name, guarded[it.name], it.permission) }
        // Debug builds also get compose's test host activity (ui-test-manifest, debugImplementation only); release must not.
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val testHost = if (debug) listOf("androidx.activity.ComponentActivity") else emptyList()
        assertEquals(listOf(MainActivity::class.java.name) + testHost, info.activities.orEmpty().filter { it.exported }.map { it.name })
        assertTrue(info.providers.orEmpty().none { it.exported })
    }

    @Test
    fun only_the_full_build_asks_for_sms() {
        val sms = info.requestedPermissions.orEmpty().filter { "SMS" in it }.toSet()
        assertEquals(if (Channel.READS_SMS) setOf("android.permission.RECEIVE_SMS", "android.permission.READ_SMS") else emptySet<String>(), sms)
    }
}
