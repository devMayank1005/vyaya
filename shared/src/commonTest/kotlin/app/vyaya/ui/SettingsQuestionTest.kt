package app.vyaya.ui

import app.vyaya.core.model.Settings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Backlog 2026-11-26 #2: the question after a merge onto a phone still on the default settings. */
class SettingsQuestionTest {
    @Test
    fun backlog_flaw_002_the_question_names_both_months_and_zones() {
        val today = LocalDate(2026, 11, 26)
        val here = Settings.default()
        val there = here.copy(homeTimeZone = TimeZone.of("America/New_York"), monthSchedule = here.monthSchedule.withStartDay(25, LocalDate(2026, 10, 1)))
        assertEquals("Use the backup's settings? There your month starts on the 25th, in America/New_York; here on the 1st, in Asia/Kolkata.",
            settingsQuestion(there, here, today))
    }
}
