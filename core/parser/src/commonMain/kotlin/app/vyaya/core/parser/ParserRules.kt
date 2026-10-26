package app.vyaya.core.parser

import app.vyaya.core.model.Money
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One parsing rule as stored in the rules JSON. Patterns are case-insensitive and run on normalised text. */
@Serializable
data class RuleSpec(
    val id: String,
    val pattern: String,
    val direction: Direction,
    val amountGroup: Int,
    val counterpartyGroup: Int? = null,
    val sources: List<Source> = Source.entries,
    val senderPattern: String? = null,
)

@Serializable
private data class RuleFile(val version: Int, val rules: List<RuleSpec>)

data class RuleHit(val amount: Money, val counterparty: String?)

class CompiledRule internal constructor(val spec: RuleSpec) {
    val id: String get() = spec.id
    val direction: Direction get() = spec.direction
    private val regex = Regex(spec.pattern, RegexOption.IGNORE_CASE)
    private val senderRegex = spec.senderPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }

    fun match(text: String, source: Source, sender: String): RuleHit? {
        if (source !in spec.sources) return null
        if (senderRegex != null && !senderRegex.containsMatchIn(sender)) return null
        val match = regex.find(text) ?: return null
        val amount = group(match, spec.amountGroup)?.let(AmountReader::read) ?: return null
        val counterparty = spec.counterpartyGroup?.let { group(match, it) }?.trim()?.takeIf { it.isNotEmpty() }
        return RuleHit(amount, counterparty)
    }

    // A group index beyond the pattern means "not captured", never a crash while parsing a live message.
    private fun group(match: MatchResult, index: Int): String? =
        if (index < match.groups.size) match.groups[index]?.value else null
}

class RuleSet(val version: Int, val rules: List<CompiledRule>)

/**
 * Bank- and app-specific rules, kept as versioned data so formats can be added or updated without code
 * changes. Anything no rule matches falls back to the generic [Extractors].
 */
object ParserRules {

    const val SUPPORTED_VERSION = 1

    private val json = Json { ignoreUnknownKeys = false }

    /** Decodes and compiles a rules file. Bad JSON, an unknown version or an invalid regex fail here, loudly. */
    fun load(text: String): RuleSet {
        val file = json.decodeFromString(RuleFile.serializer(), text)
        require(file.version == SUPPORTED_VERSION) { "Unsupported rules version ${file.version}" }
        require(file.rules.map { it.id }.toSet().size == file.rules.size) { "Duplicate rule ids" }
        file.rules.forEach { require(it.amountGroup >= 1) { "Rule ${it.id}: amountGroup must be >= 1" } }
        return RuleSet(file.version, file.rules.map(::CompiledRule))
    }

    val default: RuleSet by lazy { load(DEFAULT_RULES) }

    // Synthetic formats modelled on common Indian bank/UPI messages. Replace or extend from real samples.
    private const val AMOUNT = """(?:(?:Rs\\.?|INR|₹)\\s*)?\\d[\\d,]*(?:\\.\\d{1,2})?"""
    private const val PREFIXED_AMOUNT = """(?:Rs\\.?|INR|₹)\\s*\\d[\\d,]*(?:\\.\\d{1,2})?"""

    private const val DEFAULT_RULES = """
{
  "version": 1,
  "rules": [
    { "id": "sbi-upi-debit", "sources": ["SMS"], "direction": "DEBIT", "amountGroup": 1, "counterpartyGroup": 2,
      "pattern": "\\bdebited by\\s+($AMOUNT)\\b.*?\\btrf to\\s+(.+?)\\s+Ref\\s*no\\b" },
    { "id": "sbi-upi-credit", "sources": ["SMS"], "direction": "CREDIT", "amountGroup": 1,
      "pattern": "\\bcredited by\\s+($AMOUNT)\\b" },
    { "id": "icici-debit", "sources": ["SMS"], "direction": "DEBIT", "amountGroup": 1, "counterpartyGroup": 2,
      "pattern": "\\bdebited for\\s+($PREFIXED_AMOUNT)\\s+on\\s+[^;]+;\\s*(.+?)\\s+credited\\b" },
    { "id": "card-spend", "sources": ["SMS"], "direction": "DEBIT", "amountGroup": 1, "counterpartyGroup": 2,
      "pattern": "\\bSpent\\s+($PREFIXED_AMOUNT)\\b.*?\\bIST\\s+(.+?)\\s+Avl\\b" },
    { "id": "bhim-payment", "sources": ["NOTIFICATION"], "direction": "DEBIT", "amountGroup": 1, "counterpartyGroup": 2,
      "pattern": "\\bPayment of\\s+($PREFIXED_AMOUNT)\\s+to\\s+(\\S+)\\s+(?:is\\s+)?successful\\b" }
  ]
}
"""
}
