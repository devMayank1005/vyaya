package app.vyaya.shared

import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.SnapshotStore
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The iOS database: a real file, opened like the app opens it, kept out of iCloud backups (SEC-007 on iOS). */
class IosDatabaseTest {

    private fun dir() = NSTemporaryDirectory() + "vyaya-test-" + NSUUID().UUIDString + "/"

    @Test
    fun the_database_opens_in_its_folder_and_the_open_routine_runs() = runTest {
        val folder = dir()
        val db = IosDatabase.open(folder)
        val result = AppOpen(db).run(Instant.parse("2026-11-07T04:30:00Z"))
        assertTrue(result.seededCategories)
        assertEquals(13, SnapshotStore(db).loadRecords().categories.size)
        db.close()
        // Opened again from the same file: the data is still there.
        val again = IosDatabase.open(folder)
        assertEquals(13, SnapshotStore(again).loadRecords().categories.size)
        again.close()
    }

    @Test
    fun the_folder_is_excluded_from_icloud_backup() {
        val folder = dir()
        IosDatabase.open(folder).close()
        assertTrue(IosDatabase.isExcludedFromBackup(folder))
    }

    @Test
    fun ios_sec_001_the_folder_asks_for_complete_file_protection() {
        // The simulator keeps no protection classes (it reads back null), so this checks what is asked of iOS; the
        // effect itself is checked on a device (reports/ios-app-2026-11-07.md).
        assertEquals(mapOf<Any?, Any?>(platform.Foundation.NSFileProtectionKey to platform.Foundation.NSFileProtectionComplete), IosDatabase.PROTECTION)
    }
}
