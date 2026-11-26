package app.vyaya.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.vyaya.ui.BackupScreen
import app.vyaya.ui.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backlog 2026-11-26 #2: after a merge onto a phone still on the defaults, the backup's settings are offered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun backlog_flaw_002_the_choice_is_asked_and_answered() {
        val answers = mutableListOf<Boolean>()
        compose.setContent {
            BackupScreen(BackupStatus.OfferSettings("Use the backup's settings? There your month starts on the 25th."), {}, {}, {},
                onSettingsChoice = { answers += it })
        }
        compose.onNodeWithText("Restored", substring = true).assertExists()
        compose.onNodeWithText("Use the backup's settings? There your month starts on the 25th.").assertExists()
        compose.onNodeWithText("Use the backup's").performClick()
        compose.onNodeWithText("Keep this phone's").performClick()
        assertEquals(listOf(true, false), answers)
    }
}
