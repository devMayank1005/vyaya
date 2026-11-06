package app.vyaya.core.data

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.Restore
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Backups straight from and into the database, page by page (PEN-003: no whole snapshot needed to export). */
class BackupStoreTest {

    private val t = Instant.parse("2026-10-27T10:00:00Z")
    private val meta = ExportMeta("1.0.0", "android", Instant.parse("2026-10-28T00:00:00Z"))
    private val now = Instant.parse("2026-10-28T01:00:00Z")

    private fun withEntries(n: Int): Snapshot = Snapshot.empty().copy(entries = List(n) { i ->
        Entry(EntityId.random(), EntryType.EXPENSE, Money(100L + i), CapturedAt(t, TimeZone.of("Asia/Kolkata")), null, null, null,
            "Shop $i", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)
    })

    @Test
    fun an_export_from_the_database_is_byte_for_byte_the_backup_of_its_data() = runTest {
        val db = inMemoryDatabase()
        val s = withEntries(25)
        SnapshotStore(db).save(s)
        val out = StringBuilder()
        assertIs<EncodeResult.Success>(BackupStore(db, pageSize = 4).export(meta) { out.append(it).append('\n') })
        assertEquals(BackupCodec.encode(s, meta), out.toString())
        db.close()
    }

    @Test
    fun restore_replace_puts_exactly_the_backup_in_the_database() = runTest {
        val db = inMemoryDatabase()
        SnapshotStore(db).save(withEntries(3))
        val backup = withEntries(5)
        val result = BackupStore(db).restore(BackupCodec.encode(backup, meta).lineSequence(), RestoreMode.REPLACE, now)
        assertIs<RestoreResult.Restored>(result)
        assertEquals(backup.normalized(), SnapshotStore(db).load())
        db.close()
    }

    @Test
    fun restore_merge_matches_the_merge_rule() = runTest {
        val db = inMemoryDatabase()
        val current = withEntries(3)
        SnapshotStore(db).save(current)
        val edited = current.entries.first().copy(note = "edited elsewhere", updatedAt = t + 5.minutes)
        val backup = withEntries(2).let { it.copy(entries = it.entries + edited) }
        val metaLater = meta.copy(exportedAt = t + 10.minutes)
        BackupStore(db).restore(BackupCodec.encode(backup, metaLater).lineSequence(), RestoreMode.MERGE, now)
        assertEquals(Restore.apply(current, backup, RestoreMode.MERGE), SnapshotStore(db).load())
        db.close()
    }

    @Test
    fun a_bad_backup_changes_nothing() = runTest {
        val db = inMemoryDatabase()
        val current = withEntries(3)
        SnapshotStore(db).save(current)
        // The damage is only found at the end (the checksum), after batches of entries were written: all are undone.
        val damaged = BackupCodec.encode(withEntries(50), meta).replace("Shop 1", "Shop 9")
        for (mode in RestoreMode.entries) {
            val result = BackupStore(db, pageSize = 2).restore(damaged.lineSequence(), mode, now)
            assertIs<DecodeResult.Failure>(assertIs<RestoreResult.Refused>(result).failure)
            assertEquals(current.normalized(), SnapshotStore(db).load())
        }
        db.close()
    }

    @Test
    fun restores_in_batches_give_what_the_restore_rule_gives() = runTest {
        for (seed in 1L..15L) {
            val random = Random(seed)
            val current = withEntries(random.nextInt(0, 12))
            val backup = withEntries(random.nextInt(0, 12)).let { b ->
                // Some of the same records, edited before or after the current copy.
                b.copy(entries = b.entries + current.entries.filter { random.nextBoolean() }.map {
                    it.copy(note = "other copy", updatedAt = t + (if (random.nextBoolean()) 5 else -5).minutes)
                })
            }
            val text = BackupCodec.encode(backup, meta.copy(exportedAt = t + 10.minutes))
            for (mode in RestoreMode.entries) {
                val db = inMemoryDatabase()
                SnapshotStore(db).save(current)
                assertIs<RestoreResult.Restored>(BackupStore(db, pageSize = 3).restore(text.lineSequence(), mode, now))
                assertEquals(Restore.apply(current, backup, mode), SnapshotStore(db).load(), "seed $seed $mode")
                db.close()
            }
        }
    }

    @Test
    fun restoring_anyway_writes_what_the_reader_gives() = runTest {
        val db = inMemoryDatabase()
        val text = BackupCodec.encode(withEntries(7), meta.copy(exportedAt = Instant.parse("2099-01-01T00:00:00Z")))
        val refused = assertIs<RestoreResult.Refused>(BackupStore(db).restore(text.lineSequence(), RestoreMode.REPLACE, now))
        assertTrue(refused.failure.clockBehind)
        assertIs<RestoreResult.Restored>(BackupStore(db, pageSize = 2).restore(text.lineSequence(), RestoreMode.REPLACE, now, restoreAnyway = true))
        val expected = assertIs<DecodeResult.Success>(BackupCodec.decode(text, now, restoreAnyway = true)).snapshot
        assertEquals(expected, SnapshotStore(db).load())
        db.close()
    }

    @Test
    fun a_backup_from_the_first_version_restores() = runTest {
        val db = inMemoryDatabase()
        val v1 = V1_GOLDEN_JSON
        assertIs<RestoreResult.Restored>(BackupStore(db, pageSize = 1).restore(v1.lineSequence(), RestoreMode.REPLACE, now))
        assertEquals(assertIs<DecodeResult.Success>(BackupCodec.decode(v1, now)).snapshot, SnapshotStore(db).load())
        db.close()
    }

    @Test
    fun data_001_an_export_restore_would_refuse_fails_instead() = runTest {
        // Edited while the clock was ahead (4 Nov); the clock is corrected and the export is on 3 Nov.
        val edited = Instant.parse("2026-11-04T10:00:00Z")
        val exportAt = Instant.parse("2026-11-03T10:00:00Z")
        val e = withEntries(1).entries.single().copy(updatedAt = edited, createdAt = edited)
        val db = inMemoryDatabase()
        SnapshotStore(db).save(Snapshot.empty().copy(entries = listOf(e)))
        val result = BackupStore(db).export(meta.copy(exportedAt = exportAt)) {}
        val failure = assertIs<EncodeResult.Failure>(result)
        assertTrue("changed after the backup was made" in failure.reason, failure.reason)
        db.close()
    }

    @Test
    fun review_data_003_lookups_stay_within_old_androids_999_variables() = runTest {
        // A merge batch is 2,000 entries; each lookup of their current copies is one IN (...) statement.
        assertTrue(BackupStore.lookupChunks(List(2_000) { "$it" }).all { it.size <= 500 })
        assertEquals(2_000, BackupStore.lookupChunks(List(2_000) { "$it" }).sumOf { it.size })
        // And a merge over existing entries, in full batches, still gives the merge rule's result.
        val db = inMemoryDatabase()
        val current = withEntries(2_100)
        SnapshotStore(db).save(current)
        val backup = current.copy(entries = current.entries.map { it.copy(note = "newer", updatedAt = t + 5.minutes) })
        val text = BackupCodec.encode(backup, meta.copy(exportedAt = t + 10.minutes))
        assertIs<RestoreResult.Restored>(BackupStore(db).restore(text.lineSequence(), RestoreMode.MERGE, now))
        assertEquals(Restore.apply(current, backup, RestoreMode.MERGE), SnapshotStore(db).load())
        db.close()
    }
}
