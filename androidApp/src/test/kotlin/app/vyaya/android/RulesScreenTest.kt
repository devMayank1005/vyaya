package app.vyaya.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.vyaya.android.ui.RuleForm
import app.vyaya.android.ui.RuleInput
import app.vyaya.android.ui.RulesScreen
import app.vyaya.android.ui.Setup
import app.vyaya.android.ui.VyayaRoot
import app.vyaya.core.data.RulesStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.DefaultCategories
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The Recurring tab: rules listed, added, paused and resumed (asking about missed months). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RulesScreenTest {

    @get:Rule val compose = createComposeRule()
    private val t = Instant.parse("2026-09-01T04:30:00Z")
    private val categories = DefaultCategories.create(t)
    private fun rule(note: String, paise: Long, day: Int, paused: Boolean = false) =
        RecurringRule(EntityId.random(), EntryType.EXPENSE, Money(paise), day, null, null, note, paused, LocalDate(2026, 9, day), t, false)

    @Test
    fun rules_are_listed_with_amount_day_and_paused() {
        val rent = rule("Rent", 15_000_00, 5); val gym = rule("Gym", 1_500_00, 1, paused = true)
        val resumed = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            RulesScreen(listOf(rent, gym), emptyMap(), onAdd = {}, onOpen = {}, onPause = {},
                missed = { if (it == gym) listOf(YearMonth(2026, 10), YearMonth(2026, 11)) else emptyList() },
                onResume = { r, add -> resumed += r.note to add })
        }
        compose.onNodeWithText("Rent").assertExists()
        compose.onNodeWithText("₹15,000 · on day 5", substring = true).assertExists()
        compose.onNodeWithText("Paused", substring = true).assertExists()
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("2 months were missed while it was paused", substring = true).assertExists()
        compose.onNodeWithText("Skip them").performClick()
        assertEquals(listOf("Gym" to false), resumed)
    }

    @Test
    fun the_rule_form_checks_its_fields_and_keeps_an_existing_rules_type() {
        val saved = mutableListOf<RuleInput>()
        compose.setContent { RuleForm(null, categories, onSave = { saved += it; true }, onDelete = {}, onClose = {}) }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter an amount", substring = true).assertExists()
        compose.onNodeWithText("Amount").performTextInput("15000")
        compose.onNodeWithText("Day of the month").performTextInput("32")
        compose.onNodeWithText("Name").performTextInput("Rent")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("A day from 1 to 31", substring = true).assertExists()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun an_existing_rule_keeps_its_type() {
        compose.setContent { RuleForm(rule("Rent", 15_000_00, 5), categories, onSave = { true }, onDelete = {}, onClose = {}) }
        compose.onNodeWithText("type stays", substring = true).assertExists()
    }

    @Test
    fun a_rule_added_in_the_app_is_saved_and_listed() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        var changed = 0
        compose.setContent {
            VyayaRoot(db, Setup(true, false, false), clock = { t }, opened = 1, onNotificationAccess = {}, onSms = {}, onImport = {},
                onRulesChanged = { changed++ })
        }
        compose.onNodeWithText("Recurring").performClick()
        compose.onNodeWithText("Add rule").performClick()
        compose.onNodeWithText("Amount").performTextInput("15000")
        compose.onNodeWithText("Day of the month").performTextInput("5")
        compose.onNodeWithText("Name").performTextInput("Rent")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Rent")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Money(15_000_00), runBlocking { RulesStore(db).active() }.single().amount)
        assertTrue(changed >= 1)
        db.close()
    }
}
