package app.vyaya.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.dedup.Capture
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.MerchantRule
import kotlin.time.Instant

/**
 * Merchant memory (plan Phase 2): "always put Swiggy in Food". One rule per payee as capture compares payees
 * ([Capture.payeeKey]); remembering again changes that rule. Forgetting is a soft delete (rule 8.2).
 */
class MerchantStore(private val db: VyayaDatabase, private val limits: BackupLimits = BackupLimits()) {

    private val snapshots = SnapshotStore(db)

    /** Rules in use, by payee. */
    suspend fun rules(): List<MerchantRule> = snapshots.loadRecords().merchantRules.filter { !it.deleted }.sortedBy { it.payee.lowercase() }

    suspend fun remember(payee: String, categoryId: EntityId, now: Instant): CatalogResult<MerchantRule> {
        val key = Capture.payeeKey(payee)
        if (key.isEmpty()) return Refused("This payee has no letters or digits to remember it by")
        val all = snapshots.loadRecords().merchantRules
        // A forgotten rule for the payee comes back rather than a second one being made (like categories, catalog #3).
        val same = all.filter { Capture.payeeKey(it.payee) == key }.maxByOrNull { if (it.deleted) 0 else 1 }
        if (same == null && all.size >= limits.maxRecordsPerType) {
            return Refused("Vyaya can remember up to ${limits.maxRecordsPerType} payees, forgotten ones included")
        }
        // A key means the payee isn't blank; at most 100 characters, as an entry's payee.
        val rule = MerchantRule(same?.id ?: EntityId.random(), payee.trim().take(100), categoryId, now, false)
        save(rule)
        return Saved(rule)
    }

    /** What remembering [payee] would reach: its payments of [type] so far, and how many of them have no category. */
    data class Matches(val total: Int, val uncategorised: Int)

    /** Merchant memory report #4: shown before the user decides, so a too-broad name is noticed. */
    suspend fun matches(payee: String, type: EntryType): Matches {
        val key = Capture.payeeKey(payee)
        if (key.isEmpty()) return Matches(0, 0)
        return db.entries().payeeCounts(key, type.name).let { Matches(it.total, it.uncategorised) }
    }

    /**
     * Merchant memory report #3, when the user asks: [payee]'s payments of [type] with no category go in [categoryId].
     * A category already on an entry (the user's choice) is never changed. One write transaction; returns how many.
     */
    suspend fun applyToEarlier(payee: String, categoryId: EntityId, type: EntryType, now: Instant): Int {
        val key = Capture.payeeKey(payee)
        if (key.isEmpty()) return 0
        val entries = EntryStore(db)
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val open = db.entries().uncategorisedFor(key, type.name).map(entries::entryOf)
                entries.save(open.map { it.copy(categoryId = categoryId, updatedAt = now) })
                open.size
            }
        }
    }

    /** The key payees are matched by (case and a UPI handle don't matter): for the screens. */
    fun keyOf(payee: String): String = Capture.payeeKey(payee)

    suspend fun forget(rule: MerchantRule, now: Instant) =
        save(MerchantRule(rule.id, rule.payee, rule.categoryId, now, true))

    private suspend fun save(rule: MerchantRule) = snapshots.saveRecords(Snapshot.empty().copy(merchantRules = listOf(rule)))
}
