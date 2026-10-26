package app.vyaya.core.parser

object MessageParser {

    /** Longer texts are rejected unread (PEN-010). Real bank SMS and UPI notifications are far shorter. */
    const val MAX_TEXT_LENGTH = 2_000

    fun parse(raw: RawMessage): ParseResult {
        // Checked before normalising so a flood of huge messages costs almost nothing.
        if (raw.text.length > MAX_TEXT_LENGTH) return ParseResult.NotTransaction(NotTransactionReason.TOO_LONG)
        val text = TextNormalizer.normalize(raw.text)
        if (text.isEmpty()) return ParseResult.NotTransaction(NotTransactionReason.EMPTY)
        return ParseResult.Unrecognized
    }
}
