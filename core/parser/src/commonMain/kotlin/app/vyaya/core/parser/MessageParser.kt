package app.vyaya.core.parser

/**
 * Turns one SMS / notification / OCR text into a transaction, or says why it isn't one.
 *
 * Pipeline: length cap → [TextNormalizer] → first matching rule in [RuleSet] → otherwise the generic
 * [Extractors]. Fields a rule doesn't capture (UPI ref, account mask, and the counterparty when the rule
 * has no counterparty group) are filled by the generic extractors.
 *
 * The parser does not judge trust: OTPs that look like payments, autopay reminders, fake credits and
 * personal-number senders are core/filters' job. [ParsedTxn.sender] carries what filters need.
 */
object MessageParser {

    /** Longer texts are rejected unread (PEN-010). Real bank SMS and UPI notifications are far shorter. */
    const val MAX_TEXT_LENGTH = 2_000

    const val GENERIC_RULE_ID = "generic"

    fun parse(raw: RawMessage, rules: RuleSet = ParserRules.default): ParseResult {
        // Checked before normalising so a flood of huge messages costs almost nothing.
        if (raw.text.length > MAX_TEXT_LENGTH) return ParseResult.NotTransaction(NotTransactionReason.TOO_LONG)
        val text = TextNormalizer.normalize(raw.text)
        if (text.isEmpty()) return ParseResult.NotTransaction(NotTransactionReason.EMPTY)
        val sender = SenderClassifier.classify(raw.sender)

        for (rule in rules.rules) {
            val hit = rule.match(text, raw.source, raw.sender) ?: continue
            val counterparty = if (rule.spec.counterpartyGroup != null) hit.counterparty
            else Extractors.counterparty(text, rule.direction)
            return transaction(raw, text, sender, hit.amount, rule.direction, counterparty, rule.id)
        }

        val direction = Extractors.direction(text) ?: return ParseResult.Unrecognized
        val amount = Extractors.amount(text, near = direction.index) ?: return ParseResult.Unrecognized
        val counterparty = Extractors.counterparty(text, direction.direction)
        return transaction(raw, text, sender, amount, direction.direction, counterparty, GENERIC_RULE_ID)
    }

    private fun transaction(
        raw: RawMessage,
        text: String,
        sender: SenderKind,
        amount: app.vyaya.core.model.Money,
        direction: Direction,
        counterparty: String?,
        ruleId: String,
    ) = ParseResult.Transaction(
        ParsedTxn(
            amount = amount,
            direction = direction,
            counterparty = counterparty,
            upiRef = Extractors.upiRef(text),
            accountMask = Extractors.accountMask(text),
            occurredAt = raw.receivedAt,
            sender = sender,
            ruleId = ruleId,
        ),
    )
}
