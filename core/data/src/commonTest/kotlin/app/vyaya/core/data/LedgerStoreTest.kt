package app.vyaya.core.data

import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Lent and borrowed, from the database (plan.md invariant 6: balances calculated, never stored). */
class LedgerStoreTest {

    private val t = Instant.parse("2026-11-09T10:00:00Z")
    private val day = LocalDate(2026, 11, 9)

    @Test
    fun what_people_owe_you_and_what_you_owe_them() = runTest {
        val db = inMemoryDatabase()
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t)
        val priya = ledger.addPerson("Priya", t)
        ledger.add(rahul.id, LedgerDirection.LENT, Money(500_00), day, "Dinner", t)
        ledger.add(priya.id, LedgerDirection.BORROWED, Money(1_200_00), day, "Cab", t)
        val b = ledger.balances()
        assertEquals(Money(500_00), b.of(rahul.id))
        assertEquals(Money(-1_200_00), b.of(priya.id))
        assertEquals(Money(500_00), b.owedToYou)
        assertEquals(Money(1_200_00), b.youOwe)
        assertEquals(listOf("Priya", "Rahul"), ledger.people().map { it.name })
        db.close()
    }

    @Test
    fun a_partial_payback_lowers_the_balance_and_settle_all_clears_it() = runTest {
        val db = inMemoryDatabase()
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t)
        ledger.add(rahul.id, LedgerDirection.LENT, Money(500_00), day, "Dinner", t)
        ledger.paidBack(rahul.id, Money(300_00), day, t) // Rahul gave ₹300 back
        assertEquals(Money(200_00), ledger.balances().of(rahul.id))
        ledger.settleAll(rahul.id, t)
        assertEquals(Money(0), ledger.balances().of(rahul.id))
        assertTrue(ledger.entries(rahul.id).all { it.settled }, "kept, marked settled")
        db.close()
    }

    @Test
    fun a_deleted_entry_or_person_no_longer_counts() = runTest {
        val db = inMemoryDatabase()
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t)
        val dinner = ledger.add(rahul.id, LedgerDirection.LENT, Money(500_00), day, "Dinner", t)
        ledger.delete(dinner, t)
        assertEquals(Money(0), ledger.balances().of(rahul.id))
        assertEquals(emptyList(), ledger.entries(rahul.id))
        ledger.deletePerson(rahul, t)
        assertEquals(emptyList(), ledger.people())
        db.close()
    }

    @Test
    fun mvp2_data_003_paying_back_more_than_is_owed_is_refused() = runTest {
        val db = inMemoryDatabase()
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t)
        ledger.add(rahul.id, LedgerDirection.LENT, Money(200_00), day, "Tea", t)
        assertEquals(null, ledger.paidBack(rahul.id, Money(300_00), day, t), "more than the ₹200 owed")
        assertEquals(Money(200_00), ledger.balances().of(rahul.id), "nothing changed")
        val nobody = ledger.addPerson("Priya", t)
        assertEquals(null, ledger.paidBack(nobody.id, Money(1_00), day, t), "nothing is owed")
        db.close()
    }

    @Test
    fun mvp2_flaw_004_005_an_entry_can_be_changed_and_a_person_renamed() = runTest {
        val db = inMemoryDatabase()
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t)
        val tea = ledger.add(rahul.id, LedgerDirection.LENT, Money(200_00), day, "Tea", t)
        ledger.update(tea.copy(amount = Money(250_00), note = "Tea and snacks"), t)
        assertEquals(Money(250_00), ledger.balances().of(rahul.id))
        assertEquals("Tea and snacks", ledger.entries(rahul.id).single().note)
        ledger.renamePerson(rahul, "Rahul S", t)
        assertEquals(listOf("Rahul S"), ledger.people().map { it.name })
        db.close()
    }
}
