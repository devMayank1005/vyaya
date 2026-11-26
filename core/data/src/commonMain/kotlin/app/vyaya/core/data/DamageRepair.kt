package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.SystemCategories

/**
 * core-data DATA-006: what a backup of a damaged database keeps. Records that couldn't be read are already left out;
 * this detaches the references to them, so the rest passes the writer's checks and restores:
 * - an entry keeps its amount, date and payee, and loses a category, account or link it can't have;
 * - a split whose part names a lost category becomes a plain payment;
 * - an entry made by a lost rule becomes an ordinary entry (with an id of its own: its derived id needs the rule);
 * - a ledger entry of a lost person, a budget or merchant rule of a lost category, can't stand alone and is left out.
 * A record that still can't be built is left out too. Used only when damage was found.
 */
internal object DamageRepair {

    fun detach(s: Snapshot): Snapshot {
        val categories = s.categories.mapTo(HashSet()) { it.id } + SystemCategories.all.map { it.id }
        val accounts = s.accounts.mapTo(HashSet()) { it.id }
        val people = s.people.mapTo(HashSet()) { it.id }
        val rules = s.recurring.mapTo(HashSet()) { it.id }
        val entryIds = s.entries.mapTo(HashSet()) { it.id }
        fun EntityId?.kept(known: Set<EntityId>) = this?.takeIf { it in known }

        val entries = s.entries.mapNotNull { e ->
            runCatching {
                val splitsOk = e.splits.all { it.categoryId in categories }
                val ruleOk = e.generatedBy == null || e.generatedBy!!.ruleId in rules
                e.copy(
                    id = if (ruleOk) e.id else EntityId.random(),
                    generatedBy = if (ruleOk) e.generatedBy else null,
                    categoryId = e.categoryId.kept(categories),
                    accountId = e.accountId.kept(accounts),
                    toAccountId = e.toAccountId.kept(accounts),
                    splits = if (splitsOk) e.splits else emptyList(),
                    linkedEntryId = e.linkedEntryId.kept(entryIds),
                )
            }.getOrNull()
        }
        return s.copy(
            entries = entries,
            recurring = s.recurring.mapNotNull { r ->
                runCatching { r.copy(categoryId = r.categoryId.kept(categories), accountId = r.accountId.kept(accounts)) }.getOrNull()
            },
            ledger = s.ledger.filter { it.personId in people }.map { it.copy(paymentId = it.paymentId.kept(entryIds)) },
            budgets = s.budgets.filter { it.categoryId in categories },
            merchantRules = s.merchantRules.filter { it.categoryId in categories },
        )
    }
}

/** core-data DATA-006: the rows found damaged, for the screens (which don't see Room's types). */
class DamageReport(private val db: VyayaDatabase) {
    val rows: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = db.damaged
}
