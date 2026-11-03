package app.vyaya.core.filters

import app.vyaya.core.parser.HeaderCategory
import app.vyaya.core.parser.ParseResult
import app.vyaya.core.parser.ParsedTxn
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.SenderClassifier
import app.vyaya.core.parser.SenderKind
import app.vyaya.core.parser.Source
import app.vyaya.core.parser.TextNormalizer

/** Why a message never becomes an entry. Shown in the Ignored tab ("Why was this ignored?"). */
enum class IgnoreReason {
    /** A notification from an app that isn't a known UPI app (SEC-001). */
    UNTRUSTED_APP,

    /** An SMS from a personal phone number: banks use registered headers, scammers use numbers (SEC-001). */
    PERSONAL_NUMBER,

    /** Neither a registered header, a phone number nor a known app. */
    UNKNOWN_SENDER,

    /** A promotional (`-P`) SMS header, even from a bank (SEC-006). */
    PROMOTIONAL,
    OTP,

    /** Failed, declined or reversed: no money moved (FLAW-004). */
    FAILED,

    /** Reminders, autopay notices, "will be credited": money that hasn't moved yet (FLAW-004). */
    NOT_YET_HAPPENED,

    /** Someone asking for money (a UPI collect request), not a payment. */
    REQUEST,

    /** The parser found no transaction in it. */
    NOT_A_TRANSACTION,
}

/** Why a message waits in the review inbox instead of becoming an entry by itself. */
enum class ReviewReason {
    /** A well-formed SMS header that isn't on the known-bank list: a new bank, or a lookalike (SEC-007). */
    UNKNOWN_HEADER,

    /** A known bank's header without the operator prefix (`HDFCBK`): international routes can send any such ID (SEC-003). */
    BARE_HEADER,

    /** The SMS app's notification is titled with a saved contact's name, which anyone can make look like a bank (SEC-002). */
    SAVED_CONTACT,
}

sealed interface Verdict {
    /** Becomes an entry automatically. */
    data class Accept(val txn: ParsedTxn) : Verdict

    /** Waits in the review inbox for the user to confirm. */
    data class Review(val txn: ParsedTxn, val reason: ReviewReason) : Verdict

    data class Ignore(val reason: IgnoreReason) : Verdict
}

/**
 * Decides what happens to a message the parser has read (plan.md Part B1: ignore noise and scams). Order matters:
 * who sent it first (anyone can write any text), then what the text says, then whether it is a transaction.
 */
object Filters {

    private val OTP = Regex("""\b(otp|one[ -]?time password|verification code)\b""", RegexOption.IGNORE_CASE)
    private val FAILED = Regex("""\b(failed|declined|unsuccessful|rejected|could not be (processed|completed)|reversed)\b""", RegexOption.IGNORE_CASE)
    private val NOT_YET = Regex(
        """\bwill be (debited|credited|deducted|processed|charged|refunded)\b|\b(is|are) due\b|\bdue (on|date|by)\b|\breminder\b|\bscheduled\b|\b(refund|reversal)\b[^.]*\binitiated\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REQUEST = Regex("""\brequested\b.{0,80}?\bfrom you\b|\b(collect|payment|money) request\b""", RegexOption.IGNORE_CASE)

    fun judge(raw: RawMessage, parsed: ParseResult, senders: TrustedSenders = TrustedSenders.default): Verdict {
        val sender = SenderClassifier.classify(raw.sender)
        var review: ReviewReason? = null
        when (raw.source) {
            Source.NOTIFICATION -> if (sender !is SenderKind.App) return Verdict.Ignore(IgnoreReason.UNTRUSTED_APP)
            else -> when (sender) {
                is SenderKind.PhoneNumber -> return Verdict.Ignore(IgnoreReason.PERSONAL_NUMBER)
                is SenderKind.Header -> {
                    if (sender.category == HeaderCategory.PROMOTIONAL) return Verdict.Ignore(IgnoreReason.PROMOTIONAL)
                    review = when {
                        sender.entity !in senders.headers -> ReviewReason.UNKNOWN_HEADER
                        !sender.prefixed -> ReviewReason.BARE_HEADER
                        // SEC-002: in the SMS app's notification, a saved contact's name can look like any bank.
                        raw.source == Source.SMS_APP_NOTIFICATION && raw.senderIsContact -> ReviewReason.SAVED_CONTACT
                        else -> null
                    }
                }
                else -> return Verdict.Ignore(IgnoreReason.UNKNOWN_SENDER)
            }
        }
        // Invisible formatting characters (zero-width spaces and joiners, direction marks) can't hide a word.
        val text = TextNormalizer.normalize(raw.text.filterNot { it.category == CharCategory.FORMAT })
        when {
            OTP.containsMatchIn(text) -> return Verdict.Ignore(IgnoreReason.OTP)
            FAILED.containsMatchIn(text) -> return Verdict.Ignore(IgnoreReason.FAILED)
            REQUEST.containsMatchIn(text) -> return Verdict.Ignore(IgnoreReason.REQUEST)
            NOT_YET.containsMatchIn(text) -> return Verdict.Ignore(IgnoreReason.NOT_YET_HAPPENED)
        }
        val txn = (parsed as? ParseResult.Transaction)?.txn ?: return Verdict.Ignore(IgnoreReason.NOT_A_TRANSACTION)
        return if (review != null) Verdict.Review(txn, review) else Verdict.Accept(txn)
    }
}

/**
 * SMS header entities of banks and payment apps whose messages may become entries by themselves. Anything else
 * that looks like a header goes to review. TO VERIFY against real SMS: entities differ per bank and change.
 */
class TrustedSenders(val headers: Set<String>) {
    companion object {
        val default = TrustedSenders(
            setOf(
                "HDFCBK", "SBIUPI", "SBIINB", "SBIPSG", "ICICIT", "ICICIB", "AXISBK", "KOTAKB", "PNBSMS", "BOBTXN",
                "CANBNK", "UNIONB", "IDFCFB", "INDUSB", "YESBNK", "PAYTMB", "AUBANK", "FEDBNK", "RBLBNK", "IDBIBK",
            ),
        )
    }
}
