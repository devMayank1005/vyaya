package app.vyaya.core.backup

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CsvExportTest {

    private val header = "date,time,type,amount,category,account,payee,note,tags,source,upi_ref,id"

    private fun rows(snapshot: Snapshot): List<String> {
        val csv = CsvExport.export(snapshot)
        assertTrue(csv.startsWith("﻿"), "UTF-8 byte order mark so Excel shows ₹ and Hindi correctly")
        return csv.removePrefix("﻿").removeSuffix("\r\n").split("\r\n")
    }

    private fun entry(payee: String?, note: String = "", at: String = "2026-10-26T13:05:00Z", deleted: Boolean = false, id: EntityId = EntityId.random()) =
        Entry(id, EntryType.EXPENSE, Money(4500), CapturedAt(Instant.parse(at), IST), null, null, FOOD, payee, note, emptyList(),
            EntrySource.MANUAL, null, emptyList(), null, T, T, deleted)

    @Test
    fun header_and_one_row_per_entry_in_the_entry_s_own_local_time() {
        val lines = rows(GOLDEN_SNAPSHOT)
        assertEquals(header, lines[0])
        // 13:05 UTC is 18:35 in India; the split is spelled out; amount has two decimals and no grouping.
        assertEquals(
            "2026-10-26,18:35,Expense,120.50,Food 100.50 + Saved 20.00,HDFC Salary,Swiggy,lunch,office,Notification,429112345678,00000000-0000-4000-8000-000000000004",
            lines[1],
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun an_empty_snapshot_is_just_the_header() {
        assertEquals(listOf(header), rows(Snapshot.empty()))
    }

    @Test
    fun deleted_entries_are_left_out_and_rows_are_in_time_order() {
        val late = entry("Late", at = "2026-10-27T10:00:00Z")
        val early = entry("Early", at = "2026-10-01T10:00:00Z")
        val gone = entry("Gone", deleted = true)
        val lines = rows(GOLDEN_SNAPSHOT.copy(entries = listOf(late, gone, early)))
        assertEquals(listOf("Early", "Late"), lines.drop(1).map { it.split(",")[6] })
    }

    @Test
    fun commas_quotes_and_line_breaks_are_quoted() {
        val lines = rows(GOLDEN_SNAPSHOT.copy(entries = listOf(entry("Sharma, Sons", note = "said \"hi\"\nthen left"))))
        assertTrue(lines[1].contains(",\"Sharma, Sons\",\"said \"\"hi\"\"\nthen left\","), lines[1])
    }

    @Test
    fun cells_that_spreadsheets_would_run_as_formulas_are_neutralised() {
        listOf("=HYPERLINK(\"http://evil\",\"x\")", "+911234", "-2+3", "@SUM(A1)", "\tTAB", "\rCR").forEach { payee ->
            val cell = rows(GOLDEN_SNAPSHOT.copy(entries = listOf(entry(payee)))).last()
            val expected = CsvExport.cell("'$payee")
            assertTrue(cell.contains(",$expected,"), "payee=$payee row=$cell")
        }
    }

    @Test
    fun missing_names_are_blank_and_unknown_ids_never_crash() {
        val orphan = Entry(EntityId.random(), EntryType.INCOME, Money(100), CapturedAt(T, TimeZone.UTC), EntityId.random(), null,
            EntityId.random(), null, "", emptyList(), EntrySource.SMS, null, emptyList(), null, T, T, false)
        val cells = rows(Snapshot.empty().copy(entries = listOf(orphan)))[1].split(",")
        assertEquals(listOf("Income", "1.00", "", "", ""), cells.subList(2, 7))
    }
}
