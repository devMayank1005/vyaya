package app.vyaya.core.model

import kotlin.jvm.JvmInline

/**
 * An amount of Indian rupees stored as whole paise. Never use Float/Double for money (CLAUDE.md rule 8.1).
 *
 * Negative values are allowed for results such as "left this month"; parsed amounts are always >= 0
 * because the direction (debit/credit) is decided by the parser, not by a sign in the text.
 */
@JvmInline
value class Money(val paise: Long) {

    operator fun plus(other: Money): Money {
        val result = paise + other.paise
        // Overflow if both operands have the same sign and the result's sign differs.
        if (((paise xor result) and (other.paise xor result)) < 0) throw ArithmeticException("Money overflow")
        return Money(result)
    }

    operator fun minus(other: Money): Money {
        val result = paise - other.paise
        // Overflow if the operands have different signs and the result's sign differs from the left operand.
        if (((paise xor other.paise) and (paise xor result)) < 0) throw ArithmeticException("Money overflow")
        return Money(result)
    }

    /** Formats with Indian lakh/crore grouping: `₹1,20,000.50`, `₹120`, `-₹500`. */
    fun format(): String {
        val negative = paise < 0
        // Magnitude via ULong so Long.MIN_VALUE does not overflow.
        val magnitude = if (negative) (-(paise + 1)).toULong() + 1u else paise.toULong()
        val rupees = (magnitude / 100u).toString()
        val fraction = (magnitude % 100u).toInt()
        val sign = if (negative) "-" else ""
        val decimals = if (fraction == 0) "" else "." + fraction.toString().padStart(2, '0')
        return "$sign₹${groupIndian(rupees)}$decimals"
    }

    override fun toString(): String = format()

    companion object {
        /** ₹100 crore. Anything larger in a personal SMS/notification is a parse error or a scam. */
        val MAX = Money(1_00_00_00_000L * 100)

        fun ofRupees(rupees: Long, paise: Int = 0): Money {
            require(paise in 0..99) { "paise must be 0..99, was $paise" }
            require(rupees in (Long.MIN_VALUE / 100 + 1)..(Long.MAX_VALUE / 100 - 1)) { "rupees out of range" }
            return Money(rupees * 100 + if (rupees < 0) -paise else paise)
        }

        private val PREFIX = Regex("""^(?:₹|INR|RS\.?)\s*""", RegexOption.IGNORE_CASE)

        // Plain digits, western grouping (1,234,567) or Indian grouping (12,34,567), then up to 2 decimals.
        private val AMOUNT = Regex("""^(\d+|\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.(\d{1,2}))?$""")

        private const val MAX_INTEGER_DIGITS = 12

        /**
         * Parses an amount as it appears in bank SMS, UPI notifications, OCR text or the keypad.
         * Returns null for anything that is not a single, non-negative, sane rupee amount.
         */
        fun parse(text: String): Money? {
            val body = text.trim().replaceFirst(PREFIX, "")
            val match = AMOUNT.matchEntire(body) ?: return null
            val integerDigits = match.groupValues[1].replace(",", "")
            if (integerDigits.length > MAX_INTEGER_DIGITS) return null
            val fraction = match.groupValues[2].padEnd(2, '0')
            val paise = integerDigits.toLong() * 100 + fraction.toLong()
            return if (paise > MAX.paise) null else Money(paise)
        }

        private fun groupIndian(digits: String): String {
            if (digits.length <= 3) return digits
            val lastThree = digits.takeLast(3)
            val rest = digits.dropLast(3)
            val restGrouped = rest.reversed().chunked(2).joinToString(",").reversed()
            return "$restGrouped,$lastThree"
        }
    }
}
