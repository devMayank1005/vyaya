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

    /** Writes the file to [sink] in pieces; returns how many entries it holds. */
    suspend fun export(sink: (String) -> Unit): Int = db.useReaderConnection { transactor ->
        transactor.deferredTransaction {
            val records = SnapshotStore(db).loadInTransaction(withEntries = false)
            val names = CsvExport.Names(records.categories, records.accounts)
            val entries = EntryStore(db)
            sink(CsvExport.start())
            var afterMs = Long.MIN_VALUE
            var afterId = ""
            var rows = 0
            while (true) {
                val page = db.entries().pageByTime(afterMs, afterId, pageSize)
                page.forEach { sink(CsvExport.row(entries.entryOf(it), names)) }
                rows += page.size
                if (page.size < pageSize) break
                page.last().let { afterMs = it.occurredAtMs; afterId = it.id }
            }
            rows
        }
    }
}
