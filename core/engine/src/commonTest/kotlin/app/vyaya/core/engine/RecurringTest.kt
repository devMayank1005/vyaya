package app.vyaya.core.engine

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plusMonth
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Recurring catch-up: exactly one entry per rule per month, however often it runs (plan.md invariant 4). */
class RecurringTest {

    private val now = Instant.parse("2026-10-04T10:00:00Z")
    private fun rule(day: Int = 5, startsOn: LocalDate = LocalDate(2026, 1, 5), paused: Boolean = false, deleted: Boolean = false) =
        RecurringRule(EntityId.random(), EntryType.EXPENSE, rupees(11_000), day, RENT, null, "Rent", paused, startsOn, T, deleted)

    private fun catchUp(rules: List<RecurringRule>, entries: List<Entry>, today: LocalDate) =
        Recurring.catchUp(rules, entries, today, IST, now)

    @Test
    fun a_new_rule_starts_at_its_next_due_date() { // FLAW-002 (recurring-key-month report)
        assertEquals(LocalDate(2026, 10, 5), Recurring.startsOnFor(LocalDate(2026, 10, 3), 5))
        assertEquals(LocalDate(2026, 10, 5), Recurring.startsOnFor(LocalDate(2026, 10, 5), 5), "due today: this month")
        assertEquals(LocalDate(2026, 11, 5), Recurring.startsOnFor(LocalDate(2026, 10, 6), 5), "passed: next month")
        assertEquals(LocalDate(2026, 2, 28), Recurring.startsOnFor(LocalDate(2026, 2, 10), 31), "short month")
        assertEquals(LocalDate(2027, 1, 5), Recurring.startsOnFor(LocalDate(2026, 12, 20), 5), "over the year end")
    }

    @Test
    fun the_due_day_is_clamped_in_short_months() {
        val r = rule(day = 31)
        assertEquals(LocalDate(2026, 2, 28), Recurring.dueOn(r, YearMonth(2026, 2)))
        assertEquals(LocalDate(2028, 2, 29), Recurring.dueOn(r, YearMonth(2028, 2)))
        assertEquals(LocalDate(2026, 4, 30), Recurring.dueOn(r, YearMonth(2026, 4)))
    }

    @Test
    fun catch_up_adds_every_month_that_has_come_due() {
        val rent = rule()
        val added = catchUp(listOf(rent), emptyList(), today = LocalDate(2026, 10, 4))
        assertEquals((1..9).map { YearMonth(2026, it) }, added.map { it.generatedBy!!.month }, "January to September; October is due tomorrow")
        val first = added.first()
        assertEquals(Generation(rent.id, YearMonth(2026, 1)).entryId, first.id)
        assertEquals(EntrySource.RECURRING, first.source)
        assertEquals(rent.type, first.type); assertEquals(rent.amount, first.amount); assertEquals(rent.categoryId, first.categoryId)
        assertEquals(LocalDate(2026, 1, 5), first.occurredAt.localDate)
        assertEquals(now, first.createdAt)
        assertTrue(catchUp(listOf(rent), added, today = LocalDate(2026, 10, 5)).single().generatedBy!!.month == YearMonth(2026, 10))
    }

    @Test
    fun running_catch_up_again_adds_nothing() = runTest { // invariant 4
        checkAll(200, Arb.long()) { seed ->
            val r = Random(seed)
            val rules = List(r.nextInt(1, 5)) {
                rule(day = r.nextInt(1, 32), startsOn = LocalDate(2024, r.nextInt(1, 13), r.nextInt(1, 29)), paused = r.nextInt(6) == 0)
            }
            var entries = emptyList<Entry>()
            var day = LocalDate(2024, 1, 1)
            repeat(r.nextInt(1, 30)) { // the app opened on random days, sometimes months apart
                day = LocalDate.fromEpochDays(day.toEpochDays() + r.nextInt(0, 60))
                entries = entries + catchUp(rules, entries, day)
            }
            assertEquals(emptyList(), catchUp(rules, entries, day), "seed=$seed")
            val keys = entries.map { it.generatedBy!! }
            assertEquals(keys.size, keys.toSet().size, "seed=$seed: one entry per rule per month")
            for (rl in rules.filter { !it.paused }) {
                val months = keys.filter { it.ruleId == rl.id }.map { it.month }
                val expected = generateSequence(YearMonth(rl.startsOn.year, rl.startsOn.month)) { it.plusMonth() }
                    .takeWhile { Recurring.dueOn(rl, it) <= day }.filter { Recurring.dueOn(rl, it) >= rl.startsOn }.toList()
                assertEquals(expected, months, "seed=$seed: every month due, none early")
            }
        }
    }

    @Test
    fun a_deleted_generated_entry_is_not_brought_back() {
        val rent = rule()
        val all = catchUp(listOf(rent), emptyList(), today = LocalDate(2026, 3, 10))
        val march = all.single { it.generatedBy!!.month == YearMonth(2026, 3) }
        val afterDelete = all - march + march.markDeleted(now)
        assertEquals(emptyList(), catchUp(listOf(rent), afterDelete, today = LocalDate(2026, 3, 20)))
    }

    @Test
    fun a_payment_moved_to_the_next_month_still_counts_for_its_own() { // DATA-001: key decides "done"
        val rent = rule()
        val october = catchUp(listOf(rent), emptyList(), today = LocalDate(2026, 10, 5)).single { it.generatedBy!!.month == YearMonth(2026, 10) }
        val paidLate = october.copy(occurredAt = CapturedAt(Instant.parse("2026-11-03T06:00:00Z"), IST))
        val entries = catchUp(listOf(rent), emptyList(), today = LocalDate(2026, 10, 5)) - october + paidLate
        assertEquals(listOf(YearMonth(2026, 11)), catchUp(listOf(rent), entries, today = LocalDate(2026, 11, 5)).map { it.generatedBy!!.month })
    }

    @Test
    fun moving_a_rules_day_adds_nothing_for_a_month_already_generated() { // DATA-002
        val rent = rule(day = 5)
        val entries = catchUp(listOf(rent), emptyList(), today = LocalDate(2026, 10, 6))
        assertEquals(emptyList(), catchUp(listOf(rent.copy(dayOfMonth = 10)), entries, today = LocalDate(2026, 10, 12)))
    }

    @Test
    fun a_rule_deleted_and_added_again_in_the_same_month_makes_one_entry() { // FLAW-002
        val old = rule()
        val entries = catchUp(listOf(old), emptyList(), today = LocalDate(2026, 10, 20))
        val again = rule(startsOn = Recurring.startsOnFor(LocalDate(2026, 10, 20), 5))
        val rules = listOf(old.copy(deleted = true), again)
        val added = catchUp(rules, entries, today = LocalDate(2026, 10, 25))
        assertEquals(emptyList(), added, "October is already paid by the old rule")
        assertEquals(listOf(YearMonth(2026, 11)), catchUp(rules, entries, today = LocalDate(2026, 11, 5)).map { it.generatedBy!!.month })
    }

    @Test
    fun resuming_a_paused_rule_asks_about_the_months_it_missed() { // FLAW-003 (core-engine report), option b
        val gym = rule()
        val january = catchUp(listOf(gym), emptyList(), today = LocalDate(2026, 1, 10))
        val paused = gym.copy(paused = true)
        assertEquals(emptyList(), catchUp(listOf(paused), january, today = LocalDate(2026, 7, 6)), "nothing while paused")

        val missed = Recurring.missedWhilePaused(paused, january, today = LocalDate(2026, 7, 6))
        assertEquals((2..7).map { YearMonth(2026, it) }, missed, "February to July came due while paused")

        // "No, don't add them": the missed months are marked done, so catch-up skips them.
        val skipped = Recurring.skip(paused, missed, IST, now)
        assertTrue(skipped.all { it.deleted && it.generatedBy!!.ruleId == gym.id })
        val resumed = gym.copy(paused = false)
        assertEquals(emptyList(), catchUp(listOf(resumed), january + skipped, today = LocalDate(2026, 7, 20)))
        assertEquals(listOf(YearMonth(2026, 8)), catchUp(listOf(resumed), january + skipped, today = LocalDate(2026, 8, 5)).map { it.generatedBy!!.month })

        // "Yes, add them": catch-up fills them in as before.
        assertEquals(missed, catchUp(listOf(resumed), january, today = LocalDate(2026, 7, 6)).map { it.generatedBy!!.month })
    }

    @Test
    fun paused_and_deleted_rules_add_nothing() {
        assertEquals(emptyList(), catchUp(listOf(rule(paused = true), rule(deleted = true)), emptyList(), today = LocalDate(2026, 10, 20)))
    }

    @Test
    fun generated_entries_are_valid_and_dated_at_noon_local_time() {
        val added = catchUp(listOf(rule(day = 31)), emptyList(), today = LocalDate(2026, 3, 1))
        assertEquals(listOf(LocalDate(2026, 1, 31), LocalDate(2026, 2, 28)), added.map { it.occurredAt.localDate })
        assertTrue(added.all { it.occurredAt.timeZone == IST && it.occurredAt.instant.toString().endsWith("T06:30:00Z") })
        assertTrue(added.all { it.amount == Money(1_100_000) && !it.deleted })
    }
}
