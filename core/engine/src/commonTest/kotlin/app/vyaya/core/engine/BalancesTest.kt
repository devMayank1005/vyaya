package app.vyaya.core.engine

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Lent and borrowed, per person (plan.md invariant 6). Positive: they owe you. Negative: you owe them. */
class BalancesTest {

    private val rahul = EntityId.random()
    private val priya = EntityId.random()
    private fun lent(person: EntityId, r: Long, settled: Boolean = false, deleted: Boolean = false) =
        LedgerEntry(EntityId.random(), person, LedgerDirection.LENT, rupees(r), LocalDate(2026, 10, 1), "", settled, T, deleted)
    private fun borrowed(person: EntityId, r: Long) =
        LedgerEntry(EntityId.random(), person, LedgerDirection.BORROWED, rupees(r), LocalDate(2026, 10, 2), "", false, T, false)

    @Test
    fun a_persons_balance_is_what_they_owe_minus_what_you_owe_them() {
        val b = Balances.of(listOf(lent(rahul, 300), borrowed(rahul, 100), borrowed(priya, 50)))
        assertEquals(rupees(200), b.of(rahul))
        assertEquals(rupees(-50), b.of(priya))
        assertEquals(rupees(200), b.owedToYou)
        assertEquals(rupees(50), b.youOwe)
    }

    @Test
    fun settled_and_deleted_entries_do_not_count() {
        val b = Balances.of(listOf(lent(rahul, 300, settled = true), lent(rahul, 70, deleted = true), lent(rahul, 20)))
        assertEquals(rupees(20), b.of(rahul))
        assertEquals(rupees(0), b.of(priya), "nobody else owes anything")
    }

    @Test
    fun open_balances_leave_out_people_who_are_square() {
        val b = Balances.of(listOf(lent(rahul, 100), borrowed(rahul, 100), lent(priya, 40)))
        assertEquals(mapOf(priya to rupees(40)), b.open)
    }

    @Test
    fun a_balance_is_always_the_sum_of_the_unsettled_entries() = runTest { // invariant 6
        checkAll(200, Arb.long()) { seed ->
            val r = Random(seed)
            val people = List(4) { EntityId.random() }
            val ledger = List(r.nextInt(0, 50)) {
                LedgerEntry(EntityId.random(), people.random(r), LedgerDirection.entries.random(r), rupees(r.nextLong(1, 10_000)),
                    LocalDate(2026, 10, 1), "", r.nextBoolean(), T, r.nextInt(8) == 0)
            }
            val b = Balances.of(ledger)
            for (p in people) {
                val expected = ledger.filter { it.personId == p && !it.settled && !it.deleted }
                    .fold(rupees(0)) { a, e -> if (e.direction == LedgerDirection.LENT) a + e.amount else a - e.amount }
                assertEquals(expected, b.of(p), "seed=$seed")
            }
            assertEquals(people.fold(rupees(0)) { a, p -> a + b.of(p) }, b.owedToYou - b.youOwe, "seed=$seed")
        }
    }
}
