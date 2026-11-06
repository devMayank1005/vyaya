package app.vyaya.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.DetectedKind
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.parser.UpiApp
import app.vyaya.core.parser.UpiApps
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest
import kotlin.time.Instant

/** SEC-003: a listed package name is not enough; the app must be the real one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppVerifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm = shadowOf(context.packageManager)
    private val realCert = Signature(ByteArray(64) { 1 })
    private val fakeCert = Signature(ByteArray(64) { 2 })
    private val realDigest = MessageDigest.getInstance("SHA-256").digest(realCert.toByteArray()).joinToString(":") { "%02X".format(it) }

    private fun install(pkg: String, cert: Signature, installer: String?, system: Boolean = false) {
        val info = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; if (system) flags = ApplicationInfo.FLAG_SYSTEM }
            @Suppress("DEPRECATION") signatures = arrayOf(cert)
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(cert)) }
        }
        pm.installPackage(info)
        pm.setInstallSourceInfo(pkg, installer, installer)
    }

    private fun verifier(vararg apps: UpiApp) = AppVerifier(context.packageManager, apps.associateBy { it.packageName })

    @Test
    fun without_a_known_certificate_the_store_it_came_from_decides() {
        install("com.phonepe.app", fakeCert, "com.android.vending")
        install("com.popclub.android", fakeCert, null) // sideloaded from a link
        install("net.one97.paytm", fakeCert, "com.android.chrome") // downloaded with the browser
        install("com.samsung.android.spay", fakeCert, null, system = true) // came with the phone
        val v = verifier(UpiApp("phonepe", "PhonePe", "com.phonepe.app"), UpiApp("pop", "POP", "com.popclub.android"),
            UpiApp("paytm", "Paytm", "net.one97.paytm"), UpiApp("spay", "Samsung Pay", "com.samsung.android.spay"))
        assertTrue(v.isGenuine("com.phonepe.app"))
        assertFalse(v.isGenuine("com.popclub.android"))
        assertFalse(v.isGenuine("net.one97.paytm"))
        assertTrue(v.isGenuine("com.samsung.android.spay"))
    }

    @Test
    fun a_known_certificate_decides_whatever_the_store() {
        install("com.popclub.android", realCert, null)
        install("com.phonepe.app", fakeCert, "com.android.vending")
        val v = verifier(UpiApp("pop", "POP", "com.popclub.android", listOf(realDigest)), UpiApp("phonepe", "PhonePe", "com.phonepe.app", listOf(realDigest.lowercase().replace(":", ""))))
        assertTrue("the real app, sideloaded", v.isGenuine("com.popclub.android"))
        assertFalse("another certificate, even from the Play Store", v.isGenuine("com.phonepe.app"))
    }

    @Test
    fun an_app_that_isnt_installed_is_not_genuine() {
        assertFalse(verifier(UpiApp("pop", "POP", "com.popclub.android")).isGenuine("com.popclub.android"))
    }

    @Test
    fun a_fake_apps_payment_notification_goes_to_review() = runTest {
        install("com.popclub.android", fakeCert, null)
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        val inbox = DetectedInbox(db)
        val verifier = verifier(UpiApp("pop", "POP", "com.popclub.android"))
        val reader = NotificationReader(smsApps = emptySet(), isGenuine = verifier::isGenuine)
        val now = Instant.parse("2026-11-04T10:00:00Z")
        val c = NotificationContent("com.popclub.android", "POP", "Received ₹50,000 from Rahul S", null, emptyList(), emptyList(), now.toEpochMilliseconds())
        val raw = reader.read(c, TimeZone.of("Asia/Kolkata")).single()
        assertTrue(raw.appUnverified)
        CaptureStore(db, inbox).process(raw, now)
        assertEquals(DetectedKind.REVIEW, inbox.items().single().kind)
        assertEquals("UNVERIFIED_APP", inbox.items().single().reason)
        db.close()
    }

    @Test
    fun the_manifest_can_see_every_listed_app() {
        // Android 11+ hides other apps unless the manifest names them; an app it can't see would never be genuine.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val queried = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(UpiApps.default.apps.map { it.packageName }.toSet(), queried)
    }
}
