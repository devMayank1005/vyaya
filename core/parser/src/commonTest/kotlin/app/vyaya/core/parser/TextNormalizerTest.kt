package app.vyaya.core.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {

    @Test
    fun unicode_spaces_become_plain_spaces_and_runs_collapse() {
        // NBSP, narrow NBSP, thin space, ideographic space, tab, newline (DATA-002)
        assertEquals("₹ 120 debited", TextNormalizer.normalize("₹ 120  debited"))
        assertEquals("a b c", TextNormalizer.normalize("a\t\tb\n　c"))
    }

    @Test
    fun surrounding_space_is_trimmed() {
        assertEquals("Rs.120", TextNormalizer.normalize("  Rs.120 \n"))
    }

    @Test
    fun non_ascii_decimal_digits_become_ascii() {
        // Devanagari, Bengali, Arabic-Indic, fullwidth (DATA-011)
        assertEquals("₹120", TextNormalizer.normalize("₹१२०"))
        assertEquals("450", TextNormalizer.normalize("৪৫০"))
        assertEquals("45", TextNormalizer.normalize("٤٥"))
        assertEquals("120", TextNormalizer.normalize("１２０"))
    }

    @Test
    fun ordinary_text_is_unchanged() {
        val sms = "Rs.120.00 debited from A/c XX1234 on 26-10-26 to VPA swiggy@icici. UPI Ref 429112345678."
        assertEquals(sms, TextNormalizer.normalize(sms))
    }
}
