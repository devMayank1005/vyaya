package app.vyaya.core.parser

import app.vyaya.core.model.Money

data class DirectionHit(val direction: Direction, val index: Int)

/**
 * Generic field extractors used when no specific rule matches, and to fill fields a rule leaves out.
 *
 * Every function expects text that has already been through [TextNormalizer].
 */
object Extractors {

    private val I = RegexOption.IGNORE_CASE

    // "paid you" / "sent you" (GPay-style incoming) must win over the debit keyword "paid".
    private val CREDIT_TO_YOU = Regex("""\b(?:paid|sent)\s+you\b""", I)
    private val DEBIT_WORDS = Regex("""\b(?:debited|paid|sent|spent|withdrawn)\b""", I)
    private val CREDIT_WORDS = Regex("""\b(?:credited|received|deposited|refunded)\b""", I)

    /** The first debit/credit keyword in the text. */
    fun direction(text: String): DirectionHit? {
        val toYou = CREDIT_TO_YOU.findAll(text).map { DirectionHit(Direction.CREDIT, it.range.first) }.toList()
        val toYouStarts = toYou.map { it.index }.toSet()
        val debits = DEBIT_WORDS.findAll(text).map { it.range.first }.filter { it !in toYouStarts }
            .map { DirectionHit(Direction.DEBIT, it) }
        val credits = CREDIT_WORDS.findAll(text).map { DirectionHit(Direction.CREDIT, it.range.first) }
        return (toYou + debits + credits).minByOrNull { it.index }
    }

    private val AMOUNT_TOKEN =
        Regex("""(?:₹|₨|\bINR(?=[\s.:\d])|\bRs(?=[\s.:\d]))\s*[.:]?\s*\d[\d,]*(?:\.\d{1,2})?(?:\s*/-)?""", I)
    private val BALANCE_BEFORE = Regex("""(?:bal|balance|limit)\s*[:.\-]?\s*(?:is\s*)?$""", I)

    /**
     * A rupee amount, skipping balances and credit limits. When [near] is given (the index of the
     * direction keyword), the amount closest to it is chosen.
     */
    fun amount(text: String, near: Int? = null): Money? {
        val candidates = AMOUNT_TOKEN.findAll(text)
            .filterNot { BALANCE_BEFORE.containsMatchIn(text.substring(maxOf(0, it.range.first - 30), it.range.first)) }
            .mapNotNull { match -> AmountReader.read(match.value)?.let { it to match.range.first } }
            .toList()
        val chosen = if (near == null) candidates.firstOrNull() else candidates.minByOrNull { kotlin.math.abs(it.second - near) }
        return chosen?.first
    }

    private val UPI_REF = Regex(
        """(?:\bUPI\s*Ref(?:\s*No)?|\bRef\s*No|\bRefno|\bRRN|\bUPI|\bRef)\s*[:.#-]?\s*(\d{12})(?!\d)""", I,
    )

    /** The 12-digit UPI reference / RRN. */
    fun upiRef(text: String): String? = UPI_REF.find(text)?.groupValues?.get(1)

    private val ACCOUNT_MASK =
        Regex("""\b(?:a/c|acct|account|card(?:\s*no\.?)?)\s*(?:no\.?\s*)?[:.]?\s*[x*]+\s*(\d{3,6})(?!\d)""", I)

    /** The visible digits of a masked account or card number (`XX1234` → `1234`). */
    fun accountMask(text: String): String? = ACCOUNT_MASK.find(text)?.groupValues?.get(1)

    // What ends a counterparty name: a following keyword, an opening bracket or semicolon, or sentence punctuation.
    private const val END =
        """(?=\s+(?:on|via|using|ref|refno|upi|from|for|avl|not|is|has|with|and)\b|\s*[(;]|[.,]\s|[.,]?$)"""
    private val DEBIT_PARTY = Regex("""\b(?:to|at)\s+(?:VPA\s+)?(.+?)$END""", I)
    private val CREDIT_PARTY = Regex("""\b(?:from|by)\s+(?:VPA\s+)?(.+?)$END""", I)
    private val PAYER_BEFORE_PAID_YOU = Regex("""^(.+?)\s+(?:paid|sent)\s+you\b""", I)
    private val NOT_A_PARTY = Regex("""^(?:₹|₨|INR\b|Rs\b|Rs\.|a/c|acct|account|your\b|\d)""", I)
    private const val MAX_PARTY_LENGTH = 60

    /** Who the money went to (debit) or came from (credit). */
    fun counterparty(text: String, direction: Direction): String? {
        if (direction == Direction.CREDIT) {
            PAYER_BEFORE_PAID_YOU.find(text)?.let { return clean(it.groupValues[1]) }
        }
        val pattern = if (direction == Direction.DEBIT) DEBIT_PARTY else CREDIT_PARTY
        return pattern.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { !NOT_A_PARTY.containsMatchIn(it) }
            ?.let(::clean)
    }

    private fun clean(name: String): String? =
        name.trim().trimEnd('.', ',', ';', ':').takeIf { it.isNotEmpty() && it.length <= MAX_PARTY_LENGTH }
}
