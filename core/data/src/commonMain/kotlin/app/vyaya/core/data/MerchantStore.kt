package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.dedup.Capture
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.MerchantRule
import kotlin.time.Instant

/**
 * Merchant memory (plan Phase 2): "always put Swiggy in Food". One rule per payee as capture compares payees
 * ([Capture.payeeKey]); remembering again changes that rule. Forgetting is a soft delete (rule 8.2).
 */
class MerchantStore(private val db: VyayaDatabase, private val limits: BackupLimits = BackupLimits()) {

    private val snapshots = SnapshotStore(db)

    /** Rules in use, by payee. */
    suspend fun rules(): List<MerchantRule> = snapshots.loadRecords().merchantRules.filter { !it.deleted }.sortedBy { it.payee }

    suspend fun remember(payee: String, categoryId: EntityId, now: Instant): CatalogResult<MerchantRule> {
        val key = Capture.payeeKey(payee)
        if (key.isEmpty()) return Refused("This payee has no letters or digits to remember it by")
        val all = snapshots.loadRecords().merchantRules
        // A forgotten rule for the payee comes back rather than a second one being made (like categories, catalog #3).
        val same = all.filter { Capture.payeeKey(it.payee) == key }.maxByOrNull { if (it.deleted) 0 else 1 }
        if (same == null && all.size >= limits.maxRecordsPerType) {
            return Refused("Vyaya can remember up to ${limits.maxRecordsPerType} payees, forgotten ones included")
        }
        val rule = runCatching {
            MerchantRule(same?.id ?: EntityId.random(), payee.take(100), categoryId, now, false)
        }.getOrElse { return Refused("This payee can't be remembered") }
        save(rule)
        return Saved(rule)
    }

    suspend fun forget(rule: MerchantRule, now: Instant) =
        save(MerchantRule(rule.id, rule.payee, rule.categoryId, now, true))

    private suspend fun save(rule: MerchantRule) = snapshots.saveRecords(Snapshot.empty().copy(merchantRules = listOf(rule)))
}
