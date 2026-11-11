package app.vyaya.core.data

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import app.vyaya.core.backup.CsvExport

/**
 * The spreadsheet export (plan Phase 1), read from the database a page at a time, so any number of entries fits in
 * memory. The bytes are [CsvExport.export]'s for the same data: the formula guard (`=`, `+`, `-`, `@` get a leading `'`)
 * covers payees, notes, and category and account names alike (catalog report #10). One read transaction, so a payment
 * captured meanwhile is either in the file or not, never half.
 */
class CsvStore(private val db: VyayaDatabase, private val pageSize: Int = 500) {

    /** What the file holds: [rows] entries; [skipped] rows that didn't read back (a damaged database) were left out. */
    data class Written(val rows: Int, val skipped: Int)

    /** Writes the file to [sink] in pieces. */
    suspend fun export(sink: (String) -> Unit): Written = db.useReaderConnection { transactor ->
        transactor.deferredTransaction {
            val records = SnapshotStore(db).loadInTransaction(withEntries = false)
            val names = CsvExport.Names(records.categories, records.accounts)
            val entries = EntryStore(db)
            sink(CsvExport.start())
            var afterMs = Long.MIN_VALUE
            var afterId = ""
            var rows = 0
            var skipped = 0
            while (true) {
                val page = db.entries().pageByTime(afterMs, afterId, pageSize)
                // csv report #3: a row that doesn't read back (a damaged database) is left out and counted, not a crash.
                page.forEach { row ->
                    val entry = runCatching { entries.entryOf(row) }.getOrNull()
                    if (entry == null) skipped++ else { sink(CsvExport.row(entry, names)); rows++ }
                }
                if (page.size < pageSize) break
                page.last().let { afterMs = it.occurredAtMs; afterId = it.id }
            }
            Written(rows, skipped)
        }
    }
}
