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

    /**
     * Saves the records of [s] that aren't settings or entries (rules, categories, accounts…), leaving the settings row
     * and everything else as it is: for saving one record without passing the whole settings along.
     */
    suspend fun saveRecords(s: Snapshot) {
        val rows = recordRows(s)
        db.useWriterConnection { transactor -> transactor.immediateTransaction { db.records().upsert(rows) } }
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

    private fun rowsOf(n: Snapshot): List<RecordRow> = rowsFor(n)

    /** Everything on the database; the default snapshot when it is empty. */
    suspend fun load(): Snapshot = db.useReaderConnection { transactor -> transactor.deferredTransaction { loadInTransaction() } }

    /** Everything but the entries (few records: settings, categories, accounts, rules…). */
    suspend fun loadRecords(): Snapshot =
        db.useReaderConnection { transactor -> transactor.deferredTransaction { loadInTransaction(withEntries = false) } }

    /** [load] for a caller already inside a transaction; without the entries when not [withEntries]. */
    internal suspend fun loadInTransaction(withEntries: Boolean = true): Snapshot {
        val records = db.records().all()
        val entries = if (withEntries) db.entries().all() else emptyList()
        if (records.isEmpty() && entries.isEmpty()) return Snapshot.empty()
        return readable(records, entries)
    }

    /**
     * What capture needs besides entries, inside its transaction: accounts (to match a message's last digits, catalog
     * report #4), and categories and merchant rules (merchant memory). Read by kind, not the whole snapshot.
     */
    internal suspend fun captureRecordsInTransaction(): Snapshot =
        readable(listOf("account", "category", "merchantRule").flatMap { db.records().ofKind(it) }, emptyList(), settings = false)

    /** People and the ledger, for a caller already inside a transaction (capture asking about a pay-back). */
    internal suspend fun peopleInTransaction(): Snapshot =
        readable(listOf("person", "ledger").flatMap { db.records().ofKind(it) }, emptyList(), settings = false)

    /**
     * Everything, ready to export (DATA-006): when rows were found damaged, references to them are detached (the
     * entries stay, without the category that can't be read), so the rest still makes a backup that restores.
     */
    suspend fun loadForExport(): Snapshot {
        val s = load()
        return if (db.damaged.value.isEmpty()) s else DamageRepair.detach(s)
    }

    /**
     * The snapshot of [records] and [entries]. Rows are written only by this app, from valid records, so one that doesn't
     * read back is damage (core-data DATA-006): it is left out and reported, never fatal. The fast path reads all at
     * once; only when that fails is each row checked on its own.
     */
    private fun readable(records: List<RecordRow>, entries: List<EntryRow>, settings: Boolean = true): Snapshot {
        val byKind = records.groupBy { it.kind }
        val defaultSettings = RecordLines.of(Snapshot.empty()).settings
        fun lines(byKind: Map<String, List<RecordRow>>, entries: List<EntryRow>, settingsLine: String) = SnapshotLines(
            settings = settingsLine,
            accounts = byKind["account"].orEmpty().map { it.line }, categories = byKind["category"].orEmpty().map { it.line },
            entries = entries.map { it.line }, recurring = byKind["recurring"].orEmpty().map { it.line },
            people = byKind["person"].orEmpty().map { it.line }, ledger = byKind["ledger"].orEmpty().map { it.line },
            budgets = byKind["budget"].orEmpty().map { it.line }, merchantRules = byKind["merchantRule"].orEmpty().map { it.line },
        )
        val settingsLine = if (settings) byKind[SETTINGS]?.firstOrNull()?.line ?: defaultSettings else defaultSettings
        RecordLines.snapshotOf(lines(byKind, entries, settingsLine))?.let { return it }

        // Slow path: which rows don't read back on their own.
        val goodSettings = if (RecordLines.snapshotOf(lines(emptyMap(), emptyList(), settingsLine)) != null) settingsLine
        else defaultSettings.also { db.reportDamaged("$SETTINGS:$SETTINGS") }
        val good = byKind.filterKeys { it != SETTINGS }.mapValues { (kind, rows) ->
            rows.filter { row ->
                val one = lines(mapOf(kind to listOf(row)), emptyList(), defaultSettings)
                (RecordLines.snapshotOf(one) != null).also { ok -> if (!ok) db.reportDamaged("$kind:${row.id}") }
            }
        }
        val goodEntries = entries.filter { row ->
            (RecordLines.entryOf(row.line) != null).also { ok -> if (!ok) db.reportDamaged("entry:${row.id}") }
        }
        return RecordLines.snapshotOf(lines(good, goodEntries, goodSettings)) ?: Snapshot.empty()
    }

    internal companion object {
        /** Rows for [s]'s records other than settings and entries. */
        fun recordRows(s: Snapshot): List<RecordRow> = rowsFor(s.normalized()).filter { it.kind != SETTINGS }

        /** Every record of [n] and its settings as rows (each its backup line); entries are saved separately. */
        fun rowsFor(n: Snapshot): List<RecordRow> {
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

        const val SETTINGS = "settings"
    }
}
