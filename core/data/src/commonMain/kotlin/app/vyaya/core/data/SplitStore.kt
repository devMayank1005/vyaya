package app.vyaya.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.Money
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
import kotlin.time.Instant

/**
 * Split bills (Phase 3; option A, the user's choice). A payment stays one entry of its full amount (it matches the
 * bank's message), split into:
 * - the user's own parts ([mine]: their categories), which are what counts as spending;
 * - one "Lent & Borrowed" part for the friends' shares, which aren't spending: each friend gets a LENT entry in the
 *   People ledger, so the balance shows what they owe back.
 * The entry and the ledger entries are written in one transaction: all or nothing.
 */
class SplitStore(private val db: VyayaDatabase) {

    suspend fun save(entry: Entry, mine: List<Split>, friends: List<Pair<EntityId, Money>>, now: Instant): CatalogResult<Entry> {
        if (entry.type != EntryType.EXPENSE) return Refused("Only spending can be split")
        if (friends.any { it.second.paise <= 0 } || mine.any { it.amount.paise <= 0 }) return Refused("Every share must be more than ₹0")
        val theirs = friends.sumOf { it.second.paise }
        val yours = entry.amount.paise - theirs
        if (yours <= 0) return Refused("Friends' shares (${Money(theirs).format()}) leave nothing for you; record it in People instead")
        val parts = mine.ifEmpty {
            val category = entry.categoryId ?: return Refused("Choose a category for your share")
            listOf(Split(category, Money(yours)))
        }
        val partsTotal = parts.sumOf { it.amount.paise }
        if (partsTotal != yours) {
            val friendsNote = if (theirs > 0) " (${entry.amount.format()} less ${Money(theirs).format()} for friends)" else ""
            return Refused("Your parts add up to ${Money(partsTotal).format()}; they should be ${Money(yours).format()}$friendsNote")
        }
        val splits = parts + if (theirs > 0) listOf(Split(SystemCategories.LENT.id, Money(theirs))) else emptyList()
        val split = runCatching { entry.copy(splits = splits, categoryId = parts.first().categoryId, updatedAt = now) }
            .getOrElse { return Refused("This split can't be saved") }
        val what = entry.payee ?: entry.note.ifEmpty { "A shared payment" }
        val date = entry.occurredAt.localDate
        val lent = friends.map { (person, share) ->
            LedgerEntry(EntityId.random(), person, LedgerDirection.LENT, share, date, "$what: their share of ${entry.amount.format()}".take(500),
                false, now, false)
        }
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                EntryStore(db).save(listOf(split))
                if (lent.isNotEmpty()) db.records().upsert(SnapshotStore.recordRows(Snapshot.empty().copy(ledger = lent)))
            }
        }
        return Saved(split)
    }
}
