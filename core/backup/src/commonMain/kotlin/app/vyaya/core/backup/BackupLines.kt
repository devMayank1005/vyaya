package app.vyaya.core.backup

import app.vyaya.core.model.Sha256
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/*
 * Line-based backup files: schema versions 2 and 3 (plan.md Part D). Each version is FROZEN; v3 differs from v2
 * only in the entry lines (see BackupV3.kt). The app writes v3 and reads both.
 *
 *   {"t":"header", format, schemaVersion: 2, appVersion, createdOn, exportedAt, currency, settings, counts}
 *   {"t":"account", ...}   one compact JSON object per record, the v1 record fields, ordered by type then id
 *   ...
 *   {"t":"end","records":N,"sha256":"<hex>"}   SHA-256 of every line before it, each followed by "\n"
 *
 * Written and read line by line, so no single huge string is ever built (PEN-002). Each line is small, and
 * the reader enforces exactly the limits the writer does (DATA-001). The checksum detects damage and edits
 * (DATA-003). Integrity only: authenticity comes with encrypted backups.
 */

/** Limits shared by writer and reader, so the app can always restore what it wrote. */
data class BackupLimits(
    /** v1 files are a single JSON document; refuse larger ones unread. */
    val maxV1Chars: Int = 64 * 1024 * 1024,
    /**
     * The largest valid record is about 10,000 characters (every field at its limit, every character escaped),
     * so a crafted line can't be much bigger than a real one (PEN-002).
     */
    val maxLineChars: Int = 16_384,
    val maxRecords: Int = 2_000_000,
    /**
     * The whole file, newlines included. 200,000 real entries are about 96 MB; a valid file at 256 MB needed about
     * 371 MB to read, more than low-end phones give an app (PEN-002).
     */
    val maxTotalChars: Long = 128L * 1024 * 1024,
)

sealed interface EncodeResult {
    data object Success : EncodeResult
    data class Failure(val reason: String) : EncodeResult
}

@Serializable
internal data class HeaderV2(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val createdOn: String,
    val exportedAt: String,
    val currency: String,
    val settings: SettingsV1,
    val counts: CountsV2,
)

@Serializable
internal data class CountsV2(
    val accounts: Int, val categories: Int, val entries: Int, val recurring: Int,
    val people: Int, val ledger: Int, val budgets: Int, val merchantRules: Int,
)

@Serializable
internal data class TrailerV2(val records: Int, val sha256: String)

internal object BackupLines {
    internal val compact = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    internal val strict = Json { ignoreUnknownKeys = false }

    // ---------------------------------------------------------------------------------------------- write

    /** Writes [file] as schema [version] (3; 2 only for test fixtures). */
    fun write(file: BackupContent, version: Int, limits: BackupLimits, sink: (String) -> Unit): EncodeResult {
        require(version == 2 || version == BackupCodec.SCHEMA_VERSION) { "can't write schema $version" }
        val total = file.recordCount
        if (total > limits.maxRecords) return EncodeResult.Failure("Too many records to back up: $total (limit ${limits.maxRecords})")

        val sha = Sha256()
        var written = 0L
        fun tooLarge() = EncodeResult.Failure("The backup is too large (more than ${limits.maxTotalChars} characters)")
        fun emit(type: String, serializer: KSerializer<Any>, value: Any, what: String): EncodeResult.Failure? {
            val line = line(type, compact.encodeToJsonElement(serializer, value))
            if (line.length > limits.maxLineChars) {
                val size = "(${line.length} characters, limit ${limits.maxLineChars})"
                // FLAW-005: the header holds the settings, which are plural.
                return EncodeResult.Failure(if (type == "header") "Settings are too large to back up $size" else "$what is too long to back up $size")
            }
            written += line.length + 1
            if (written > limits.maxTotalChars) return tooLarge()
            sha.update(line + "\n")
            sink(line)
            return null
        }

        val header = HeaderV2(
            BackupCodec.FORMAT, version, file.appVersion, file.createdOn, file.exportedAt, file.currency, file.settings,
            CountsV2(file.accounts.size, file.categories.size, file.entries.size, file.recurring.size, file.people.size,
                file.ledger.size, file.budgets.size, file.merchantRules.size),
        )
        @Suppress("UNCHECKED_CAST")
        fun <T> s(serializer: KSerializer<T>) = serializer as KSerializer<Any>
        emit("header", s(HeaderV2.serializer()), header, "Settings")?.let { return it }
        file.accounts.forEach { emit("account", s(AccountV1.serializer()), it, "Account ${it.id}")?.let { f -> return f } }
        file.categories.forEach { emit("category", s(CategoryV1.serializer()), it, "Category ${it.id}")?.let { f -> return f } }
        file.entries.forEach { e ->
            val failure = if (version == 2) emit("entry", s(EntryV1.serializer()), e.toV1(), "Entry ${e.id}")
            else emit("entry", s(EntryV3.serializer()), e, "Entry ${e.id}")
            failure?.let { return it }
        }
        file.recurring.forEach { r ->
            val failure = if (version == 2) emit("recurring", s(RecurringV1.serializer()), r.toV1(), "Recurring rule ${r.id}")
            else emit("recurring", s(RecurringV3.serializer()), r, "Recurring rule ${r.id}")
            failure?.let { return it }
        }
        file.people.forEach { emit("person", s(PersonV1.serializer()), it, "Person ${it.id}")?.let { f -> return f } }
        file.ledger.forEach { emit("ledger", s(LedgerV1.serializer()), it, "Ledger entry ${it.id}")?.let { f -> return f } }
        file.budgets.forEach { emit("budget", s(BudgetV1.serializer()), it, "Budget ${it.id}")?.let { f -> return f } }
        file.merchantRules.forEach { emit("merchantRule", s(MerchantRuleV1.serializer()), it, "Merchant rule ${it.id}")?.let { f -> return f } }

        val end = compact.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("t", JsonPrimitive("end")); put("records", JsonPrimitive(total)); put("sha256", JsonPrimitive(sha.hexDigest()))
        })
        if (written + end.length + 1 > limits.maxTotalChars) return tooLarge()
        sink(end)
        return EncodeResult.Success
    }

    /** `{"t": type, ...fields}`: the type first, then the record's fields in declaration order. */
    internal fun line(type: String, fields: JsonElement): String =
        compact.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("t", JsonPrimitive(type))
            fields.jsonObject.forEach { (k, v) -> put(k, v) }
        })

    // ----------------------------------------------------------------------------------------------- read

    fun read(lines: Sequence<String>, now: Instant, limits: BackupLimits, restoreAnyway: Boolean = false): DecodeResult {
        val decoder = BackupDecoder(now, limits, restoreAnyway)
        for (line in lines) if (!decoder.accept(line)) break
        return decoder.end()
    }

    /** Decodes a record line (without its "t" field); null if it doesn't match the record's shape. */
    internal fun <T> decode(serializer: KSerializer<T>, obj: JsonObject): T? = try {
        strict.decodeFromJsonElement(serializer, JsonObject(obj - "t"))
    } catch (e: Exception) {
        null
    }
}

/** Finds a key that appears twice in the same JSON object (FLAW-006). Keys are compared after unescaping. */
internal object JsonKeys {

    fun firstDuplicate(json: String): String? {
        val stack = ArrayList<MutableSet<String>?>() // a key set per object; null for arrays
        var expectKey = false
        var i = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> { stack.add(mutableSetOf()); expectKey = true }
                '[' -> { stack.add(null); expectKey = false }
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); expectKey = false }
                ',' -> expectKey = stack.lastOrNull() != null
                ':' -> expectKey = false
                '"' -> {
                    var j = i + 1
                    while (j < json.length && json[j] != '"') j += if (json[j] == '\\') 2 else 1
                    val keys = stack.lastOrNull()
                    if (expectKey && keys != null) {
                        val key = unescape(json.substring(i + 1, minOf(j, json.length)))
                        if (key != null && !keys.add(key)) return key
                        expectKey = false
                    }
                    i = j
                }
            }
            i++
        }
        return null
    }

    private fun unescape(raw: String): String? = try {
        Json.decodeFromString(String.serializer(), "\"$raw\"")
    } catch (e: Exception) {
        null
    }
}
