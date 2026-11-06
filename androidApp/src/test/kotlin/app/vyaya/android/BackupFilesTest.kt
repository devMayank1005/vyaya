package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.android.ui.BackupScreen
import app.vyaya.android.ui.BackupStatus
import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.time.Instant

/** Backups to and from files the user picks (export via a temp file; restore from a local copy, FLAW-005). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupFilesTest {

    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    private val t = Instant.parse("2026-11-05T06:00:00Z")
    private val now = Instant.parse("2026-11-06T09:00:00Z")
    private val meta = ExportMeta("0.1.0", "android", now)

    @After fun close() = db.close()

    private fun data(n: Int, changed: Instant = t) = Snapshot.empty().copy(entries = List(n) { i ->
        Entry(EntityId.random(), EntryType.EXPENSE, Money(100L + i), CapturedAt(t, TimeZone.of("Asia/Kolkata")), null, null, null, "Shop $i", "",
            emptyList(), EntrySource.MANUAL, null, emptyList(), null, changed, changed, false)
    })

    private fun files() = BackupFiles(db, context.cacheDir, appVersion = "0.1.0")

    @Test
    fun an_export_is_the_backup_of_the_database() = runTest {
        val s = data(30)
        SnapshotStore(db).save(s)
        val out = ByteArrayOutputStream()
        assertEquals(EncodeResult.Success, files().export(out, now))
        assertEquals(BackupCodec.encode(s, meta), out.toString(Charsets.UTF_8))
        assertTrue("no temporary file left", context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("export") })
    }

    @Test
    fun a_refused_export_writes_nothing_to_the_users_file() = runTest {
        SnapshotStore(db).save(data(3, changed = Instant.parse("2026-12-01T00:00:00Z"))) // changed after the export time
        val out = ByteArrayOutputStream()
        assertTrue(files().export(out, now) is EncodeResult.Failure)
        assertEquals(0, out.size())
    }

    @Test
    fun a_restore_reads_a_local_copy_and_removes_it() = runTest {
        val backup = data(20)
        val text = BackupCodec.encode(backup, meta)
        val result = files().restore(ByteArrayInputStream(text.toByteArray()), RestoreMode.REPLACE, now)
        assertTrue(result.toString(), result is RestoreResult.Restored)
        assertEquals(backup.normalized(), SnapshotStore(db).load())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("restore") })
    }

    @Test
    fun a_damaged_or_oversized_file_is_refused_and_changes_nothing() = runTest {
        val current = data(2)
        SnapshotStore(db).save(current)
        val damaged = BackupCodec.encode(data(5), meta).replace("Shop 1", "Shop 9")
        assertTrue(files().restore(ByteArrayInputStream(damaged.toByteArray()), RestoreMode.REPLACE, now) is RestoreResult.Refused)
        val huge = BackupFiles(db, context.cacheDir, appVersion = "0.1.0", maxCopyBytes = 1_000)
        val refused = huge.restore(ByteArrayInputStream(ByteArray(5_000) { 'x'.code.toByte() }), RestoreMode.REPLACE, now)
        assertTrue(refused.toString(), refused is RestoreResult.Refused)
        assertEquals(current.normalized(), SnapshotStore(db).load())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("restore") })
    }

    @Test
    fun the_screen_offers_export_merge_replace_and_restore_anyway() {
        val clicks = mutableListOf<String>()
        val status = androidx.compose.runtime.mutableStateOf<BackupStatus>(BackupStatus.Idle)
        compose.setContent {
            BackupScreen(status.value, onExport = { clicks += "export" }, onRestore = { clicks += it.name }, onRestoreAnyway = { clicks += "anyway" })
        }
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("Restore: merge").performClick()
        compose.onNodeWithText("Restore: replace everything").performClick()
        status.value = BackupStatus.Refused(DecodeResult.Failure(listOf("This backup was made on 2027-01-01…"), clockBehind = true))
        compose.onNodeWithText("This backup was made on 2027-01-01…").assertExists()
        compose.onNodeWithText("Restore anyway").performClick()
        status.value = BackupStatus.Done("Restored")
        compose.onNodeWithText("Restored").assertExists()
        assertEquals(listOf("export", "MERGE", "REPLACE", "anyway"), clicks)
    }
}
