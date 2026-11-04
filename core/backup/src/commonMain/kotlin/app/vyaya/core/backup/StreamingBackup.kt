package app.vyaya.core.backup

import app.vyaya.core.model.Sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** How many records of each type a backup holds (its header says so before the records come). */
data class RecordCounts(
    val accounts: Int, val categories: Int, val entries: Int, val recurring: Int,
    val people: Int, val ledger: Int, val budgets: Int, val merchantRules: Int,
) {
    val total: Int get() = accounts + categories + entries + recurring + people + ledger + budgets + merchantRules

    internal fun list() = listOf(accounts, categories, entries, recurring, people, ledger, budgets, merchantRules)
}

/**
 * Writes a backup v3 from record lines that arrive one at a time (the database pages through its rows), so no whole
 * snapshot is ever in memory (PEN-003). The caller sends [record] lines in backup order: accounts, categories, entries,
 * recurring rules, people, ledger, budgets, merchant rules, each type by id. The bytes are the same as
 * [BackupCodec.write] gives for the same data, with the same line, size and record limits.
 */
class StreamingBackup private constructor(
    private val limits: BackupLimits, private val sink: (String) -> Unit, private val total: Int, exportedAt: kotlin.time.Instant,
) {
    /**
     * DATA-001: every line is also read back as a restore would read it (entries are handed out and dropped, so this
     * needs no more memory), and [finish] fails if the restore would refuse the file. The rows are copied as they are,
     * so this is where data a restore refuses (a change after the export time, a broken reference) is caught.
     */
    private val readBack = BackupDecoder(exportedAt, limits, streamEntries = true)

    private val sha = Sha256()
    private var written = 0L
    private var count = 0

    private fun emit(line: String, what: String): EncodeResult.Failure? {
        if (line.length > limits.maxLineChars) {
            val size = "(${line.length} characters, limit ${limits.maxLineChars})"
            return EncodeResult.Failure(if (what == "Settings") "Settings are too large to back up $size" else "$what is too long to back up $size")
        }
        written += line.length + 1
        if (written > limits.maxTotalChars) return tooLarge()
        sha.update(line + "\n")
        readBack.accept(line)
        readBack.takeEntries()
        sink(line)
        return null
    }

    private fun tooLarge() = EncodeResult.Failure("The backup is too large (more than ${limits.maxTotalChars} characters)")

    /** One record line ([what] names it in a failure); a failure means stop and throw the partial file away. */
    fun record(line: String, what: String = "A record"): EncodeResult.Failure? {
        count++
        return emit(line, what)
    }

    /** The checksum line. The number of records sent must be what the header said. */
    fun finish(): EncodeResult {
        if (count != total) return EncodeResult.Failure("The data changed while it was being backed up ($count records, expected $total)")
        val end = BackupLines.compact.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("t", JsonPrimitive("end")); put("records", JsonPrimitive(total)); put("sha256", JsonPrimitive(sha.hexDigest()))
        })
        if (written + end.length + 1 > limits.maxTotalChars) return tooLarge()
        readBack.accept(end)
        val restored = readBack.end()
        if (restored is DecodeResult.Failure) {
            return EncodeResult.Failure("This data can't be backed up because it couldn't be restored: ${restored.problems.joinToString("; ")}")
        }
        sink(end)
        return EncodeResult.Success
    }

    companion object {
        /** Starts a backup: writes the header. [settingsLine] is the database's settings row ([SnapshotLines.settings]). */
        fun begin(meta: ExportMeta, settingsLine: String, counts: RecordCounts, limits: BackupLimits = BackupLimits(), sink: (String) -> Unit):
            Pair<StreamingBackup?, EncodeResult.Failure?> {
            if (counts.total > limits.maxRecords) return null to EncodeResult.Failure("Too many records to back up: ${counts.total} (limit ${limits.maxRecords})")
            limits.tooManyToWrite(counts.list())?.let { return null to it }
            val (currency, settings) = RecordLines.settingsOf(settingsLine) ?: return null to EncodeResult.Failure("The settings can't be read")
            val header = HeaderV2(
                BackupCodec.FORMAT, BackupCodec.SCHEMA_VERSION, meta.appVersion, meta.createdOn, meta.exportedAt.toString(), currency, settings,
                CountsV2(counts.accounts, counts.categories, counts.entries, counts.recurring, counts.people, counts.ledger, counts.budgets, counts.merchantRules),
            )
            val writer = StreamingBackup(limits, sink, counts.total, meta.exportedAt)
            writer.emit(BackupLines.line("header", BackupLines.compact.encodeToJsonElement(HeaderV2.serializer(), header)), "Settings")
                ?.let { return null to it }
            return writer to null
        }
    }
}
