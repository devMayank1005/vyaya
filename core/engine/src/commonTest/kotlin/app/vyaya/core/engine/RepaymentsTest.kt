package app.vyaya.core.engine

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.Money
import app.vyaya.core.model.Person
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Split-bills report #2: which friend money coming in could be paying you back. */
class RepaymentsTest {

    private val t = Instant.parse("2026-11-18T14:00:00Z")
    private fun person(name: String) = Person(EntityId.random(), name, t, false)
    private fun lent(p: Person, rupees: Long, direction: LedgerDirection = LedgerDirection.LENT) =
        LedgerEntry(EntityId.random(), p.id, direction, Money(rupees * 100), LocalDate(2026, 11, 18), "", false, t, false)

    @Test
    fun the_one_friend_whose_name_matches_and_who_owes_at_least_that() {
        val rahul = person("Rahul"); val priya = person("Priya Nair")
        val people = listOf(rahul, priya)
        val balances = Balances.of(listOf(lent(rahul, 400), lent(priya, 300)))
        assertEquals(rahul, Repayments.from("Rahul Sharma", Money(400_00), people, balances), "a longer UPI name")
        assertEquals(rahul, Repayments.from("  rahul ", Money(100_00), people, balances), "case and spaces; part of it")
        assertEquals(priya, Repayments.from("PRIYA", Money(300_00), people, balances), "a shorter UPI name")
        assertNull(Repayments.from("Rahul", Money(400_01), people, balances), "more than he owes")
        assertNull(Repayments.from("Rahulkumar", Money(100_00), people, balances), "whole words only")
        assertNull(Repayments.from("Ankit", Money(100_00), people, balances), "nobody you know")
        assertNull(Repayments.from(null, Money(100_00), people, balances), "no payee")
        assertNull(Repayments.from("Priya Menon", Money(100_00), people, balances), "a different Priya")
    }

    @Test
    fun nobody_when_they_owe_nothing_you_owe_them_or_two_people_match() {
        val rahul = person("Rahul"); val rahulK = person("Rahul K"); val asha = person("Asha")
        val balances = Balances.of(listOf(lent(rahul, 400), lent(rahulK, 400), lent(asha, 200, LedgerDirection.BORROWED)))
        assertNull(Repayments.from("Rahul", Money(100_00), listOf(rahul, rahulK, asha), balances), "two Rahuls: can't tell")
        assertEquals(rahulK, Repayments.from("Rahul K", Money(100_00), listOf(rahul, rahulK, asha), balances), "the full name tells")
        assertNull(Repayments.from("Asha", Money(100_00), listOf(rahul, rahulK, asha), balances), "you owe Asha")
        assertNull(Repayments.from("Rahul", Money(100_00), listOf(rahul.copy(deleted = true)), balances), "deleted")
    }
}
