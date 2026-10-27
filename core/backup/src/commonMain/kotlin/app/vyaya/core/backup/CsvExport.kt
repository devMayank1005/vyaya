package app.vyaya.core.backup

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

    private val HEADER = listOf("date", "time", "type", "amount", "category", "account", "payee", "note", "tags", "source", "upi_ref", "id")

    private val TYPE = mapOf(EntryType.EXPENSE to "Expense", EntryType.INCOME to "Income", EntryType.TRANSFER to "Transfer", EntryType.SAVED to "Saved")
    private val SOURCE = mapOf(
        EntrySource.MANUAL to "Manual", EntrySource.SMS to "SMS", EntrySource.NOTIFICATION to "Notification",
        EntrySource.SCREENSHOT to "Screenshot", EntrySource.SHORTCUT to "Shortcut", EntrySource.RECURRING to "Recurring",
        EntrySource.SYSTEM to "System",
    )
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    fun export(snapshot: Snapshot): String {
        val categoryNames = (snapshot.categories + SystemCategories.all).associate { it.id to it.name }
        val accountNames = snapshot.accounts.associate { it.id to it.name }
        val out = StringBuilder("﻿")
        out.row(HEADER)
        snapshot.entries
            .filterNot { it.deleted }
            .sortedWith(compareBy({ it.occurredAt.instant }, { it.id.value }))
            .forEach { e ->
                val local = e.occurredAt.instant.toLocalDateTime(e.occurredAt.timeZone)
                val category = if (e.splits.isEmpty()) e.categoryId?.let { categoryNames[it] }.orEmpty()
                else e.splits.joinToString(" + ") { "${categoryNames[it.categoryId].orEmpty()} ${plain(it.amount)}" }
                out.row(
                    listOf(
                        local.date.toString(),
                        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
                        TYPE.getValue(e.type),
                        plain(e.amount),
                        category,
                        e.accountId?.let { accountNames[it] }.orEmpty(),
                        e.payee.orEmpty(),
                        e.note,
                        e.tags.joinToString("; "),
                        SOURCE.getValue(e.source),
                        e.upiRef.orEmpty(),
                        e.id.value,
                    ),
                )
            }
        return out.toString()
    }

    /** RFC 4180 quoting of one already-neutralised value. */
    fun cell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun StringBuilder.row(values: List<String>) {
        append(values.joinToString(",") { cell(neutralise(it)) }).append("\r\n")
    }

    private fun neutralise(value: String): String = if (value.isNotEmpty() && value[0] in FORMULA_START) "'$value" else value

    /** `120.50`: two decimals, no grouping, so spreadsheets read it as a number. */
    private fun plain(m: Money): String = "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"
}
