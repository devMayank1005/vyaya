package app.vyaya.core.backup

import app.vyaya.core.model.Account
import app.vyaya.core.model.Category
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.SystemCategories
import kotlinx.datetime.toLocalDateTime

/**
 * Spreadsheet export of all entries (export only; it can't be imported back). Same columns on every platform.
 *
 * - UTF-8 with a byte order mark and CRLF line endings, so Excel shows ₹ and Hindi text correctly.
 * - RFC 4180 quoting for commas, quotes and line breaks.
 * - Cells starting with `=`, `+`, `-`, `@`, a tab or a carriage return get a leading `'`, so Excel and
 *   Google Sheets never run a payee or note as a formula (CSV injection).
 * - Each entry's date and time are shown in the zone it was logged in. Deleted entries are left out.
 */
object CsvExport {

    private val HEADER = listOf(
        "date", "time", "type", "amount", "signed_amount", "category", "account", "to_account", "payee", "note", "tags", "source", "upi_ref", "id",
    )

    /** The `signed_amount` column: a number, never neutralised (its minus sign is not a formula). */
    private const val SIGNED = 4

    private val TYPE = mapOf(EntryType.EXPENSE to "Expense", EntryType.INCOME to "Income", EntryType.TRANSFER to "Transfer", EntryType.SAVED to "Saved")
    private val SOURCE = mapOf(
        EntrySource.MANUAL to "Manual", EntrySource.SMS to "SMS", EntrySource.NOTIFICATION to "Notification",
        EntrySource.SCREENSHOT to "Screenshot", EntrySource.SHORTCUT to "Shortcut", EntrySource.RECURRING to "Recurring",
        EntrySource.SYSTEM to "System",
    )
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    /** The whole file for [snapshot]; the database export writes the same bytes page by page ([start], [row]). */
    fun export(snapshot: Snapshot): String {
        val names = Names(snapshot.categories, snapshot.accounts)
        val out = StringBuilder(start())
        snapshot.entries
            .filterNot { it.deleted }
            .sortedWith(ORDER)
            .forEach { out.append(row(it, names)) }
        return out.toString()
    }

    /** The order of the rows: by time (to the millisecond, as the database keeps it), then by id. */
    val ORDER: Comparator<Entry> = compareBy({ it.occurredAt.instant.toEpochMilliseconds() }, { it.id.value })

    /** What a row shows for a category or account id. System categories (Saved, Transfer) are always named. */
    class Names(categories: List<Category>, accounts: List<Account>) {
        internal val categories = (categories + SystemCategories.all).associate { it.id to it.name }
        internal val accounts = accounts.associate { it.id to it.name }
    }

    /** The byte order mark and the header line. */
    fun start(): String = "\uFEFF" + line(HEADER)

    /** One entry's line, ending in CRLF. */
    fun row(e: Entry, names: Names): String {
        val local = e.occurredAt.instant.toLocalDateTime(e.occurredAt.timeZone)
        val category = if (e.splits.isEmpty()) e.categoryId?.let { names.categories[it] }.orEmpty()
        else e.splits.joinToString(" + ") { "${names.categories[it.categoryId].orEmpty()} ${plain(it.amount)}" }
        return line(
            listOf(
                local.date.toString(),
                "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
                TYPE.getValue(e.type),
                plain(e.amount),
                // csv report #5: spending negative, income positive, so SUM is what's left; transfers and money put
                // aside move nothing in or out, as the engine counts them.
                when (e.type) {
                    EntryType.EXPENSE -> "-" + plain(e.amount)
                    EntryType.INCOME -> plain(e.amount)
                    EntryType.TRANSFER, EntryType.SAVED -> ""
                },
                category,
                e.accountId?.let { names.accounts[it] }.orEmpty(),
                e.toAccountId?.let { names.accounts[it] }.orEmpty(), // csv report #7
                e.payee.orEmpty(),
                e.note,
                e.tags.joinToString("; "),
                SOURCE.getValue(e.source),
                e.upiRef.orEmpty(),
                e.id.value,
            ),
        )
    }

    /** RFC 4180 quoting of one already-neutralised value. */
    fun cell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun line(values: List<String>): String =
        values.withIndex().joinToString(",") { (i, v) -> cell(if (i == SIGNED) v else neutralise(v)) } + "\r\n"

    private fun neutralise(value: String): String = if (value.isNotEmpty() && value[0] in FORMULA_START) "'$value" else value

    /** `120.50`: two decimals, no grouping, so spreadsheets read it as a number. */
    private fun plain(m: Money): String = "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"
}
