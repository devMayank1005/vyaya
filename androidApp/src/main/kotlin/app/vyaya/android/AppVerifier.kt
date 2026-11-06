package app.vyaya.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import app.vyaya.core.parser.UpiApp
import java.security.MessageDigest

/**
 * SEC-003: whether the app posting under a listed package name is the real one. Android keeps package names unique
 * on the phone, but anyone can publish an APK under one (a fake "POP" from a link).
 * - When the app list has the real app's certificate digests, the signing certificate decides.
 * - Otherwise it must come from a store that checks who publishes under a name (Play, Galaxy Store), or with the phone.
 * Remembered per package until it is updated.
 */
class AppVerifier(private val pm: PackageManager, private val apps: Map<String, UpiApp>) {

    private val known = HashMap<String, Pair<Long, Boolean>>()

    @Synchronized
    fun isGenuine(packageName: String): Boolean {
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        known[packageName]?.let { (updated, genuine) -> if (updated == info.lastUpdateTime) return genuine }
        val digests = apps[packageName]?.certSha256.orEmpty().map(::normal).toSet()
        val genuine = if (digests.isNotEmpty()) {
            signers(info).any { normal(sha256(it)) in digests }
        } else {
            val system = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            system || installer(packageName) in STORES
        }
        known[packageName] = info.lastUpdateTime to genuine
        return genuine
    }

    private fun signers(info: android.content.pm.PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptyList()
            // The current signer(s); with key rotation, the history's certificates are the same app too.
            (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory).orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") info.signatures.orEmpty().map { it.toByteArray() }
        }

    private fun installer(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(packageName)
    } catch (e: Exception) {
        null
    }

    private fun sha256(cert: ByteArray) = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

    /** Digests compare without case or colons (`AB:CD…` as apksigner prints them, or `abcd…`). */
    private fun normal(digest: String) = digest.replace(":", "").lowercase()

    private companion object {
        val STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps")
    }
}
