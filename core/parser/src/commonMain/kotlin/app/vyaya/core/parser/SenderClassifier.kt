package app.vyaya.core.parser

/** UPI apps whose payment notifications Vyaya reads (Android only; iOS cannot read notifications). */
enum class UpiApp(val packageName: String) {
    // Package names are as published on Google Play; re-check on a real phone (open point in the parser report).
    PHONEPE("com.phonepe.app"),
    GOOGLE_PAY("com.google.android.apps.nbu.paisa.user"),
    PAYTM("net.one97.paytm"),
    BHIM("in.org.npci.upiapp"),
    CRED("com.dreamplug.androidapp"),
    AMAZON_PAY("in.amazon.mShop.android.shopping"),
    NAVI("com.naviapp"),
    MOBIKWIK("com.mobikwik_new"),
    FREECHARGE("com.freecharge.android"),
}

/** Who a message came from. The parser only labels the sender; deciding whom to trust is core/filters' job. */
sealed interface SenderKind {
    /** A TRAI-registered SMS header such as `VM-HDFCBK-S`; [entity] is the 6-character part (`HDFCBK`). */
    data class Header(val entity: String) : SenderKind
    data class App(val app: UpiApp) : SenderKind
    data object PhoneNumber : SenderKind
    data object Unknown : SenderKind
}

object SenderClassifier {

    // Operator/circle prefix, entity, optional TRAI category suffix (Service, Transactional, Promotional, Govt).
    private val PREFIXED_HEADER = Regex("""^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[STPG])?$""", RegexOption.IGNORE_CASE)
    private val BARE_HEADER = Regex("""^([A-Z]{6})$""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""^\+?\d{10,13}$""")
    private val APPS = UpiApp.entries.associateBy { it.packageName }

    fun classify(sender: String): SenderKind {
        val trimmed = sender.trim()
        APPS[trimmed]?.let { return SenderKind.App(it) }
        (PREFIXED_HEADER.matchEntire(trimmed) ?: BARE_HEADER.matchEntire(trimmed))?.let {
            return SenderKind.Header(it.groupValues[1].uppercase())
        }
        if (PHONE.matches(trimmed.filterNot { it == ' ' || it == '-' })) return SenderKind.PhoneNumber
        return SenderKind.Unknown
    }
}
