package app.vyaya.core.engine

import app.vyaya.core.model.Money
import app.vyaya.core.model.Person

/** Split-bills report #2: money coming in that could be a friend paying you back (asked, never assumed). */
object Repayments {

    /**
     * The friend [payee] could be: their name and the payee's agree word for word as far as the shorter goes ("Rahul" and
     * "Rahul Sharma"), and they owe you at least [amount]. When two could be (two Rahuls), the one agreeing on more words;
     * a tie is nobody, as is a payee nobody matches.
     */
    fun from(payee: String?, amount: Money, people: List<Person>, balances: Balances): Person? {
        val words = words(payee ?: return null).ifEmpty { return null }
        val matches = people
            .filter { !it.deleted && balances.of(it.id).paise >= amount.paise && amount.paise > 0 }
            .mapNotNull { p ->
                val name = words(p.name)
                val shorter = minOf(name.size, words.size)
                if (shorter > 0 && name.take(shorter) == words.take(shorter)) p to shorter else null
            }
        val best = matches.maxOfOrNull { it.second } ?: return null
        return matches.filter { it.second == best }.singleOrNull()?.first
    }

    private fun words(s: String): List<String> = s.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
