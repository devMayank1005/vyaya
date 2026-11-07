package app.vyaya.core.data

import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Recurring rules from the database: add, change, pause, and resume with or without the months missed (FLAW-003). */
class RulesStoreTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val sep1 = Instant.parse("2026-09-01T04:30:00Z")

    @Test
    fun a_new_rule_starts_on_its_next_due_day_and_catch_up_adds_it() = runTest {
        val db = inMemoryDatabase()
        val rules = RulesStore(db)
        val rent = rules.add(EntryType.EXPENSE, Money(15_000_00), dayOfMonth = 5, categoryId = null, note = "Rent", now = sep1, zone = ist)
        assertEquals(LocalDate(2026, 9, 5), rent.startsOn)
        assertEquals(listOf(rent), rules.active())
        AppOpen(db).run(Instant.parse("2026-11-06T04:30:00Z"))
        assertEquals(3, EntryStore(db).all().count { it.generatedBy?.ruleId == rent.id }, "September, October, November")
        db.close()
    }

    @Test
    fun resuming_asks_about_the_missed_months_and_no_skips_them() = runTest {
        val db = inMemoryDatabase()
        val rules = RulesStore(db)
        val gym = rules.add(EntryType.EXPENSE, Money(1_500_00), 1, null, "Gym", sep1, ist)
        AppOpen(db).run(sep1) // September's
        rules.pause(gym, Instant.parse("2026-09-10T04:30:00Z"))
        val nov6 = Instant.parse("2026-11-06T04:30:00Z")
        AppOpen(db).run(nov6) // paused: nothing added
        val paused = rules.all().single()
        assertEquals(listOf(YearMonth(2026, 10), YearMonth(2026, 11)), rules.missedWhilePaused(paused, nov6, ist))
        rules.resume(paused, addMissed = false, now = nov6, zone = ist)
        AppOpen(db).run(nov6)
        val live = EntryStore(db).all().filter { it.generatedBy?.ruleId == gym.id && !it.deleted }
        assertEquals(listOf(YearMonth(2026, 9)), live.map { it.generatedBy!!.month }, "the missed months stay empty")
        assertTrue(!rules.all().single().paused)
        db.close()
    }

    @Test
    fun resuming_with_the_missed_months_adds_them() = runTest {
        val db = inMemoryDatabase()
        val rules = RulesStore(db)
        val gym = rules.add(EntryType.EXPENSE, Money(1_500_00), 1, null, "Gym", sep1, ist)
        AppOpen(db).run(sep1)
        rules.pause(gym, Instant.parse("2026-09-10T04:30:00Z"))
        val nov6 = Instant.parse("2026-11-06T04:30:00Z")
        rules.resume(rules.all().single(), addMissed = true, now = nov6, zone = ist)
        AppOpen(db).run(nov6)
        assertEquals(3, EntryStore(db).all().count { it.generatedBy?.ruleId == gym.id && !it.deleted })
        db.close()
    }

    @Test
    fun a_changed_rule_keeps_its_id_and_a_deleted_one_makes_no_more_entries() = runTest {
        val db = inMemoryDatabase()
        val rules = RulesStore(db)
        val rent = rules.add(EntryType.EXPENSE, Money(15_000_00), 5, null, "Rent", sep1, ist)
        val later = Instant.parse("2026-09-20T04:30:00Z")
        rules.save(rent.copy(amount = Money(16_000_00), updatedAt = later))
        assertEquals(Money(16_000_00), rules.active().single().amount)
        rules.delete(rules.active().single(), later)
        assertEquals(emptyList(), rules.active())
        AppOpen(db).run(Instant.parse("2026-11-06T04:30:00Z"))
        assertEquals(0, EntryStore(db).all().count { it.generatedBy?.ruleId == rent.id && it.occurredAt.instant > later })
        db.close()
    }
}
