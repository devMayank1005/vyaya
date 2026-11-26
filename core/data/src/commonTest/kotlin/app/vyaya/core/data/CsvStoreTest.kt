package app.vyaya.core.data

import app.vyaya.core.backup.CsvExport
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
import androidx.room.useReaderConnection
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The spreadsheet export read from the database page by page: the same file as [CsvExport] makes from a snapshot. */
class CsvStoreTest {

    private val t = Instant.parse("2026-11-10T10:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    private suspend fun export(db: VyayaDatabase, pageSize: Int = 500): Pair<String, Int> {
        val out = StringBuilder()
        val written = CsvStore(db, pageSize).export { out.append(it) }
        assertEquals(0, written.skipped)
        return out.toString() to written.rows
    }

    @Test
    fun the_database_export_is_the_codecs_file_across_pages() = runTest {
        val db = inMemoryDatabase()
        val random = Random(7)
        val food = Category(EntityId.random(), "Food", CategoryKind.EXPENSE, t, false)
        val gone = Category(EntityId.random(), "Old stuff", CategoryKind.EXPENSE, t, true)
        val hdfc = Account(EntityId.random(), "HDFC", AccountKind.BANK, "1234", t, false)
        val entries = List(1_234) { i ->
            // Few distinct times, so many entries share one: the pages must split ties by id, not lose or repeat them.
            val at = t + (random.nextInt(40) * 7).minutes
            val paise = random.nextLong(1_000, 10_000_00)
            Entry(
                EntityId.random(), if (i % 9 == 0) EntryType.INCOME else EntryType.EXPENSE, Money(paise),
                CapturedAt(at, if (i % 2 == 0) ist else TimeZone.UTC), hdfc.id.takeIf { i % 3 == 0 }, null,
                listOf(food.id, gone.id, null)[i % 3], "Payee $i", if (i % 5 == 0) "=1+1" else "", emptyList(),
                listOf(EntrySource.MANUAL, EntrySource.SMS, EntrySource.NOTIFICATION)[i % 3], null,
                if (i % 50 == 0) listOf(Split(food.id, Money(paise - 200)), Split(SystemCategories.SAVED.id, Money(200))) else emptyList(),
                null, t, t, deleted = i % 11 == 0,
            )
        }
        SnapshotStore(db).save(Snapshot.empty().copy(categories = listOf(food, gone), accounts = listOf(hdfc), entries = entries))
        val expected = CsvExport.export(SnapshotStore(db).load())
        val (csv, rows) = export(db, pageSize = 7)
        assertEquals(expected, csv)
        assertEquals(entries.count { !it.deleted }, rows)
        assertEquals(expected, export(db).first, "the app's page size")
        db.close()
    }

    @Test
    fun an_empty_database_is_the_header_only() = runTest {
        val db = inMemoryDatabase()
        assertEquals(CsvExport.export(Snapshot.empty()) to 0, export(db))
        db.close()
    }

    @Test
    fun catalog_sec_010_category_and_account_names_cannot_run_as_formulas() = runTest {
        val db = inMemoryDatabase()
        val evil = Category(EntityId.random(), "=HYPERLINK(\"http://x\",\"Food\")", CategoryKind.EXPENSE, t, false)
        val card = Account(EntityId.random(), "@SUM(A1)", AccountKind.CARD, null, t, false)
        val e = Entry(EntityId.random(), EntryType.EXPENSE, Money(100_00), CapturedAt(t, ist), card.id, null, evil.id, "+91 Shop", "-tip",
            emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)
        SnapshotStore(db).save(Snapshot.empty().copy(categories = listOf(evil), accounts = listOf(card), entries = listOf(e)))
        val row = export(db).first.split("\r\n")[1]
        assertTrue(row.contains(",\"'=HYPERLINK(\"\"http://x\"\",\"\"Food\"\")\",'@SUM(A1),,'+91 Shop,'-tip,"), row)
        db.close()
    }

    @Test
    fun csv_sim_001_each_page_comes_from_an_index_without_sorting() = runTest {
        val db = inMemoryDatabase()
        val plan = db.useReaderConnection { c ->
            c.usePrepared("EXPLAIN QUERY PLAN $PAGE_BY_TIME") { st -> buildList { while (st.step()) add(st.getText(3)) } }
        }
        assertTrue(plan.none { "TEMP B-TREE" in it }, "a page must not sort the rest of the table: $plan")
        db.close()
    }

    @Test
    fun csv_pen_003_a_damaged_row_is_left_out_and_counted_not_a_crash() = runTest {
        val db = inMemoryDatabase()
        val good = Entry(EntityId.random(), EntryType.EXPENSE, Money(100_00), CapturedAt(t, ist), null, null, null, "Shop", "",
            emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)
        SnapshotStore(db).save(Snapshot.empty().copy(entries = listOf(good)))
        val expected = CsvExport.export(SnapshotStore(db).load())
        db.entries().upsert(listOf(EntryRow(id = "00000000-0000-4000-8000-000000000009", line = """{"t":"entry"}""", occurredAtMs = 5,
            amountPaise = 1, type = "EXPENSE", upiRef = null, payeeKey = null, ruleId = null, month = null, deleted = false, updatedAtMs = 1, createdAtMs = 1)))
        val out = StringBuilder()
        assertEquals(CsvStore.Written(rows = 1, skipped = 1), CsvStore(db).export { out.append(it) })
        assertEquals(expected, out.toString())
        db.close()
    }
}
