package app.vyaya.core.data

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import app.vyaya.core.backup.BackupDecoder
import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.RecordCounts
import app.vyaya.core.backup.RecordLines
import app.vyaya.core.backup.Restore
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.backup.StreamingBackup
import app.vyaya.core.model.Entry
import kotlin.time.Instant

/** What a restore did. */
sealed interface RestoreResult {
    data class Restored(val meta: ExportMeta) : RestoreResult

    /** Nothing was changed; [failure] says why (and whether "Restore anyway" may be offered). */
    data class Refused(val failure: DecodeResult.Failure) : RestoreResult
}

/**
 * Backups straight from and into the database.
 *
 * Export reads each table in id order, [pageSize] rows at a time, and writes each row's line as it is (rows are
 * backup lines), so a backup of any size needs only a page in memory (PEN-003). It runs in one read transaction, so
 * a capture arriving meanwhile is either all in the file or not at all.
 *
 * Restore reads the file with the backup reader and writes as it reads, inside one transaction: any problem, or a
 * crash half-way, leaves the database as it was.
 */
class BackupStore(private val db: VyayaDatabase, private val pageSize: Int = 2_000) {

    init { require(pageSize > 0) }

    private val snapshots = SnapshotStore(db)

    suspend fun export(meta: ExportMeta, limits: BackupLimits = BackupLimits(), sink: (String) -> Unit): EncodeResult =
        db.useReaderConnection { transactor ->
            transactor.deferredTransaction {
                val records = db.records()
                val counts = RecordCounts(
                    records.count("account"), records.count("category"), db.entries().count(), records.count("recurring"),
                    records.count("person"), records.count("ledger"), records.count("budget"), records.count("merchantRule"),
                )
                val settings = records.pageAfter(SnapshotStore.SETTINGS, "", 1).singleOrNull()?.line ?: RecordLines.of(Snapshot.empty()).settings
                val (writer, failure) = StreamingBackup.begin(meta, settings, counts, limits, sink)
                if (writer == null) return@deferredTransaction failure!!

                suspend fun copy(what: String, page: suspend (afterId: String, limit: Int) -> List<IdLine>): EncodeResult.Failure? {
                    var after = "" // every id sorts after the empty string
                    while (true) {
                        val rows = page(after, pageSize)
                        rows.forEach { row -> writer.record(row.line, what)?.let { return it } }
                        if (rows.size < pageSize) return null
                        after = rows.last().id
                    }
                }
                for ((kind, what) in KINDS) {
                    val failure = if (kind == ENTRY) copy(what) { a, l -> db.entries().pageAfter(a, l) }
                    else copy(what) { a, l -> records.pageAfter(kind, a, l) }
                    failure?.let { return@deferredTransaction it }
                }
                writer.finish()
            }
        }

    /**
     * Restores [lines] (a backup file read line by line). Entries go to the database in batches of [pageSize] as they
     * are read, inside one transaction; when the file turns out bad (checksum, counts and references are only known at
     * the end) the transaction is rolled back, so on [RestoreResult.Refused] the database is unchanged.
     */
    suspend fun restore(lines: Sequence<String>, mode: RestoreMode, now: Instant, restoreAnyway: Boolean = false): RestoreResult {
        val decoder = BackupDecoder(now, restoreAnyway = restoreAnyway, streamEntries = true)
        return try {
            db.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (mode == RestoreMode.REPLACE) {
                        db.records().clear()
                        db.entries().clear()
                        // DATA-003: open Detected items are about data the backup replaces; undo or confirm would act on it.
                        db.detected().closeOpen()
                    }
                    val batch = ArrayList<Entry>(pageSize)
                    suspend fun flush() { if (batch.isNotEmpty()) { writeEntries(batch, mode); batch.clear() } }
                    for (line in lines) {
                        val more = decoder.accept(line)
                        batch += decoder.takeEntries()
                        if (batch.size >= pageSize) flush()
                        if (!more) break
                    }
                    val result = decoder.end()
                    if (result !is DecodeResult.Success) throw Refusal(result as DecodeResult.Failure)
                    batch += decoder.takeEntries()
                    batch += result.snapshot.entries // a v1 file comes whole
                    flush()
                    val records = result.snapshot.copy(entries = emptyList())
                    snapshots.saveInTransaction(
                        if (mode == RestoreMode.REPLACE) records
                        // The other records are few: merged in memory by the same rule (entries were merged above).
                        else Restore.apply(snapshots.loadInTransaction(withEntries = false), records, mode),
                    )
                    RestoreResult.Restored(result.meta)
                }
            }
        } catch (refusal: Refusal) {
            RestoreResult.Refused(refusal.failure)
        }
    }

    /** Replace writes them all; merge keeps the current copy unless the backup's is newer ([Restore.apply]'s rule). */
    private suspend fun writeEntries(entries: List<Entry>, mode: RestoreMode) {
        val incoming = if (mode == RestoreMode.REPLACE) entries else {
            val current = lookupChunks(entries.map { it.id.value }).flatMap { db.entries().byIds(it) }.associate { row ->
                // Compared exactly (the line keeps the full time), not by the millisecond column.
                row.id to (RecordLines.entryOf(row.line) ?: error("the database holds a record that is not valid")).updatedAt
            }
            entries.filter { e -> current[e.id.value].let { it == null || e.updatedAt > it } }
        }
        db.entries().upsert(incoming.map(EntryStore::row))
    }

    /** Thrown inside the transaction to roll it back. */
    private class Refusal(val failure: DecodeResult.Failure) : Exception()

    internal companion object {
        /** Review 2026-11-06 #3: the system SQLite before Android 11 allows 999 variables in one statement. */
        const val MAX_SQL_VARIABLES = 500

        /** Ids for `IN (...)` lookups, in statements small enough for every Android version. */
        fun lookupChunks(ids: List<String>): List<List<String>> = ids.chunked(MAX_SQL_VARIABLES)

        const val ENTRY = "entry"
        /** Backup order (the same as the writer's), with the name a failure gives a record of that kind. */
        val KINDS = listOf(
            "account" to "An account", "category" to "A category", ENTRY to "An entry", "recurring" to "A recurring rule",
            "person" to "A person", "ledger" to "A ledger entry", "budget" to "A budget", "merchantRule" to "A merchant rule",
        )
    }
}
