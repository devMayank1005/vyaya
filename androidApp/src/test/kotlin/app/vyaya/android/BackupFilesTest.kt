package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.ui.BackupScreen
import app.vyaya.ui.BackupStatus
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
        compose.onNodeWithText("Export without a password").performScrollTo().performClick()
        compose.onNodeWithText("Restore: merge").performScrollTo().performClick()
        compose.onNodeWithText("Restore: replace everything").performScrollTo().performClick()
        status.value = BackupStatus.Refused(DecodeResult.Failure(listOf("This backup was made on 2027-01-01…"), clockBehind = true))
        compose.onNodeWithText("This backup was made on 2027-01-01…").assertExists()
        compose.onNodeWithText("Restore anyway").performScrollTo().performClick()
        status.value = BackupStatus.Done("Restored")
        compose.onNodeWithText("Restored").assertExists()
        assertEquals(listOf("export", "MERGE", "REPLACE", "anyway"), clicks)
    }

    private val pw = "a good long password".toCharArray()

    @Test
    fun sec_002_a_password_protected_backup_restores_with_its_password_only() = runTest {
        val s = data(500)
        SnapshotStore(db).save(s)
        val out = ByteArrayOutputStream()
        assertEquals(EncodeResult.Success, files().export(out, now, password = pw.copyOf()))
        val file = out.toByteArray()
        assertTrue(BackupCrypto.isEncrypted(file.copyOf(8)))
        assertTrue("no plain text in the file", !String(file, Charsets.ISO_8859_1).contains("Shop 1"))

        val other: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        val otherFiles = BackupFiles(other, context.cacheDir, appVersion = "0.1.0")
        assertTrue(otherFiles.restore(ByteArrayInputStream(file), RestoreMode.REPLACE, now).needsPassword())
        val wrong = otherFiles.restore(ByteArrayInputStream(file), RestoreMode.REPLACE, now, password = "not it at all".toCharArray())
        assertTrue(wrong.toString(), wrong is RestoreResult.Refused && wrong.failure.problems.single().startsWith("Wrong password"))
        val restored = otherFiles.restore(ByteArrayInputStream(file), RestoreMode.REPLACE, now, password = pw.copyOf())
        assertTrue(restored.toString(), restored is RestoreResult.Restored)
        assertEquals(s.normalized(), SnapshotStore(other).load())
        other.close()
    }

    @Test
    fun sec_002_an_encrypted_file_changed_after_export_changes_nothing() = runTest {
        SnapshotStore(db).save(data(500))
        val out = ByteArrayOutputStream()
        files().export(out, now, password = pw.copyOf())
        val file = out.toByteArray().also { it[it.size - 100] = (it[it.size - 100].toInt() xor 1).toByte() } // a late chunk
        val other: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        val before = SnapshotStore(other).load()
        val r = BackupFiles(other, context.cacheDir, appVersion = "0.1.0").restore(ByteArrayInputStream(file), RestoreMode.REPLACE, now, password = pw.copyOf())
        assertTrue(r.toString(), r is RestoreResult.Refused)
        assertEquals(before, SnapshotStore(other).load())
        other.close()
    }

    @Test
    fun sec_002_the_screen_asks_for_matching_passwords_or_an_explicit_no() {
        val exported = mutableListOf<String?>()
        compose.setContent { BackupScreen(BackupStatus.Idle, onExport = { exported += it?.concatToString() }, onRestore = {}, onRestoreAnyway = {}) }
        compose.onNodeWithText("Password").performTextInput("short")
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("At least 8 characters", substring = true).assertExists()
        compose.onNodeWithText("Password").performTextReplacement("a good long password")
        compose.onNodeWithText("Repeat the password").performTextInput("a good long passwort")
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("The passwords don't match").assertExists()
        compose.onNodeWithText("Repeat the password").performTextReplacement("a good long password")
        compose.onNodeWithText("Export backup").performClick()
        assertEquals(listOf<String?>("a good long password"), exported)
    }

    @Test
    fun sec_002_exporting_without_a_password_needs_a_second_explicit_tap() {
        val exported = mutableListOf<String?>()
        compose.setContent { BackupScreen(BackupStatus.Idle, onExport = { exported += it?.concatToString() }, onRestore = {}, onRestoreAnyway = {}) }
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("anyone who gets the file can read it", substring = true).assertExists()
        assertEquals(emptyList<String?>(), exported)
        compose.onNodeWithText("Export without a password").performScrollTo().performClick()
        assertEquals(listOf<String?>(null), exported)
    }

    @Test
    fun sec_002_a_protected_backup_asks_for_its_password() {
        val given = mutableListOf<String>()
        compose.setContent { BackupScreen(BackupStatus.NeedsPassword(null), onExport = {}, onRestore = {}, onRestoreAnyway = {}, onPassword = { given += it.concatToString() }) }
        compose.onNodeWithText("Backup password").performTextInput("a good long password")
        compose.onNodeWithText("Restore with this password").performClick()
        assertEquals(listOf("a good long password"), given)
    }

    @Test
    fun csv_the_spreadsheet_is_written_from_the_database() = runTest {
        SnapshotStore(db).save(data(1_203))
        val out = ByteArrayOutputStream()
        val written = BackupFiles(db, context.cacheDir, "0.1.0").exportCsv(out)
        assertEquals(app.vyaya.core.data.CsvStore.Written(1_203, 0), written)
        assertEquals(app.vyaya.core.backup.CsvExport.export(SnapshotStore(db).load()), out.toString(Charsets.UTF_8))
    }

    @Test
    fun csv_settings_has_a_spreadsheet_export_that_says_it_has_no_password() {
        var asked = 0
        compose.setContent { BackupScreen(BackupStatus.Idle, onExport = {}, onRestore = {}, onRestoreAnyway = {}, onExportCsv = { asked++ }) }
        compose.onNodeWithText("No password: anyone with the file can read it", substring = true).performScrollTo()
        compose.onNodeWithText("Export spreadsheet (CSV)").performScrollTo().performClick()
        assertEquals(1, asked)
    }

    @Test
    fun csv_data_002_a_failed_export_writes_nothing_to_the_picked_file() = runTest {
        SnapshotStore(db).save(data(10))
        db.close() // the database fails mid-export (any error, not only a storage one)
        val out = ByteArrayOutputStream()
        assertEquals(null, BackupFiles(db, context.cacheDir, "0.1.0").exportCsv(out))
        assertEquals("nothing reaches the user's file until the whole spreadsheet is written", 0, out.size())
        assertTrue("the temporary file is gone", context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("csv") })
    }

    @Test
    fun csv_pen_003_the_message_says_how_many_rows_were_left_out() {
        assertEquals("Spreadsheet saved: 1 entry", csvSavedMessage(app.vyaya.core.data.CsvStore.Written(1, 0)))
        assertEquals("Spreadsheet saved: 12 entries. 2 couldn't be read and were left out.",
            csvSavedMessage(app.vyaya.core.data.CsvStore.Written(12, 2)))
    }

    @Test
    fun csv_sec_004_without_a_screen_lock_the_export_asks_first() {
        var asked = 0
        compose.setContent {
            BackupScreen(BackupStatus.Idle, onExport = {}, onRestore = {}, onRestoreAnyway = {}, deviceSecure = false, onExportCsv = { asked++ })
        }
        compose.onNodeWithText("Export spreadsheet (CSV)").performScrollTo().performClick()
        compose.onNodeWithText("Export without a check?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, asked)
        compose.onNodeWithText("Export spreadsheet (CSV)").performScrollTo().performClick()
        compose.onNodeWithText("Export anyway").performClick()
        assertEquals(1, asked)
    }
}
