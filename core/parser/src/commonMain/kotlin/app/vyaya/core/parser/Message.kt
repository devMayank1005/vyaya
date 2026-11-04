package app.vyaya.core.parser

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Money

enum class Source {
    SMS,

    /** A UPI app's own notification; the sender is the app's package name. */
    NOTIFICATION,

    /**
     * The notification the SMS app shows for an SMS (the Play build has no SMS permission). The sender is the
     * notification's title: the sender's address, or a contact's saved name, so see [RawMessage.senderIsContact].
     */
    SMS_APP_NOTIFICATION,
    SCREENSHOT_OCR,
    SHORTCUT,
}

/**
 * [senderIsContact]: for [Source.SMS_APP_NOTIFICATION], the title is a name saved in the user's contacts.
 * [appUnverified]: for [Source.NOTIFICATION], the phone could not confirm the app is the real one (SEC-003).
 */
data class RawMessage(
    val source: Source,
    val sender: String,
    val text: String,
    val receivedAt: CapturedAt,
    val senderIsContact: Boolean = false,
    val appUnverified: Boolean = false,
)

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
