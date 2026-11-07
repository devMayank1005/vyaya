package app.vyaya.android

import app.vyaya.android.ui.EntryInput
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Instant

/** What the add/edit form saves (plain logic). */
class EntryInputTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-06T09:15:00Z") // 14:45 IST
    private val food = EntityId.random()

    @Test
    fun a_new_entry_today_is_manual_and_happens_now() {
        val e = EntryInput(EntryType.EXPENSE, "250.50", food, "Swiggy", "lunch", LocalDate(2026, 11, 6)).toEntry(null, now, ist)!!
        assertEquals(Money(250_50), e.amount)
        assertEquals(EntrySource.MANUAL, e.source)
        assertEquals(now, e.occurredAt.instant)
        assertEquals(ist, e.occurredAt.timeZone)
        assertEquals(food, e.categoryId)
        assertEquals("Swiggy", e.payee)
        assertEquals(now, e.createdAt)
    }

    @Test
    fun another_day_is_at_noon_and_a_blank_payee_is_none() {
        val e = EntryInput(EntryType.INCOME, "₹50,000", null, "  ", "", LocalDate(2026, 11, 1)).toEntry(null, now, ist)!!
        assertEquals("2026-11-01T12:00", e.occurredAt.instant.toLocalDateTime(ist).toString())
        assertNull(e.payee)
        assertEquals(EntryType.INCOME, e.type)
    }

    @Test
    fun an_edit_keeps_what_the_entry_is_and_marks_the_change() {
        val first = EntryInput(EntryType.EXPENSE, "100", food, "Chai", "", LocalDate(2026, 11, 6)).toEntry(null, now, ist)!!
        val later = Instant.parse("2026-11-06T10:00:00Z")
        val edited = EntryInput(EntryType.EXPENSE, "120", food, "Chai", "with snacks", LocalDate(2026, 11, 6)).toEntry(first, later, ist)!!
        assertEquals(first.id, edited.id)
        assertEquals(first.createdAt, edited.createdAt)
        assertEquals("same day: the time stays", first.occurredAt, edited.occurredAt)
        assertEquals(later, edited.updatedAt)
        assertEquals(Money(120_00), edited.amount)
    }

    @Test
    fun an_amount_that_isnt_one_saves_nothing() {
        for (bad in listOf("", "abc", "0", "-5", "12.345", "99999999999")) {
            assertNull(bad, EntryInput(EntryType.EXPENSE, bad, null, "", "", LocalDate(2026, 11, 6)).toEntry(null, now, ist))
        }
    }

    private val rent = app.vyaya.core.model.Generation(EntityId.random(), kotlinx.datetime.YearMonth(2026, 11)).let { k ->
        app.vyaya.core.model.Entry(k.entryId, EntryType.EXPENSE, Money(15_000_00), app.vyaya.core.model.CapturedAt(Instant.parse("2026-11-01T06:30:00Z"), ist),
            null, null, null, null, "Rent", emptyList(), EntrySource.RECURRING, null, emptyList(), null, now, now, false, k)
    }

    @Test
    fun mvp_data_001_a_recurring_entry_keeps_its_type() {
        val edited = EntryInput(EntryType.INCOME, "16000", null, "", "Rent", LocalDate(2026, 11, 1)).toEntry(rent, now, ist)!!
        assertEquals(EntryType.EXPENSE, edited.type)
        assertEquals(Money(16_000_00), edited.amount)
    }

    @Test
    fun mvp_flaw_004_005_what_is_wrong_is_said() {
        fun problem(i: EntryInput, existing: app.vyaya.core.model.Entry? = null) = i.problem(existing, ist)
        val ok = EntryInput(EntryType.EXPENSE, "100", null, "Chai", "", LocalDate(2026, 11, 6))
        assertNull(problem(ok))
        assertEquals("Enter an amount (up to 2 decimals, at most ₹100 crore)", problem(ok.copy(amount = "12.345")))
        assertEquals("Payee: at most 100 characters", problem(ok.copy(payee = "x".repeat(101))))
        assertEquals("Note: at most 500 characters", problem(ok.copy(note = "x".repeat(501))))
        assertEquals("A recurring entry stays in its month or the month either side (2026-11)",
            problem(ok.copy(date = LocalDate(2027, 1, 15)), rent))
        assertNull(problem(ok.copy(date = LocalDate(2026, 12, 2)), rent))
    }
}
