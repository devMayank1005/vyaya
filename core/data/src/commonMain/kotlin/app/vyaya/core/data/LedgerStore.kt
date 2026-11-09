package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.engine.Balances
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.Money
import app.vyaya.core.model.Person
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Lent and borrowed on the database: people and their ledger entries (records; soft delete, rule 8.2). Balances come
 * from [Balances] each time (invariant 6: calculated, never stored). Never part of spending.
 */
class LedgerStore(private val db: VyayaDatabase) {

    private val snapshots = SnapshotStore(db)

    /** People not deleted, by name. */
    suspend fun people(): List<Person> = snapshots.loadRecords().people.filter { !it.deleted }.sortedBy { it.name.lowercase() }

    suspend fun addPerson(name: String, now: Instant): Person =
        Person(EntityId.random(), name.trim(), now, false).also { save(people = listOf(it)) }

    suspend fun renamePerson(p: Person, name: String, now: Instant) = save(people = listOf(p.copy(name = name.trim(), updatedAt = now)))

    suspend fun deletePerson(p: Person, now: Instant) = save(people = listOf(p.copy(deleted = true, updatedAt = now)))

    /** [person]'s entries not deleted, newest first. */
    suspend fun entries(person: EntityId): List<LedgerEntry> =
        snapshots.loadRecords().ledger.filter { it.personId == person && !it.deleted }.sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.updatedAt })

    /** LENT: they owe you. BORROWED: you owe them. */
    suspend fun add(person: EntityId, direction: LedgerDirection, amount: Money, date: LocalDate, note: String, now: Instant): LedgerEntry =
        LedgerEntry(EntityId.random(), person, direction, amount, date, note.trim(), false, now, false).also { save(ledger = listOf(it)) }

    /**
     * Part of what [person] owes coming back (or part of what you owe going back, when you owe them): an entry the
     * other way, so the balance moves by [amount] and the history shows it.
     */
    suspend fun paidBack(person: EntityId, amount: Money, date: LocalDate, now: Instant): LedgerEntry {
        val theyOwe = balances().of(person).paise >= 0
        return add(person, if (theyOwe) LedgerDirection.BORROWED else LedgerDirection.LENT, amount, date, "Paid back", now)
    }

    /** Everything open with [person] is settled (kept in the history, marked settled). */
    suspend fun settleAll(person: EntityId, now: Instant) =
        save(ledger = entries(person).filter { !it.settled }.map { it.copy(settled = true, updatedAt = now) })

    suspend fun delete(entry: LedgerEntry, now: Instant) = save(ledger = listOf(entry.copy(deleted = true, updatedAt = now)))

    /** Who owes whom, from every ledger entry (few records). */
    suspend fun balances(): Balances = Balances.of(snapshots.loadRecords().ledger)

    private suspend fun save(people: List<Person> = emptyList(), ledger: List<LedgerEntry> = emptyList()) =
        snapshots.saveRecords(Snapshot.empty().copy(people = people, ledger = ledger))
}
