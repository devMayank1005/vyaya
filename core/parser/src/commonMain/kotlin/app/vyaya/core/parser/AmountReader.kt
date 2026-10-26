package app.vyaya.core.parser

import app.vyaya.core.model.Money

/**
 * Reads one amount token taken from a message, such as `Rs.500/-`, `INR. 120`, `₹ 1,20,000.50.` or `₹१२०`.
 *
 * It normalises the token, puts the currency prefix and suffix in canonical form, and then hands the
 * amount to the strict [Money.parse].
 * Returns null when there is no valid amount, and also for ₹0, because penny-drop checks, ₹0 mandates and
 * test transactions are never logged (FLAW-012).
 */
object AmountReader {

    // ₹, ₨ (old rupee sign), INR, Rs, optionally followed by "." or ":" (DATA-005).
    private val PREFIX = Regex("""^(?:₹|₨|INR|RS)\s*[.:]?\s*""", RegexOption.IGNORE_CASE)

    // "/-" (DATA-001) and one trailing sentence "." or "," (DATA-006).
    private val SUFFIX = Regex("""(?:\s*/-)?[.,]?$""")

    fun read(token: String): Money? {
        val text = TextNormalizer.normalize(token)
        val prefix = PREFIX.find(text)
        val canonical = if (prefix == null) text else "₹" + text.substring(prefix.range.last + 1)
        val money = Money.parse(canonical.replace(SUFFIX, "")) ?: return null
        return if (money.paise == 0L) null else money
    }
}
