package app.vyaya.android

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.data.BackupStore
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A large merge restore on the phone's own SQLite (review #3). Before Android 11 a statement takes at most 999 variables;
 * Robolectric's SQLite allows more, so the limit itself is checked in core/data (BackupStoreTest), this checks the whole path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun review_data_003_a_merge_restore_of_thousands_of_entries_works_on_android_10() = runTest {
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        val backup = backupText(2_500)
        val result = BackupStore(db).restore(backup.lineSequence(), app.vyaya.core.backup.RestoreMode.MERGE, NOW)
        assertTrue(result.toString(), result is RestoreResult.Restored)
        assertEquals(2_500, SnapshotStore(db).load().entries.size)
        db.close()
    }

    private val NOW = kotlin.time.Instant.parse("2026-11-06T10:00:00Z")

    private fun backupText(n: Int): String {
        val t = kotlin.time.Instant.parse("2026-11-01T10:00:00Z")
        val entries = List(n) { i ->
            app.vyaya.core.model.Entry(app.vyaya.core.model.EntityId.random(), app.vyaya.core.model.EntryType.EXPENSE, app.vyaya.core.model.Money(100L + i),
                app.vyaya.core.model.CapturedAt(t, kotlinx.datetime.TimeZone.of("Asia/Kolkata")), null, null, null, "Shop $i", "", emptyList(),
                app.vyaya.core.model.EntrySource.MANUAL, null, emptyList(), null, t, t, false)
        }
        return app.vyaya.core.backup.BackupCodec.encode(app.vyaya.core.backup.Snapshot.empty().copy(entries = entries),
            app.vyaya.core.backup.ExportMeta("1.0.0", "android", t))
    }
}
