package app.vyaya.core.backup

import kotlinx.serialization.Serializable

/*
 * Backup file format, schema version 3 (plan.md Part D). FROZEN like v1 and v2: changes need a new schema version.
 *
 * The same lines as v2, except:
 * - an entry line ends with "generatedBy": the rule and calendar month ("2026-10") of an entry a recurring
 *   rule made, or null (CLAUDE.md rule 8.4). Entries from v1 and v2 files are read with no key.
 * - a recurring line has no "lastGeneratedOn": it is worked out from the keys (rule 8.5). v1 and v2 values
 *   are dropped when read.
 */

@Serializable
internal data class GenerationV3(val ruleId: String, val month: String)

@Serializable
internal data class RecurringV3(
    val id: String,
    val type: String,
    val amountPaise: Long,
    val dayOfMonth: Int,
    val categoryId: String?,
    val accountId: String?,
    val note: String,
    val paused: Boolean,
    val startsOn: String,
    val updatedAt: String,
    val deleted: Boolean,
)

/** Migration v1/v2 → v3: "lastGeneratedOn" is dropped. */
internal fun RecurringV1.toV3() = RecurringV3(id, type, amountPaise, dayOfMonth, categoryId, accountId, note, paused, startsOn, updatedAt, deleted)

/** Only for writing old-format test fixtures. */
internal fun RecurringV3.toV1() =
    RecurringV1(id, type, amountPaise, dayOfMonth, categoryId, accountId, note, paused, startsOn, lastGeneratedOn = null, updatedAt, deleted)

@Serializable
internal data class EntryV3(
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
    val generatedBy: GenerationV3?,
)

/** Migration v1/v2 → v3: older entries have no generation key. */
internal fun EntryV1.toV3() = EntryV3(
    id, type, amountPaise, occurredAt, timezone, accountId, toAccountId, categoryId, payee, note, tags, source, upiRef, splits,
    linkedEntryId, createdAt, updatedAt, deleted, generatedBy = null,
)

/** Only for writing old-format test fixtures; the app always writes v3. Drops the key. */
internal fun EntryV3.toV1() = EntryV1(
    id, type, amountPaise, occurredAt, timezone, accountId, toAccountId, categoryId, payee, note, tags, source, upiRef, splits,
    linkedEntryId, createdAt, updatedAt, deleted,
)

/** Everything a backup holds, as file records of the current schema. Not serialized as a whole. */
internal data class BackupContent(
    val appVersion: String,
    val createdOn: String,
    val exportedAt: String,
    val currency: String,
    val settings: SettingsV1,
    val accounts: List<AccountV1>,
    val categories: List<CategoryV1>,
    val entries: List<EntryV3>,
    val recurring: List<RecurringV3>,
    val people: List<PersonV1>,
    val ledger: List<LedgerV1>,
    val budgets: List<BudgetV1>,
    val merchantRules: List<MerchantRuleV1>,
) {
    val recordCount: Int
        get() = accounts.size + categories.size + entries.size + recurring.size + people.size + ledger.size + budgets.size + merchantRules.size

    fun toV1File() = BackupFileV1(
        BackupCodec.FORMAT, BackupCodec.V1, appVersion, createdOn, exportedAt, currency, settings,
        accounts, categories, entries.map { it.toV1() }, recurring.map { it.toV1() }, people, ledger, budgets, merchantRules,
    )
}

internal fun BackupFileV1.toContent() = BackupContent(
    appVersion, createdOn, exportedAt, currency, settings,
    accounts, categories, entries.map { it.toV3() }, recurring.map { it.toV3() }, people, ledger, budgets, merchantRules,
)
