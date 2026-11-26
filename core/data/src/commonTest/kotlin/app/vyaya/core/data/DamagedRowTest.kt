package app.vyaya.core.data

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * core-data DATA-006: one row that no longer reads back (a damaged database) is left out and counted, never fatal; the
 * rest can still be exported.
 */
class DamagedRowTest {

    private val t = Instant.parse("2026-11-26T06:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    private fun entry(rupees: Long, payee: String, category: EntityId? = null) = Entry(EntityId.random(), EntryType.EXPENSE, Money(rupees * 100),
        CapturedAt(t, ist), null, null, category, payee, "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)

    @Test
    fun data_006_a_damaged_entry_and_record_are_left_out_and_counted_and_the_app_still_opens() = runTest {
        val db = inMemoryDatabase()
        AppOpen(db).run(t)
        val snacks = (CatalogStore(db).addCategory("Snacks", CategoryKind.EXPENSE, t) as CatalogResult.Saved).item
        val good = entry(250, "Swiggy"); val broken = entry(90, "Chai")
        EntryStore(db).save(listOf(good, broken))
        db.entries().upsert(listOf(EntryStore.row(broken).copy(line = "{\"t\":\"entry\",\"broken")))
        db.records().upsert(listOf(RecordRow("category", snacks.id.value, "{\"t\":\"category\",\"id\":\"not an id\"}", false, 0)))
        assertEquals(0, db.damaged.value.size)

        AppOpen(db).run(t + 1.hours) // must not throw
        assertEquals(listOf(good.id), EntryStore(db).all().map { it.id })
        assertTrue(snacks.id !in CatalogStore(db).categories().map { it.id })
        assertEquals(setOf("entry:${broken.id.value}", "category:${snacks.id.value}"), db.damaged.value)
        // Reading again doesn't count them twice.
        EntryStore(db).all(); SnapshotStore(db).load()
        assertEquals(2, db.damaged.value.size)
        db.close()
    }

    @Test
    fun data_006_the_rest_exports_even_when_an_entry_points_at_a_damaged_record() = runTest {
        val db = inMemoryDatabase()
        AppOpen(db).run(t)
        val snacks = (CatalogStore(db).addCategory("Snacks", CategoryKind.EXPENSE, t) as CatalogResult.Saved).item
        val samosa = entry(40, "Samosa", snacks.id)
        EntryStore(db).save(listOf(samosa))
        db.records().upsert(listOf(RecordRow("category", snacks.id.value, "{broken", false, 0)))

        val exportable = SnapshotStore(db).loadForExport()
        val text = BackupCodec.encode(exportable, ExportMeta("1", "android", t + 1.hours))
        val back = assertIs<DecodeResult.Success>(BackupCodec.decode(text, t + 1.hours)).snapshot
        assertEquals(null, back.entries.single { it.id == samosa.id }.categoryId, "kept, without the category that can't be read")
        db.close()
    }

    @Test
    fun data_006_a_damaged_settings_row_falls_back_to_the_defaults() = runTest {
        val db = inMemoryDatabase()
        AppOpen(db).run(t)
        db.records().upsert(listOf(RecordRow(SnapshotStore.SETTINGS, SnapshotStore.SETTINGS, "{broken", false, 0)))
        assertEquals(app.vyaya.core.model.Settings.default(), SnapshotStore(db).loadRecords().settings)
        assertEquals(setOf("settings:settings"), db.damaged.value)
        db.close()
    }

    @Test
    fun an_undamaged_database_reports_nothing() = runTest {
        val db = inMemoryDatabase()
        AppOpen(db).run(t)
        EntryStore(db).save(listOf(entry(250, "Swiggy")))
        SnapshotStore(db).load(); EntryStore(db).all()
        assertEquals(emptySet(), db.damaged.value)
        assertEquals(SnapshotStore(db).load(), SnapshotStore(db).loadForExport())
        db.close()
    }

    @Test
    fun data_006_restoring_a_backup_with_merge_repairs_a_damaged_entry() = runTest {
        val db = inMemoryDatabase()
        AppOpen(db).run(t)
        val chai = entry(90, "Chai")
        EntryStore(db).save(listOf(chai))
        val backup = BackupCodec.encode(SnapshotStore(db).load(), ExportMeta("1", "android", t + 1.hours))
        db.entries().upsert(listOf(EntryStore.row(chai).copy(line = "{broken")))
        EntryStore(db).all()
        assertEquals(1, db.damaged.value.size)
        val result = BackupStore(db).restore(backup.lineSequence(), app.vyaya.core.backup.RestoreMode.MERGE, t + 2.hours)
        assertIs<RestoreResult.Restored>(result)
        assertEquals(chai, EntryStore(db).byId(chai.id), "the backup's copy replaces the row that couldn't be read")
        EntryStore(db).all(); SnapshotStore(db).load()
        assertEquals(emptySet(), db.damaged.value, "repaired: Home stops saying so")
        db.close()
    }
}
