package app.vyaya.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.vyaya.ui.AddEntryScreen
import app.vyaya.ui.EntryInput
import app.vyaya.ui.HomeScreen
import app.vyaya.core.data.MonthView
import app.vyaya.core.engine.MonthSummary
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.DefaultCategories
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The home screen and the add form (Compose UI tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {

    @get:Rule val compose = createComposeRule()

    private val ist = TimeZone.of("Asia/Kolkata")
    private val t = Instant.parse("2026-11-05T06:00:00Z")
    private val categories = DefaultCategories.create(t)
    private val food = categories.first { it.name == "Food & dining" }
    private val month = MonthSchedule.of(1).monthContaining(LocalDate(2026, 11, 6))

    private fun entry(type: EntryType, paise: Long, payee: String?, category: EntityId?) = Entry(EntityId.random(), type, Money(paise),
        CapturedAt(t, ist), null, null, category, payee, "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)

    private fun view(vararg entries: Entry) = MonthView(month, MonthSummary.of(entries.toList(), MonthSchedule.of(1), month), entries.toList())

    @Test
    fun the_home_screen_shows_whats_left_in_and_out_and_the_months_entries() {
        val v = view(entry(EntryType.INCOME, 50_000_00, "Employer", null), entry(EntryType.EXPENSE, 250_50, "Swiggy", food.id))
        var previous = 0; var next = 0; var added = 0
        compose.setContent { HomeScreen(v, categories.associate { it.id to it.name }, { previous++ }, { next++ }, { added++ }, {}) }
        compose.onNodeWithText("November 2026").assertIsDisplayed()
        compose.onNodeWithText("₹49,749.50").assertIsDisplayed() // left
        compose.onNodeWithText("In ₹50,000").assertIsDisplayed()
        compose.onNodeWithText("Out ₹250.50").assertIsDisplayed()
        compose.onNodeWithText("Swiggy").assertIsDisplayed()
        compose.onNodeWithText("Food & dining", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        assertEquals(listOf(1, 1, 1), listOf(previous, next, added))
    }

    @Test
    fun an_empty_month_says_so() {
        compose.setContent { HomeScreen(view(), emptyMap(), {}, {}, {}, {}) }
        compose.onNodeWithText("No entries this month").assertIsDisplayed()
        compose.onNodeWithText("₹0").assertIsDisplayed()
    }

    @Test
    fun the_add_form_saves_what_was_typed_and_refuses_a_missing_amount() {
        val saved = mutableListOf<EntryInput>()
        compose.setContent { AddEntryScreen(null, categories, LocalDate(2026, 11, 6), onSave = { saved += it; true }, onDelete = {}, onClose = {}) }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter an amount", substring = true).assertIsDisplayed()
        assertEquals(emptyList<EntryInput>(), saved)
        compose.onNodeWithText("Amount").performTextInput("250.50")
        compose.onNodeWithText("Food & dining").performClick()
        compose.onNodeWithText("Payee").performTextInput("Swiggy")
        compose.onNodeWithText("Save").performClick()
        assertEquals(EntryInput(EntryType.EXPENSE, "250.50", food.id, "Swiggy", "", LocalDate(2026, 11, 6)), saved.single())
    }

    @Test
    fun the_add_form_can_record_income() {
        val saved = mutableListOf<EntryInput>()
        compose.setContent { AddEntryScreen(null, categories, LocalDate(2026, 11, 6), onSave = { saved += it; true }, onDelete = {}, onClose = {}) }
        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithText("Amount").performTextInput("50000")
        compose.onNodeWithText("Salary").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(EntryType.INCOME, saved.single().type)
    }

    @Test
    fun a_recurring_entry_shows_where_it_comes_from_and_long_text_stops_at_the_limit() {
        val k = app.vyaya.core.model.Generation(EntityId.random(), kotlinx.datetime.YearMonth(2026, 11))
        val rent = Entry(k.entryId, EntryType.EXPENSE, Money(15_000_00), CapturedAt(Instant.parse("2026-11-01T06:30:00Z"), ist), null, null, null, null,
            "Rent", emptyList(), EntrySource.RECURRING, null, emptyList(), null, t, t, false, k)
        val saved = mutableListOf<EntryInput>()
        compose.setContent { AddEntryScreen(rent, categories, LocalDate(2026, 11, 6), onSave = { saved += it; true }, onDelete = {}, onClose = {}) }
        compose.onNodeWithText("From a recurring rule", substring = true).assertExists()
        compose.onNodeWithText("Payee").performTextInput("y".repeat(150))
        compose.onNodeWithText("Save").performClick()
        assertEquals(100, saved.single().payee.length)
    }

    @Test
    fun a_finished_month_says_what_it_saved_or_overspent() {
        val saved = view(entry(EntryType.INCOME, 10_000_00, "Employer", null), entry(EntryType.EXPENSE, 4_000_00, "Rent", null))
        val end = app.vyaya.core.engine.MonthEnd.of(saved.entries, MonthSchedule.of(1), month, LocalDate(2026, 12, 3))
        compose.setContent { HomeScreen(saved, emptyMap(), {}, {}, {}, {}, monthEnd = end) }
        compose.onNodeWithText("Saved ₹6,000", substring = true).assertExists()
    }

    @Test
    fun an_overspent_month_says_by_how_much() {
        val over = view(entry(EntryType.INCOME, 5_000_00, "Employer", null), entry(EntryType.EXPENSE, 7_000_00, "Rent", null))
        val end = app.vyaya.core.engine.MonthEnd.of(over.entries, MonthSchedule.of(1), month, LocalDate(2026, 12, 3))
        compose.setContent { HomeScreen(over, emptyMap(), {}, {}, {}, {}, monthEnd = end) }
        compose.onNodeWithText("Overspent ₹2,000", substring = true).assertExists()
    }

    @Test
    fun the_current_month_shows_saved_so_far() {
        val savings = app.vyaya.core.data.SavingsSummary(Money(6_000_00), Money(2_000_00), 2, LocalDate(2026, 9, 1))
        compose.setContent { HomeScreen(view(), emptyMap(), {}, {}, {}, {}, savings = savings) }
        compose.onNodeWithText("Saved so far ₹6,000 over 2 months", substring = true).assertExists()
        compose.onNodeWithText("overspent ₹2,000", substring = true).assertExists() // MVP2 FLAW-002: both, not a flattering half
    }
}
