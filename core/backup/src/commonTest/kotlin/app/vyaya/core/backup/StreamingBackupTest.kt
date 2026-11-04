package app.vyaya.core.backup

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The streaming writer gives the same file as the whole-snapshot writer, with the same limits. */
class StreamingBackupTest {

    private fun counts(l: SnapshotLines) = RecordCounts(
        l.accounts.size, l.categories.size, l.entries.size, l.recurring.size, l.people.size, l.ledger.size, l.budgets.size, l.merchantRules.size,
    )

    private fun stream(s: Snapshot, limits: BackupLimits = BackupLimits(), dropLast: Boolean = false): Pair<String, EncodeResult> {
        val lines = RecordLines.of(s.normalized())
        val out = StringBuilder()
        val (writer, failure) = StreamingBackup.begin(META_LATE, lines.settings, counts(lines), limits) { out.append(it).append('\n') }
        if (writer == null) return out.toString() to failure!!
        val records = lines.records.filter { it != lines.settings }.let { if (dropLast) it.dropLast(1) else it }
        records.forEach { writer.record(it)?.let { f -> return out.toString() to f } }
        val result = writer.finish()
        return out.toString() to result
    }

    @Test
    fun the_same_bytes_as_the_snapshot_writer() {
        for (s in listOf(GOLDEN_V3_SNAPSHOT) + (1L..30L).map { randomSnapshot(Random(it), generated = true) }) {
            val (text, result) = stream(s)
            assertIs<EncodeResult.Success>(result)
            assertEquals(BackupCodec.encode(s, META_LATE), text)
        }
    }

    @Test
    fun the_limits_are_the_snapshot_writers() {
        val s = GOLDEN_V3_SNAPSHOT
        val full = BackupCodec.encode(s, META_LATE)
        for (limits in listOf(
            BackupLimits(maxRecords = 1),
            BackupLimits(maxLineChars = 200),
            BackupLimits(maxTotalChars = full.length - 10L),
            BackupLimits(maxTotalChars = full.length / 2L),
        )) {
            val expected = BackupCodec.write(s, META_LATE, limits) {}
            assertIs<EncodeResult.Failure>(expected)
            assertEquals(expected, stream(s, limits).second, limits.toString())
        }
    }

    @Test
    fun records_missing_from_what_the_header_promised_fail() {
        val result = stream(GOLDEN_V3_SNAPSHOT, dropLast = true).second
        assertIs<EncodeResult.Failure>(result)
        assertTrue("changed while" in result.reason)
    }

    @Test
    fun unreadable_settings_fail() {
        val (writer, failure) = StreamingBackup.begin(META, "{", RecordCounts(0, 0, 0, 0, 0, 0, 0, 0)) {}
        assertNull(writer)
        assertIs<EncodeResult.Failure>(failure)
    }
}
