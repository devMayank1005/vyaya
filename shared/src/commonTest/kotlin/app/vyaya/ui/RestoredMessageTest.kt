package app.vyaya.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Backlog 2026-11-26 #5: a restore says when it removed hidden characters from the backup's text. */
class RestoredMessageTest {
    @Test
    fun backlog_pen_001_the_message_says_what_was_cleaned() {
        assertEquals("Restored", restoredMessage(0))
        assertEquals("Restored. 1 name or note had hidden characters, removed.", restoredMessage(1))
        assertEquals("Restored. 3 names or notes had hidden characters, removed.", restoredMessage(3))
    }
}
