package app.vyaya.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * core-backup SEC-010: text shown or exported has no invisible format or control characters. A right-to-left override
 * can make "‮yggiws" look like "swiggy", and a null byte breaks a spreadsheet.
 */
class CleanTextTest {

    @Test
    fun bidi_overrides_zero_widths_and_control_characters_go() {
        assertEquals("yggiws", "‮yggiws".cleanText())
        assertEquals("Swiggy", "Swi​ggy﻿".cleanText())
        assertEquals("Chai note", "Chai\u0000 note\u0007".cleanText())
        assertEquals("a b", "a⁦ b⁩".cleanText())
    }

    @Test
    fun indian_scripts_keep_their_joiners_and_marks() {
        // Devanagari and Malayalam rely on the zero-width joiner and non-joiner, and on combining marks.
        listOf("क्‍ष", "സ്‌", "चाय वाला", "தமிழ்", "Café").forEach { assertEquals(it, it.cleanText()) }
    }

    @Test
    fun line_breaks_and_tabs_stay() {
        assertEquals("line one\nline two", "line one\nline two".cleanText())
        assertEquals("a\tb\r\n", "a\tb\r\n".cleanText())
    }

    @Test
    fun clean_text_stays_as_it_is_and_cleaning_twice_changes_nothing() = runTest {
        checkAll(500, Arb.string(0..40)) { s ->
            val once = s.cleanText()
            assertEquals(once, once.cleanText())
        }
        assertEquals("Barbeque Nation ₹1,200", "Barbeque Nation ₹1,200".cleanText())
    }
}
