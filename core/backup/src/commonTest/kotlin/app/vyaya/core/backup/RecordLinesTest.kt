package app.vyaya.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One record as its exact backup line, and back: what the database stores in each row (core/data). */
class RecordLinesTest {

    @Test
    fun an_entry_line_is_exactly_the_line_a_backup_writes() {
        val backupLines = BackupCodec.encode(GOLDEN_V3_SNAPSHOT, META).lines()
        for (e in GOLDEN_V3_SNAPSHOT.entries) {
            val line = RecordLines.entry(e)
            assertEquals(1, backupLines.count { it == line }, line)
            assertEquals(e, RecordLines.entryOf(line))
        }
    }

    @Test
    fun a_damaged_or_invalid_line_gives_nothing() {
        val line = RecordLines.entry(GOLDEN_V3_SNAPSHOT.entries.first())
        assertNull(RecordLines.entryOf(line.dropLast(1)), "not JSON")
        assertNull(RecordLines.entryOf(line.replace("\"amountPaise\":12050", "\"amountPaise\":-1")), "invalid amount")
        assertNull(RecordLines.entryOf(line.replace("\"t\":\"entry\"", "\"t\":\"person\"")), "another record type")
        assertNull(RecordLines.entryOf(line.replace("\"deleted\":false", "\"deleted\":false,\"x\":1")), "unknown field")
    }

    @Test
    fun every_record_of_a_snapshot_is_its_backup_line_and_reads_back() {
        for (snapshot in listOf(GOLDEN_V3_SNAPSHOT) + (1L..50L).map { randomSnapshot(kotlin.random.Random(it), generated = true) }) {
            val lines = RecordLines.of(snapshot)
            val backup = BackupCodec.encode(snapshot, META_LATE).lines().toSet()
            assertTrue(lines.records.all { it in backup }, "each record line is exactly a backup line")
            assertEquals(snapshot.normalized(), RecordLines.snapshotOf(lines))
        }
    }

    @Test
    fun a_snapshot_with_any_bad_line_is_not_read() {
        val lines = RecordLines.of(GOLDEN_V3_SNAPSHOT)
        assertNull(RecordLines.snapshotOf(lines.copy(people = lines.people.map { it.replace("\"Rahul\"", "\"\"") })), "invalid name")
        assertNull(RecordLines.snapshotOf(lines.copy(settings = "{")), "bad settings")
        assertNull(RecordLines.snapshotOf(lines.copy(budgets = lines.budgets + lines.people.first())), "a person among budgets")
        // A damaged line in any type's list.
        val bad = "{\"t\":\"x\"}"
        for (broken in listOf(lines.copy(accounts = lines.accounts + bad), lines.copy(categories = lines.categories + bad),
            lines.copy(entries = lines.entries + bad), lines.copy(recurring = lines.recurring + bad), lines.copy(people = lines.people + bad),
            lines.copy(ledger = lines.ledger + bad), lines.copy(merchantRules = lines.merchantRules + bad), lines.copy(accounts = lines.accounts + "{"))) {
            assertNull(RecordLines.snapshotOf(broken))
        }
        // Settings that read but aren't valid (unknown time zone).
        assertNull(RecordLines.snapshotOf(lines.copy(settings = lines.settings.replace("Asia/Kolkata", "Mars/Olympus"))))
    }

    @Test
    fun branch_flaw_008_reading_the_databases_rows_builds_no_export_time_problems() {
        // The database's rows have no export time: none of them can be "changed after the backup was made".
        val reader = Reader(kotlin.time.Instant.fromEpochSeconds(0), checkExportTime = false)
        val content = BackupCodec.toContent(GOLDEN_V3_SNAPSHOT, META)
        reader.header("INR", "2000-01-01T00:00:00Z", content.settings)
        content.people.forEach(reader::person); content.categories.forEach(reader::category)
        assertEquals(0, reader.problemCount)
    }
}
