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
    /** The whole file, newlines included. 200,000 real entries are about 92 MB (PEN-002). */
    val maxTotalChars: Long = 256L * 1024 * 1024,
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
    private val compact = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val strict = Json { ignoreUnknownKeys = false }

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
                return EncodeResult.Failure("$what is too long to back up (${line.length} characters, limit ${limits.maxLineChars})")
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
        emit("header", s(HeaderV2.serializer()), header, "The settings")?.let { return it }
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
    private fun line(type: String, fields: JsonElement): String =
        compact.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("t", JsonPrimitive(type))
            fields.jsonObject.forEach { (k, v) -> put(k, v) }
        })

    // ----------------------------------------------------------------------------------------------- read

    fun read(lines: Sequence<String>, now: Instant, limits: BackupLimits, restoreAnyway: Boolean = false): DecodeResult {
        // Counts characters as they are read, newlines between lines included (PEN-002).
        var total = -1L
        val iterator = lines.map { total += it.length + 1; it }.iterator()
        fun tooLarge() = total > limits.maxTotalChars
        val tooLargeFailure = { BackupCodec.fail("The backup file is too large (more than ${limits.maxTotalChars} characters)") }
        var first: String? = null
        while (iterator.hasNext()) {
            val l = iterator.next().removePrefix("﻿").removeSuffix("\r") // FLAW-007: BOM, Windows line endings
            if (tooLarge()) return tooLargeFailure()
            if (l.isNotBlank()) { first = l; break }
        }
        if (first == null) return BackupCodec.fail("This backup file is empty")
        if (first.trim() == "{") return readV1(first, iterator, now, limits, restoreAnyway) // v1: one pretty-printed JSON document
        if (first.length > limits.maxLineChars) return BackupCodec.fail("Line 1 is too long (limit ${limits.maxLineChars} characters)")
        JsonKeys.firstDuplicate(first)?.let { return BackupCodec.fail("Line 1 has a duplicate key '$it' (the file was edited)") }
        val parsed = try { strict.parseToJsonElement(first) } catch (e: Exception) { null }
            ?: return BackupCodec.fail("This is not a Vyaya backup file (it is not valid JSON)")
        val header = parsed as? JsonObject ?: return BackupCodec.fail("This is not a Vyaya backup file (no header)")
        if (header["t"] == null && header["format"] != null) return readV1(first, iterator, now, limits, restoreAnyway)
        if ((header["t"] as? JsonPrimitive)?.content != "header" || (header["format"] as? JsonPrimitive)?.content != BackupCodec.FORMAT) {
            return BackupCodec.fail("This is not a Vyaya backup file (no header)")
        }
        val version = (header["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return BackupCodec.fail("This is not a Vyaya backup file (no schema version)")
        if (version > BackupCodec.SCHEMA_VERSION) {
            return BackupCodec.fail("This backup was made by a newer version of Vyaya (schema $version). Update the app to restore it.")
        }
        if (version != 2 && version != 3) return BackupCodec.fail("Unsupported backup schema version $version")
        val h = decode(HeaderV2.serializer(), header) ?: return BackupCodec.fail("Line 1 is damaged or was edited")

        val sha = Sha256().apply { update(first + "\n") }
        // Each record becomes a domain record as soon as it is read; nothing else is kept (PEN-003).
        val reader = Reader(now, restoreAnyway).apply { header(h.currency, h.exportedAt, h.settings) }
        val counts = IntArray(8) // per type, in the header's order
        var records = 0
        var trailer: TrailerV2? = null
        var number = 1

        while (iterator.hasNext()) {
            number++
            val l = iterator.next().removeSuffix("\r")
            if (tooLarge()) return tooLargeFailure()
            if (trailer != null) {
                if (l.isNotBlank()) return BackupCodec.fail("There is data after the end of the backup (line $number)")
                continue
            }
            if (l.length > limits.maxLineChars) return BackupCodec.fail("Line $number is too long (limit ${limits.maxLineChars} characters)")
            JsonKeys.firstDuplicate(l)?.let { return BackupCodec.fail("Line $number has a duplicate key '$it' (the file was edited)") }
            val obj = try { strict.parseToJsonElement(l) as? JsonObject } catch (e: Exception) { null }
                ?: return BackupCodec.fail("Line $number is damaged (not valid JSON)")
            val type = (obj["t"] as? JsonPrimitive)?.content
            if (type == "end") {
                trailer = decode(TrailerV2.serializer(), obj) ?: return BackupCodec.fail("The last line (line $number) is damaged")
                continue
            }
            sha.update(l + "\n")
            if (++records > limits.maxRecords) return BackupCodec.fail("The backup has too many records (limit ${limits.maxRecords})")
            val read = when (type) {
                "account" -> decode(AccountV1.serializer(), obj)?.let { reader.account(it); 0 }
                "category" -> decode(CategoryV1.serializer(), obj)?.let { reader.category(it); 1 }
                "entry" -> (if (version == 2) decode(EntryV1.serializer(), obj)?.toV3() else decode(EntryV3.serializer(), obj))
                    ?.let { reader.entry(it); 2 }
                "recurring" -> (if (version == 2) decode(RecurringV1.serializer(), obj)?.toV3() else decode(RecurringV3.serializer(), obj))
                    ?.let { reader.recurring(it); 3 }
                "person" -> decode(PersonV1.serializer(), obj)?.let { reader.person(it); 4 }
                "ledger" -> decode(LedgerV1.serializer(), obj)?.let { reader.ledger(it); 5 }
                "budget" -> decode(BudgetV1.serializer(), obj)?.let { reader.budget(it); 6 }
                "merchantRule" -> decode(MerchantRuleV1.serializer(), obj)?.let { reader.merchantRule(it); 7 }
                else -> return BackupCodec.fail("Line $number has an unknown record type '${type?.take(30)}'")
            } ?: return BackupCodec.fail("Line $number is damaged or was edited")
            counts[read]++
            if (reader.full) return reader.stoppedAt(number) // PEN-002: fail fast on a file full of bad records
        }

        if (trailer == null) return BackupCodec.fail("The backup file is incomplete: it was cut off before the end")
        if (trailer.sha256 != sha.hexDigest()) return BackupCodec.fail("The backup file is damaged or edited (its checksum does not match)")
        if (trailer.records != records) return BackupCodec.fail("The backup file says it has ${trailer.records} records but has $records")
        val c = h.counts
        val mismatches = listOf(
            Triple("accounts", c.accounts, counts[0]), Triple("categories", c.categories, counts[1]),
            Triple("entries", c.entries, counts[2]), Triple("recurring", c.recurring, counts[3]),
            Triple("people", c.people, counts[4]), Triple("ledger", c.ledger, counts[5]),
            Triple("budgets", c.budgets, counts[6]), Triple("merchantRules", c.merchantRules, counts[7]),
        ).filter { it.second != it.third }.map { "${it.first}: the header says ${it.second} but the file has ${it.third} (count mismatch)" }
        if (mismatches.isNotEmpty()) return DecodeResult.Failure(mismatches)
        return reader.finish(h.appVersion, h.createdOn)
    }

    /** A v1 file: collect the rest of the document (within the v1 size limit) and decode it as before. */
    private fun readV1(first: String, rest: Iterator<String>, now: Instant, limits: BackupLimits, restoreAnyway: Boolean): DecodeResult {
        val text = StringBuilder(first)
        while (rest.hasNext()) {
            text.append('\n').append(rest.next())
            if (text.length > limits.maxV1Chars) return BackupCodec.fail("Backup file is too large (more than ${limits.maxV1Chars} characters)")
        }
        return BackupCodec.decodeV1(text.toString(), now, limits.maxV1Chars, restoreAnyway)
    }

    /** Decodes a record line (without its "t" field); null if it doesn't match the record's shape. */
    private fun <T> decode(serializer: KSerializer<T>, obj: JsonObject): T? = try {
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
