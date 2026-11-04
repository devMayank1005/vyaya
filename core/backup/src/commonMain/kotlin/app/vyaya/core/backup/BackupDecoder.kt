package app.vyaya.core.backup

import app.vyaya.core.model.Entry
import app.vyaya.core.model.Sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Instant

/**
 * Reads a backup one line at a time: [accept] each line in order until it returns false, then [end] gives the result.
 * [BackupCodec.read] is this over a sequence of lines, so both apply exactly the same checks.
 *
 * With [streamEntries], a v2 or v3 file's valid entries are handed out as they are read ([takeEntries]) instead of
 * being kept, and the snapshot [end] gives has none: a restore into the database writes them as it goes and so never
 * holds them all (PEN-003). What it takes is only good once [end] succeeds; on a failure it must be thrown away
 * (the database rolls the restore back). A v1 file (small, from an earlier version) still comes whole from [end].
 */
class BackupDecoder(
    private val now: Instant,
    private val limits: BackupLimits = BackupLimits(),
    private val restoreAnyway: Boolean = false,
    private val streamEntries: Boolean = false,
) {
    /** Characters read, newlines between lines included (PEN-002). */
    private var total = -1L
    private var stopped: DecodeResult? = null
    private var v1: StringBuilder? = null

    private var version = 0
    private var header: HeaderV2? = null
    private var reader: Reader? = null
    private val sha = Sha256()
    private val counts = IntArray(8) // per type, in the header's order
    private var records = 0
    private var trailer: TrailerV2? = null
    private var number = 1

    /** Reads the next line; false means stop reading (the result is decided) and call [end]. */
    fun accept(line: String): Boolean {
        if (stopped != null) return false
        total += line.length + 1
        stopped = when {
            v1 != null -> v1Line(line)
            header == null -> firstLine(line)
            else -> recordLine(line)
        }
        return stopped == null
    }

    /** Valid entries read since the last call ([streamEntries] only). */
    fun takeEntries(): List<Entry> {
        val r = reader ?: return emptyList()
        if (r.streamed.isEmpty()) return emptyList()
        return r.streamed.also { r.streamed = ArrayList() }
    }

    /** The result, once every line was given (or [accept] said stop). */
    fun end(): DecodeResult {
        stopped?.let { return it }
        v1?.let { return BackupCodec.decodeV1(it.toString(), now, limits.maxV1Chars, restoreAnyway, limits) }
        val h = header ?: return BackupCodec.fail("This backup file is empty")
        val trailer = trailer ?: return BackupCodec.fail("The backup file is incomplete: it was cut off before the end")
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
        return reader!!.finish(h.appVersion, h.createdOn)
    }

    private fun tooLarge(): DecodeResult? =
        if (total > limits.maxTotalChars) BackupCodec.fail("The backup file is too large (more than ${limits.maxTotalChars} characters)") else null

    /** A v1 file: collect the rest of the document (within the v1 size limit) and decode it at the end, as before. */
    private fun v1Line(line: String): DecodeResult? {
        val text = v1!!.append('\n').append(line)
        return if (text.length > limits.maxV1Chars) BackupCodec.fail("Backup file is too large (more than ${limits.maxV1Chars} characters)") else null
    }

    private fun firstLine(raw: String): DecodeResult? {
        val first = raw.removePrefix("﻿").removeSuffix("\r") // FLAW-007: BOM, Windows line endings
        tooLarge()?.let { return it }
        if (first.isBlank()) return null
        if (first.trim() == "{") { v1 = StringBuilder(first); return null } // v1: one pretty-printed JSON document
        if (first.length > limits.maxLineChars) return BackupCodec.fail("Line 1 is too long (limit ${limits.maxLineChars} characters)")
        JsonKeys.firstDuplicate(first)?.let { return BackupCodec.fail("Line 1 has a duplicate key '$it' (the file was edited)") }
        val parsed = try { BackupLines.strict.parseToJsonElement(first) } catch (e: Exception) { null }
            ?: return BackupCodec.fail("This is not a Vyaya backup file (it is not valid JSON)")
        val obj = parsed as? JsonObject ?: return BackupCodec.fail("This is not a Vyaya backup file (no header)")
        if (obj["t"] == null && obj["format"] != null) { v1 = StringBuilder(first); return null }
        if ((obj["t"] as? JsonPrimitive)?.content != "header" || (obj["format"] as? JsonPrimitive)?.content != BackupCodec.FORMAT) {
            return BackupCodec.fail("This is not a Vyaya backup file (no header)")
        }
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return BackupCodec.fail("This is not a Vyaya backup file (no schema version)")
        if (version > BackupCodec.SCHEMA_VERSION) {
            return BackupCodec.fail("This backup was made by a newer version of Vyaya (schema $version). Update the app to restore it.")
        }
        if (version != 2 && version != 3) return BackupCodec.fail("Unsupported backup schema version $version")
        val h = BackupLines.decode(HeaderV2.serializer(), obj) ?: return BackupCodec.fail("Line 1 is damaged or was edited")
        sha.update(first + "\n")
        // Each record becomes a domain record as soon as it is read; nothing else is kept (PEN-003).
        reader = Reader(now, restoreAnyway, streamEntries).apply { header(h.currency, h.exportedAt, h.settings) }
        this.version = version
        header = h
        return null
    }

    private fun recordLine(raw: String): DecodeResult? {
        number++
        val l = raw.removeSuffix("\r")
        tooLarge()?.let { return it }
        if (trailer != null) {
            return if (l.isNotBlank()) BackupCodec.fail("There is data after the end of the backup (line $number)") else null
        }
        if (l.length > limits.maxLineChars) return BackupCodec.fail("Line $number is too long (limit ${limits.maxLineChars} characters)")
        JsonKeys.firstDuplicate(l)?.let { return BackupCodec.fail("Line $number has a duplicate key '$it' (the file was edited)") }
        val obj = try { BackupLines.strict.parseToJsonElement(l) as? JsonObject } catch (e: Exception) { null }
            ?: return BackupCodec.fail("Line $number is damaged (not valid JSON)")
        val type = (obj["t"] as? JsonPrimitive)?.content
        if (type == "end") {
            trailer = BackupLines.decode(TrailerV2.serializer(), obj) ?: return BackupCodec.fail("The last line (line $number) is damaged")
            return null
        }
        sha.update(l + "\n")
        if (++records > limits.maxRecords) return BackupCodec.fail("The backup has too many records (limit ${limits.maxRecords})")
        val reader = reader!!
        val read = when (type) {
            "account" -> BackupLines.decode(AccountV1.serializer(), obj)?.let { reader.account(it); 0 }
            "category" -> BackupLines.decode(CategoryV1.serializer(), obj)?.let { reader.category(it); 1 }
            "entry" -> (if (version == 2) BackupLines.decode(EntryV1.serializer(), obj)?.toV3() else BackupLines.decode(EntryV3.serializer(), obj))
                ?.let { reader.entry(it); 2 }
            "recurring" -> (if (version == 2) BackupLines.decode(RecurringV1.serializer(), obj)?.toV3() else BackupLines.decode(RecurringV3.serializer(), obj))
                ?.let { reader.recurring(it); 3 }
            "person" -> BackupLines.decode(PersonV1.serializer(), obj)?.let { reader.person(it); 4 }
            "ledger" -> BackupLines.decode(LedgerV1.serializer(), obj)?.let { reader.ledger(it); 5 }
            "budget" -> BackupLines.decode(BudgetV1.serializer(), obj)?.let { reader.budget(it); 6 }
            "merchantRule" -> BackupLines.decode(MerchantRuleV1.serializer(), obj)?.let { reader.merchantRule(it); 7 }
            else -> return BackupCodec.fail("Line $number has an unknown record type '${type?.take(30)}'")
        } ?: return BackupCodec.fail("Line $number is damaged or was edited")
        counts[read]++
        // PEN-002: stop as soon as one type passes its limit, before more of them are held.
        if (read != BackupLimits.ENTRIES && counts[read] > limits.maxRecordsPerType) return BackupCodec.fail(limits.tooManyToRead(counts.toList())!!)
        return if (reader.full) reader.stoppedAt(number) else null // PEN-002: fail fast on a file full of bad records
    }
}
