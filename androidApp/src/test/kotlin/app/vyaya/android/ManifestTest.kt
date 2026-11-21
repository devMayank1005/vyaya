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
            // Only the system can bind a Quick Settings tile.
            QuickAddTile::class.java.name to "android.permission.BIND_QUICK_SETTINGS_TILE",
            // A home-screen widget must be reachable by the launcher: no permission exists for it. It only redraws itself.
            QuickAddWidget::class.java.name to null,
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

    @Test
    fun budgets_flaw_002_asks_to_post_notifications_for_budget_alerts() {
        val asked = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue(asked.toString(), "android.permission.POST_NOTIFICATIONS" in asked)
    }

    @Test
    fun the_widget_only_answers_the_systems_update() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName), 0)
        assertEquals(listOf(QuickAddWidget::class.java.name), receivers.map { it.activityInfo.name })
        // Besides the system's own time and zone changes (protected broadcasts: only the system can send them).
        val actions = info.receivers.orEmpty().single { it.name == QuickAddWidget::class.java.name }.let { r ->
            listOf(AppWidgetManagerActions.UPDATE, android.content.Intent.ACTION_TIME_CHANGED, android.content.Intent.ACTION_TIMEZONE_CHANGED, "app.vyaya.action.ADD")
                .filter { a -> context.packageManager.queryBroadcastReceivers(android.content.Intent(a).setPackage(context.packageName), 0).any { it.activityInfo.name == r.name } }
        }
        assertEquals(listOf(AppWidgetManagerActions.UPDATE, android.content.Intent.ACTION_TIME_CHANGED, android.content.Intent.ACTION_TIMEZONE_CHANGED), actions)
        val other = context.packageManager.queryBroadcastReceivers(android.content.Intent("app.vyaya.action.ADD").setPackage(context.packageName), 0)
        assertTrue(other.isEmpty())
    }

    @Test
    fun launch_the_app_has_an_adaptive_launcher_icon_with_a_themed_version() {
        val app = context.applicationInfo
        assertTrue("no launcher icon", app.icon != 0)
        assertEquals("mipmap", context.resources.getResourceTypeName(app.icon))
        val icon = context.resources.getXml(app.icon)
        val layers = mutableListOf<String>()
        while (icon.next() != XmlPullParser.END_DOCUMENT) if (icon.eventType == XmlPullParser.START_TAG) layers += icon.name
        assertEquals(listOf("adaptive-icon", "background", "foreground", "monochrome"), layers)
    }
}


private object AppWidgetManagerActions { const val UPDATE = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE }
