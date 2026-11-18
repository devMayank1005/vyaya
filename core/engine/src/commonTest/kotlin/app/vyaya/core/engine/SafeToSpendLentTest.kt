package app.vyaya.core.engine

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Split-bills report #4: money lent this month and not yet repaid isn't yours to spend. */
class SafeToSpendLentTest {

    private val month = MonthSchedule.of(1).monthContaining(LocalDate(2026, 11, 10))
    private val rahul = EntityId.random()
    private val priya = EntityId.random()

    private fun lent(person: EntityId, rupees: Long, date: LocalDate, direction: LedgerDirection = LedgerDirection.LENT, settled: Boolean = false) =
        LedgerEntry(EntityId.random(), person, direction, rupees(rupees), date, "", settled, T, false)

    @Test
    fun only_this_months_lending_still_owed_counts() {
        val ledger = listOf(
            lent(rahul, 400, LocalDate(2026, 11, 5)),
            lent(rahul, 150, LocalDate(2026, 11, 8), LedgerDirection.BORROWED), // he paid ₹150 back
            lent(priya, 400, LocalDate(2026, 11, 5)),
            lent(priya, 1_000, LocalDate(2026, 10, 20)), // last month: already out of last month's money
            lent(EntityId.random(), 300, LocalDate(2026, 11, 2), settled = true),
            lent(EntityId.random(), 300, LocalDate(2026, 11, 2), LedgerDirection.BORROWED), // you owe them: not lent
        )
        // Rahul: ₹250 still owed of this month's ₹400; Priya: all of this month's ₹400 (her older ₹1,000 is last month's).
        assertEquals(rupees(650), SafeToSpend.openLent(ledger, month))
        assertEquals(rupees(0), SafeToSpend.openLent(listOf(lent(rahul, 400, LocalDate(2026, 12, 1))), month), "next month")
    }

    @Test
    fun a_shared_dinner_lowers_safe_to_spend_by_what_friends_owe_and_today_stays_steady() {
        val today = LocalDate(2026, 11, 21) // 10 days left
        val dinner = Entry(EntityId.random(), EntryType.EXPENSE, rupees(1_200), CapturedAt(Instant.parse("2026-11-21T14:00:00Z"), TimeZone.of("Asia/Kolkata")),
            null, null, EntityId.random(), "Barbeque Nation", "", emptyList(), EntrySource.MANUAL, null,
            listOf(Split(EntityId.random(), rupees(400)), Split(SystemCategories.LENT.id, rupees(800))), null, T, T, false)
        val summary = MonthSummary(month, rupees(20_000), rupees(10_400), emptyMap()) // ₹400 of it is today's dinner share
        val s = SafeToSpend.of(summary, emptyList(), emptySet(), today, payments = listOf(dinner), lent = rupees(800))!!
        assertEquals(rupees(800), s.lent)
        assertEquals(rupees(1_000), s.perDay, "(₹9,600 left − ₹800 lent + today's ₹1,200 added back) / 10 days")
        assertEquals(rupees(-200), s.leftToday, "today's dinner, all of it, comes out of today")
        val before = SafeToSpend.of(MonthSummary(month, rupees(20_000), rupees(10_000), emptyMap()), emptyList(), emptySet(), today)!!
        assertEquals(before.perDay, s.perDay, "paying the dinner doesn't change today's amount, only what's left of it")
        val tomorrow = SafeToSpend.of(summary, emptyList(), emptySet(), today.plusDays(1), payments = listOf(dinner), lent = rupees(800))!!
        assertEquals(rupees(977), tomorrow.perDay, "₹8,800 over 9 days, rounded down to the rupee")
    }

    @Test
    fun lent_alone_can_leave_nothing_safe_and_says_by_how_much() {
        val s = SafeToSpend.of(MonthSummary(month, rupees(1_000), rupees(500), emptyMap()), emptyList(), emptySet(), LocalDate(2026, 11, 21), lent = rupees(800))!!
        assertEquals(rupees(0) to rupees(300), s.perDay to s.shortBy)
        assertEquals(rupees(0), s.billsDue)
    }

    private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)
}
