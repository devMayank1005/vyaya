package app.vyaya.core.parser

/**
 * First step for every incoming text (SMS, notification, OCR), identical on Android and iOS.
 *
 * - Any Unicode whitespace (NBSP, narrow NBSP, thin space, tabs, newlines…) becomes one plain space, and
 *   surrounding space is trimmed (DATA-002).
 * - Any Unicode decimal digit (Devanagari १२०, Bengali, Arabic-Indic, fullwidth…) becomes its ASCII digit
 *   (DATA-011).
 *
 * Uses Kotlin's common Unicode tables (`isWhitespace`, `isDigit`) rather than regex classes, whose meaning
 * differs between the JVM and Kotlin/Native (DATA-007).
 */
object TextNormalizer {

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(if (c.isDigit() && c !in '0'..'9') '0' + c.digitToInt() else c)
        }
        return out.toString()
    }
}
