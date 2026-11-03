package app.vyaya.core.engine

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.Money

/**
 * Lent and borrowed balances per person (plan.md invariant 6): the sum of their unsettled, not deleted ledger
 * entries. Positive: they owe you. Negative: you owe them. Calculated, never stored (CLAUDE.md rule 8.5).
 */
class Balances private constructor(private val byPerson: Map<EntityId, Money>) {

    /** What [person] owes you (negative: what you owe them). Zero for someone with nothing open. */
    fun of(person: EntityId): Money = byPerson[person] ?: Money(0)

    /** Everyone with a non-zero balance. */
    val open: Map<EntityId, Money> get() = byPerson.filterValues { it.paise != 0L }

    /** The total others owe you. */
    val owedToYou: Money get() = byPerson.values.filter { it.paise > 0 }.fold(Money(0)) { a, b -> a + b }

    /** The total you owe others (a positive amount). */
    val youOwe: Money get() = byPerson.values.filter { it.paise < 0 }.fold(Money(0)) { a, b -> a - b }

    companion object {
        fun of(ledger: List<LedgerEntry>): Balances {
            val byPerson = LinkedHashMap<EntityId, Money>()
            for (e in ledger) {
                if (e.settled || e.deleted) continue
                val current = byPerson[e.personId] ?: Money(0)
                byPerson[e.personId] = if (e.direction == LedgerDirection.LENT) current + e.amount else current - e.amount
            }
            return Balances(byPerson)
        }
    }
}
