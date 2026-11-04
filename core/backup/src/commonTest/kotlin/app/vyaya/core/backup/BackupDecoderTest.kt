package app.vyaya.core.backup

import app.vyaya.core.model.Entry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A backup read a line at a time with its entries handed out as they are read (so a restore into the database
 * never holds them all, PEN-003) gives exactly what the whole-file reader gives.
 */
class BackupDecoderTest {

    /** Streams [text]; the entries taken along the way go back into the snapshot to compare. */
    private fun streamed(text: String, now: Instant, restoreAnyway: Boolean = false): DecodeResult {
        val d = BackupDecoder(now, restoreAnyway = restoreAnyway, streamEntries = true)
        val taken = mutableListOf<Entry>()
        for (line in text.lineSequence()) {
            val more = d.accept(line)
            taken += d.takeEntries()
            if (!more) break
        }
        val result = d.end()
        taken += d.takeEntries()
        return if (result is DecodeResult.Success) result.copy(snapshot = result.snapshot.copy(entries = result.snapshot.entries + taken).normalized())
        else result
    }

    @Test
    fun streamed_reading_gives_what_the_whole_file_reader_gives() {
        val cases = listOf(BackupCodec.encode(GOLDEN_V3_SNAPSHOT, META_LATE), GOLDEN_JSON, BackupCodec.encodeV2(GOLDEN_SNAPSHOT, META_LATE)) +
            (1L..40L).map { BackupCodec.encode(randomSnapshot(Random(it), generated = true), META_LATE) }
        for (text in cases) {
            val expected = BackupCodec.decode(text, LATE)
            assertIs<DecodeResult.Success>(expected)
            assertEquals(expected, streamed(text, LATE))
        }
    }

    @Test
    fun entries_are_handed_out_as_they_are_read_not_kept() {
        val text = BackupCodec.encode(randomSnapshot(Random(3), maxPerList = 30), META_LATE)
        val d = BackupDecoder(LATE, streamEntries = true)
        var most = 0
        for (line in text.lineSequence()) { d.accept(line); most = maxOf(most, d.takeEntries().size) }
        assertTrue(most <= 1, "at most the entry of the line just read is waiting")
        val result = assertIs<DecodeResult.Success>(d.end())
        assertEquals(emptyList(), result.snapshot.entries)
    }

    @Test
    fun restoring_anyway_streams_the_same_moved_dates() {
        val s = randomSnapshot(Random(9), generated = true)
        val ahead = Instant.parse("2099-06-01T00:00:00Z")
        val text = BackupCodec.encode(s, META.copy(exportedAt = ahead))
        val behind = Instant.parse("2040-01-01T00:00:00Z")
        val expected = BackupCodec.decode(text, behind, restoreAnyway = true)
        assertIs<DecodeResult.Success>(expected)
        assertEquals(expected, streamed(text, behind, restoreAnyway = true))
    }

    @Test
    fun the_same_failures_as_the_whole_file_reader() {
        val good = BackupCodec.encode(GOLDEN_V3_SNAPSHOT, META_LATE)
        val e = GOLDEN_V3_SNAPSHOT.entries.first()
        for (bad in listOf(
            "", good.replaceFirst("\"deleted\":false", "\"deleted\":true"), good.lines().dropLast(2).joinToString("\n"),
            good + "\n{\"t\":\"x\"}", good.replace(e.id.value, "00000000-0000-4000-8000-00000000abcd"),
            BackupCodec.encode(GOLDEN_V3_SNAPSHOT, META_LATE).replace("\"schemaVersion\":3", "\"schemaVersion\":9"),
        )) {
            assertEquals(BackupCodec.decode(bad, LATE), streamed(bad, LATE), bad.take(80))
        }
    }
}
