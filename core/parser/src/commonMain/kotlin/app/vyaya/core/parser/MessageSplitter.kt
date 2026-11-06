package app.vyaya.core.parser

import app.vyaya.core.model.CapturedAt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Splits a message that lists several payments into one message per payment, before parsing. The parser and the
 * rest of capture then handle each like any other SMS.
 *
 * Known form (the user's Airtel Payments Bank SMS): a "Txns card 1234" line, then one `yy-mm-dd MERCHANT Rs 77.6`
 * line per payment. Each part is rewritten as a plain card-spend sentence and given the message's time plus its
 * position in milliseconds, so the parts are distinct, and reading the same SMS again gives the same parts (de-dup
 * then recognises them).
 */
object MessageSplitter {

    private val CARD_SUMMARY = Regex("""\bTxns\s+card\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val CARD_LINE = Regex("""^\s*(\d{2}-\d{2}-\d{2})\s+(.+?)\s+Rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * DATA-001 (real-samples report): the line's `yy-mm-dd` day at the message's local time of day, so a ride on 31 Oct
     * reported on 1 Nov counts in October. An impossible date, or one after the message arrived, keeps the arrival time.
     */
    private fun onLineDay(arrived: CapturedAt, yymmdd: String): Instant {
        val (yy, mm, dd) = yymmdd.split('-').map { it.toInt() }
        val day = runCatching { LocalDate(2000 + yy, mm, dd) }.getOrNull() ?: return arrived.instant
        val local = arrived.instant.toLocalDateTime(arrived.timeZone)
        val instant = runCatching { LocalDateTime(day, local.time).toInstant(arrived.timeZone) }.getOrNull() ?: return arrived.instant
        return if (instant > arrived.instant) arrived.instant else instant
    }

    fun split(raw: RawMessage): List<RawMessage> {
        if (raw.text.length > MessageParser.MAX_TEXT_LENGTH) return listOf(raw)
        val card = CARD_SUMMARY.find(raw.text)?.groupValues?.get(1) ?: return listOf(raw)
        val lines = raw.text.lines().mapNotNull { CARD_LINE.matchEntire(it) }
        if (lines.isEmpty()) return listOf(raw)
        return lines.mapIndexed { i, m ->
            val (date, merchant, amount) = m.destructured
            raw.copy(
                text = "Rs.$amount spent on card XX$card at $merchant on $date",
                receivedAt = raw.receivedAt.copy(instant = onLineDay(raw.receivedAt, date) + i.milliseconds),
            )
        }
    }
}
