package app.vyaya.core.model

/**
 * core-backup SEC-010: the text as it may be shown and exported. Format characters (bidi overrides and isolates,
 * zero-width spaces, the byte-order mark) and control characters (NUL, BEL…) go; a right-to-left override would make
 * "‮yggiws" look like "swiggy". Line breaks and tabs stay (a note may have several lines; the CSV quotes them). The
 * zero-width joiner and non-joiner stay too: Indian scripts need them. Cleaning twice changes nothing.
 */
fun String.cleanText(): String {
    if (none { it.needsCleaning() }) return this
    val out = StringBuilder(length)
    for (c in this) if (!c.needsCleaning()) out.append(c)
    return out.toString()
}

private const val ZWNJ = '‌'
private const val ZWJ = '‍'

private fun Char.needsCleaning(): Boolean = when (this) {
    ZWNJ, ZWJ, '\n', '\r', '\t' -> false
    else -> category == CharCategory.FORMAT || category == CharCategory.CONTROL
}
