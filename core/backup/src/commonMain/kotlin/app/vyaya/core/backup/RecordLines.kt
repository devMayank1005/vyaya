package app.vyaya.core.backup

import app.vyaya.core.model.Entry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * Every record of a snapshot as its exact backup v3 line, per type, in backup order (each type by id), plus the
 * settings. What the database stores, one row per line.
 */
data class SnapshotLines(
    val settings: String,
    val accounts: List<String>,
    val categories: List<String>,
    val entries: List<String>,
    val recurring: List<String>,
    val people: List<String>,
    val ledger: List<String>,
    val budgets: List<String>,
    val merchantRules: List<String>,
) {
    /** All record lines in backup order (without the settings). */
    val records: List<String> get() = accounts + categories + entries + recurring + people + ledger + budgets + merchantRules
}

/**
 * One record as its exact backup v3 line, and back. The database (core/data) stores each record this way, so backup
 * export and restore stream rows as they are, with no second format to keep in step.
 *
 * Reading checks what a restore checks for a single record (shape, types, ranges, the model's rules). What needs the
 * other records (references, duplicates, the export time) is checked by a restore as a whole.
 */
object RecordLines {

    @Serializable
    private class SettingsLine(val currency: String, val settings: SettingsV1)

    private val json = Json { ignoreUnknownKeys = false }
    private val anyTime = Instant.parse("2000-01-01T00:00:00Z")

    /** The currency and settings in a settings line; null when it can't be read. */
    internal fun settingsOf(line: String): Pair<String, SettingsV1>? =
        runCatching { json.decodeFromString(SettingsLine.serializer(), line) }.getOrNull()?.let { it.currency to it.settings }

    fun entry(e: Entry): String = line("entry", EntryV3.serializer(), BackupCodec.entryV3(e))

    /** The entry in [line]; null when it isn't a valid entry line. */
    fun entryOf(line: String): Entry? {
        val record = decode(line, "entry", EntryV3.serializer()) ?: return null
        return Reader(Instant.fromEpochSeconds(0)).run { entry(record); entriesRead.singleOrNull() }
    }

    fun of(snapshot: Snapshot): SnapshotLines {
        val c = BackupCodec.toContent(snapshot, ExportMeta("", "", anyTime))
        return SnapshotLines(
            settings = json.encodeToString(SettingsLine.serializer(), SettingsLine(c.currency, c.settings)),
            accounts = c.accounts.map { line("account", AccountV1.serializer(), it) },
            categories = c.categories.map { line("category", CategoryV1.serializer(), it) },
            entries = c.entries.map { line("entry", EntryV3.serializer(), it) },
            recurring = c.recurring.map { line("recurring", RecurringV3.serializer(), it) },
            people = c.people.map { line("person", PersonV1.serializer(), it) },
            ledger = c.ledger.map { line("ledger", LedgerV1.serializer(), it) },
            budgets = c.budgets.map { line("budget", BudgetV1.serializer(), it) },
            merchantRules = c.merchantRules.map { line("merchantRule", MerchantRuleV1.serializer(), it) },
        )
    }

    /** The snapshot in [lines]; null when any line isn't a valid record of its type, or the settings aren't valid. */
    fun snapshotOf(lines: SnapshotLines): Snapshot? {
        val settings = runCatching { json.decodeFromString(SettingsLine.serializer(), lines.settings) }.getOrNull() ?: return null
        val reader = Reader(Instant.fromEpochSeconds(0))
        reader.header(settings.currency, anyTime.toString(), settings.settings)
        fun <T> each(list: List<String>, type: String, serializer: KSerializer<T>, read: (T) -> Unit): Boolean =
            list.all { line -> decode(line, type, serializer)?.also(read) != null }
        val decoded = each(lines.accounts, "account", AccountV1.serializer(), reader::account) &&
            each(lines.categories, "category", CategoryV1.serializer(), reader::category) &&
            each(lines.entries, "entry", EntryV3.serializer(), reader::entry) &&
            each(lines.recurring, "recurring", RecurringV3.serializer(), reader::recurring) &&
            each(lines.people, "person", PersonV1.serializer(), reader::person) &&
            each(lines.ledger, "ledger", LedgerV1.serializer(), reader::ledger) &&
            each(lines.budgets, "budget", BudgetV1.serializer(), reader::budget) &&
            each(lines.merchantRules, "merchantRule", MerchantRuleV1.serializer(), reader::merchantRule)
        if (!decoded) return null
        val s = reader.snapshotRead() ?: return null
        val counts = listOf(s.accounts, s.categories, s.entries, s.recurring, s.people, s.ledger, s.budgets, s.merchantRules).map { it.size }
        val expected = listOf(lines.accounts, lines.categories, lines.entries, lines.recurring, lines.people, lines.ledger, lines.budgets, lines.merchantRules).map { it.size }
        return if (counts == expected) s.normalized() else null // a record that failed the model's rules was dropped
    }

    private fun <T> line(type: String, serializer: KSerializer<T>, record: T): String =
        BackupLines.line(type, BackupLines.compact.encodeToJsonElement(serializer, record))

    private fun <T> decode(line: String, type: String, serializer: KSerializer<T>): T? {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        if ((obj["t"] as? JsonPrimitive)?.content != type || JsonKeys.firstDuplicate(line) != null) return null
        return BackupLines.decode(serializer, obj)
    }
}
