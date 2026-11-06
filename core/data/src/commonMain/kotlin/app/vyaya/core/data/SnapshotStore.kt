package app.vyaya.core.data

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import app.vyaya.core.backup.RecordLines
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.backup.SnapshotLines
import app.vyaya.core.model.EntityId
import kotlin.time.Instant

/** All the app's data on the database, each record as its backup line (the user's choice of design). */
class SnapshotStore(private val db: VyayaDatabase) {

    /** Saves every record of [s] and its settings, replacing what has the same id. Records not in [s] are left as they are. */
    suspend fun save(s: Snapshot) {
        val n = s.normalized()
        val records = rowsOf(n)
        db.useWriterConnection { transactor -> transactor.immediateTransaction { write(records, n) } }
    }

    /** The writes of [save], for a caller already inside a transaction (restore). */
    internal suspend fun saveInTransaction(s: Snapshot) {
        val n = s.normalized()
        write(rowsOf(n), n)
    }

    private suspend fun write(records: List<RecordRow>, n: Snapshot) {
        db.records().upsert(records)
        db.entries().upsert(n.entries.map(EntryStore::row))
    }

    private fun rowsOf(n: Snapshot): List<RecordRow> {
        val lines = RecordLines.of(n)
        fun rows(kind: String, ids: List<Triple<EntityId, Boolean, Instant>>, lines: List<String>) =
            ids.zip(lines) { (id, deleted, updatedAt), line -> RecordRow(kind, id.value, line, deleted, updatedAt.toEpochMilliseconds()) }
        return rows("account", n.accounts.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.accounts) +
            rows("category", n.categories.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.categories) +
            rows("recurring", n.recurring.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.recurring) +
            rows("person", n.people.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.people) +
            rows("ledger", n.ledger.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.ledger) +
            rows("budget", n.budgets.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.budgets) +
            rows("merchantRule", n.merchantRules.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.merchantRules) +
            RecordRow(SETTINGS, SETTINGS, lines.settings, false, 0)
    }

    /** Everything on the database; the default snapshot when it is empty. */
    suspend fun load(): Snapshot = db.useReaderConnection { transactor -> transactor.deferredTransaction { loadInTransaction() } }

    /** Everything but the entries (few records: settings, categories, accounts, rules…). */
    suspend fun loadRecords(): Snapshot =
        db.useReaderConnection { transactor -> transactor.deferredTransaction { loadInTransaction(withEntries = false) } }

    /** [load] for a caller already inside a transaction; without the entries when not [withEntries]. */
    internal suspend fun loadInTransaction(withEntries: Boolean = true): Snapshot {
        val byKind = db.records().all().groupBy({ it.kind }, { it.line })
        val entries = if (withEntries) db.entries().all().map { it.line } else emptyList()
        if (byKind.isEmpty() && entries.isEmpty()) return Snapshot.empty()
        val lines = SnapshotLines(
            settings = byKind[SETTINGS]?.single() ?: RecordLines.of(Snapshot.empty()).settings,
            accounts = byKind["account"].orEmpty(), categories = byKind["category"].orEmpty(), entries = entries,
            recurring = byKind["recurring"].orEmpty(), people = byKind["person"].orEmpty(), ledger = byKind["ledger"].orEmpty(),
            budgets = byKind["budget"].orEmpty(), merchantRules = byKind["merchantRule"].orEmpty(),
        )
        // Rows are written only by this app, from valid records; a row that doesn't read back is a damaged database.
        return RecordLines.snapshotOf(lines) ?: error("the database holds a record that is not valid")
    }

    internal companion object {
        const val SETTINGS = "settings"
    }
}
