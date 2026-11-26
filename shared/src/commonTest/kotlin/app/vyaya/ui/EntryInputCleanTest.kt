package app.vyaya.ui

import app.vyaya.core.model.EntryType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** core-backup SEC-010: what is typed into the entry form is saved without invisible or control characters. */
class EntryInputCleanTest {
    @Test
    fun sec_010_payee_and_note_are_saved_clean() {
        val input = EntryInput(EntryType.EXPENSE, "250", null, "‮yggiws ", "Lunch\u0000 with​ team", LocalDate(2026, 11, 26), null)
        val e = input.toEntry(null, Instant.parse("2026-11-26T06:00:00Z"), TimeZone.of("Asia/Kolkata"))!!
        assertEquals("yggiws" to "Lunch with team", e.payee to e.note)
    }
}
