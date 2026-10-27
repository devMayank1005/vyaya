package app.vyaya.core.backup

import kotlinx.serialization.Serializable

/*
 * Backup file format, schema version 1 (plan.md Part D). FROZEN: any change to these classes needs a new
 * schema version, a migration and a golden-file test (CLAUDE.md rule 8.7). Field order here is the order in
 * the file. Kept separate from the domain records so the app can evolve without silently changing the format.
 */

@Serializable
internal data class BackupFileV1(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val createdOn: String,
    val exportedAt: String,
    val currency: String,
    val settings: SettingsV1,
    val accounts: List<AccountV1>,
    val categories: List<CategoryV1>,
    val entries: List<EntryV1>,
    val recurring: List<RecurringV1>,
    val people: List<PersonV1>,
    val ledger: List<LedgerV1>,
    val budgets: List<BudgetV1>,
    val merchantRules: List<MerchantRuleV1>,
)

@Serializable
internal data class SettingsV1(
    val monthStartDay: Int,
    val startDayChanges: List<StartDayChangeV1>,
    val timezone: String,
    val alertsEnabled: Boolean,
)

@Serializable
internal data class StartDayChangeV1(val effectiveFrom: String, val transitionEnd: String, val startDay: Int)

@Serializable
internal data class AccountV1(
    val id: String, val name: String, val kind: String, val mask: String?, val updatedAt: String, val deleted: Boolean,
)

@Serializable
internal data class CategoryV1(val id: String, val name: String, val kind: String, val updatedAt: String, val deleted: Boolean)

@Serializable
internal data class EntryV1(
    val id: String,
    val type: String,
    val amountPaise: Long,
    val occurredAt: String,
    val timezone: String,
    val accountId: String?,
    val toAccountId: String?,
    val categoryId: String?,
    val payee: String?,
    val note: String,
    val tags: List<String>,
    val source: String,
    val upiRef: String?,
    val splits: List<SplitV1>,
    val linkedEntryId: String?,
    val createdAt: String,
    val updatedAt: String,
    val deleted: Boolean,
)

@Serializable
internal data class SplitV1(val categoryId: String, val amountPaise: Long)

@Serializable
internal data class RecurringV1(
    val id: String,
    val type: String,
    val amountPaise: Long,
    val dayOfMonth: Int,
    val categoryId: String?,
    val accountId: String?,
    val note: String,
    val paused: Boolean,
    val startsOn: String,
    val lastGeneratedOn: String?,
    val updatedAt: String,
    val deleted: Boolean,
)

@Serializable
internal data class PersonV1(val id: String, val name: String, val updatedAt: String, val deleted: Boolean)

@Serializable
internal data class LedgerV1(
    val id: String,
    val personId: String,
    val direction: String,
    val amountPaise: Long,
    val date: String,
    val note: String,
    val settled: Boolean,
    val updatedAt: String,
    val deleted: Boolean,
)

@Serializable
internal data class BudgetV1(val id: String, val categoryId: String, val limitPaise: Long, val updatedAt: String, val deleted: Boolean)

@Serializable
internal data class MerchantRuleV1(val id: String, val payee: String, val categoryId: String, val updatedAt: String, val deleted: Boolean)
