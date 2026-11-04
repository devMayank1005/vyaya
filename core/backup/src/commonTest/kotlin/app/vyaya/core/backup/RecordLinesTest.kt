package app.vyaya.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
