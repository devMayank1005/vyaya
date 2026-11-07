package app.vyaya.shared

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.data.VyayaDatabase
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * The app's database on iOS: in Application Support (not Documents, which the Files app can show), in a folder marked
 * excluded from iCloud and computer backups, the iOS side of SEC-007 (rule 8.8: nothing leaves the device; the user
 * moves data with Vyaya's own backup file). The same SQLite as the tests (bundled).
 */
object IosDatabase {

    /** The app's folder for the database. */
    fun defaultFolder(): String {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        return "$support/Vyaya/"
    }

    @OptIn(ExperimentalForeignApi::class)
    fun open(folder: String = defaultFolder()): VyayaDatabase {
        // iOS report SEC-001: complete protection: unreadable whenever the phone is locked (the app does no background
        // work on iOS). New files in the folder (the database, its journal) take the folder's class.
        NSFileManager.defaultManager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = PROTECTION, error = null)
        NSFileManager.defaultManager.setAttributes(PROTECTION, ofItemAtPath = folder, error = null)
        excludeFromBackup(folder)
        return Room.databaseBuilder<VyayaDatabase>(name = folder + "vyaya.db").setDriver(BundledSQLiteDriver()).build()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun excludeFromBackup(folder: String) {
        val ok = NSURL.fileURLWithPath(folder, isDirectory = true).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        check(ok) { "the database folder couldn't be excluded from backups" }
    }

    /** Complete protection for the database folder (iOS report SEC-001). */
    val PROTECTION: Map<Any?, Any?> = mapOf(NSFileProtectionKey to NSFileProtectionComplete)

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun isExcludedFromBackup(folder: String): Boolean {
        val values = NSURL.fileURLWithPath(folder, isDirectory = true).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
        return (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue == true
    }
}
