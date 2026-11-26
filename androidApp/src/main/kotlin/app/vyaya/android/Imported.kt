package app.vyaya.android

/** What the 30-day SMS import did (android-capture FLAW-009): payments added, and those waiting in Detected to be checked. */
data class Imported(val added: Int, val toCheck: Int) {
    operator fun plus(o: Imported) = Imported(added + o.added, toCheck + o.toCheck)

    fun message(): String = when {
        added == 0 && toCheck == 0 -> "No new payments in the last 30 days"
        toCheck == 0 -> "$added ${if (added == 1) "payment" else "payments"} added from the last 30 days"
        added == 0 -> "Nothing added; $toCheck to check in Detected"
        else -> "$added added, $toCheck to check in Detected"
    }
}
