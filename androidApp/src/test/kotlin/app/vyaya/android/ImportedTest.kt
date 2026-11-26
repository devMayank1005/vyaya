package app.vyaya.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** android-capture FLAW-009: what the 30-day import says it did. */
class ImportedTest {
    @Test
    fun capture_flaw_009_the_message_counts_added_and_to_check() {
        assertEquals("12 added, 3 to check in Detected", Imported(12, 3).message())
        assertEquals("12 payments added from the last 30 days", Imported(12, 0).message())
        assertEquals("1 payment added from the last 30 days", Imported(1, 0).message())
        assertEquals("Nothing added; 2 to check in Detected", Imported(0, 2).message())
        assertEquals("No new payments in the last 30 days", Imported(0, 0).message())
    }
}
