package app.vyaya.core.data

import androidx.room.immediateTransaction
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
        val lines = RecordLines.of(n)
        fun rows(kind: String, ids: List<Triple<EntityId, Boolean, Instant>>, lines: List<String>) =
            ids.zip(lines) { (id, deleted, updatedAt), line -> RecordRow(kind, id.value, line, deleted, updatedAt.toEpochMilliseconds()) }
        val records = rows("account", n.accounts.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.accounts) +
            rows("category", n.categories.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.categories) +
            rows("recurring", n.recurring.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.recurring) +
            rows("person", n.people.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.people) +
            rows("ledger", n.ledger.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.ledger) +
            rows("budget", n.budgets.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.budgets) +
            rows("merchantRule", n.merchantRules.map { Triple(it.id, it.deleted, it.updatedAt) }, lines.merchantRules) +
            RecordRow(SETTINGS, SETTINGS, lines.settings, false, 0)
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                db.records().upsert(records)
                db.entries().upsert(n.entries.map(EntryStore::row))
            }
        }
    }

    /** Everything on the database; the default snapshot when it is empty. */
    suspend fun load(): Snapshot {
        val byKind = db.records().all().groupBy({ it.kind }, { it.line })
        val entries = db.entries().all().map { it.line }
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

    private companion object {
        const val SETTINGS = "settings"
    }
}
