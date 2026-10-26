package app.vyaya.core.parser

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Money

enum class Source { SMS, NOTIFICATION, SCREENSHOT_OCR, SHORTCUT }

data class RawMessage(val source: Source, val sender: String, val text: String, val receivedAt: CapturedAt)

enum class Direction { DEBIT, CREDIT }

data class ParsedTxn(
    val amount: Money,
    val direction: Direction,
    val counterparty: String?,
    val upiRef: String?,
    val accountMask: String?,
    val occurredAt: CapturedAt,
    val sender: SenderKind,
    val ruleId: String,
)

enum class NotTransactionReason { EMPTY, TOO_LONG }

sealed interface ParseResult {
    data class Transaction(val txn: ParsedTxn) : ParseResult
    data class NotTransaction(val reason: NotTransactionReason) : ParseResult
    data object Unrecognized : ParseResult
}
