package app.vyaya.android

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app as it starts (branch review 2026-11-18 #4: the Android glue had no test): the activity opens on Home, and
 * the export buttons hand over to the system's file picker with the right kind of file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun the_app_opens_on_home() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Left")
            compose.onNodeWithText("Home").assertExists()
        }
    }

    @Test
    fun the_spreadsheet_export_asks_the_system_for_a_csv_file() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("Left")
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Export spreadsheet (CSV)").performScrollTo().performClick()
            // A phone without a screen lock (Robolectric's) is asked first (csv SEC-004).
            waitFor("Export anyway")
            compose.onNodeWithText("Export anyway").performClick()
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val asked = shadowOf(activity).nextStartedActivityForResult?.intent ?: shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked?.action)
                assertEquals("text/csv", asked?.type)
                assertTrue(asked?.getStringExtra(Intent.EXTRA_TITLE).orEmpty().startsWith("vyaya-entries-"))
            }
        }
    }
}
