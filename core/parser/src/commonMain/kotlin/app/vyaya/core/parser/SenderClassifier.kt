package app.vyaya.core.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A UPI app (or bank app with UPI) whose payment notifications Vyaya reads. Android only: iOS cannot read notifications. */
@Serializable
data class UpiApp(val id: String, val name: String, val packageName: String)

/** The list of trusted apps; a notification from any other package is ignored (core/filters, SEC-001). */
class UpiAppList(val version: Int, val apps: List<UpiApp>) {
    private val byPackage = apps.associateBy { it.packageName }

    fun byPackage(packageName: String): UpiApp? = byPackage[packageName]
}

/**
 * The UPI apps, kept as versioned data like the parser rules, so apps can be added or changed without code changes.
 * Every package name was checked on the Indian Play Store (play.google.com, gl=IN) on 2026-11-04. Left out on purpose:
 * WhatsApp (its notifications are mostly chats, so a friend's "₹500 paid to X" would become an entry), and Fi (not on
 * the store).
 */
object UpiApps {

    const val SUPPORTED_VERSION = 1

    @Serializable
    private class AppFile(val version: Int, val apps: List<UpiApp>)

    private val json = Json { ignoreUnknownKeys = false }

    /** Decodes a list. Bad JSON, an unknown version or a repeated id or package fail here, loudly. */
    fun load(text: String): UpiAppList {
        val file = json.decodeFromString(AppFile.serializer(), text)
        require(file.version == SUPPORTED_VERSION) { "Unsupported UPI app list version ${file.version}" }
        require(file.apps.map { it.id }.toSet().size == file.apps.size) { "Duplicate app ids" }
        require(file.apps.map { it.packageName }.toSet().size == file.apps.size) { "Duplicate app packages" }
        return UpiAppList(file.version, file.apps)
    }

    val default: UpiAppList by lazy { load(DEFAULT_APPS) }

    private const val DEFAULT_APPS = """
{
  "version": 1,
  "apps": [
    { "id": "phonepe", "name": "PhonePe", "packageName": "com.phonepe.app" },
    { "id": "gpay", "name": "Google Pay", "packageName": "com.google.android.apps.nbu.paisa.user" },
    { "id": "paytm", "name": "Paytm", "packageName": "net.one97.paytm" },
    { "id": "bhim", "name": "BHIM", "packageName": "in.org.npci.upiapp" },
    { "id": "amazon-pay", "name": "Amazon Pay", "packageName": "in.amazon.mShop.android.shopping" },
    { "id": "cred", "name": "CRED", "packageName": "com.dreamplug.androidapp" },
    { "id": "mobikwik", "name": "MobiKwik", "packageName": "com.mobikwik_new" },
    { "id": "freecharge", "name": "Freecharge", "packageName": "com.freecharge.android" },
    { "id": "navi", "name": "Navi", "packageName": "com.naviapp" },
    { "id": "pop", "name": "POP", "packageName": "com.popclub.android" },
    { "id": "super-money", "name": "super.money", "packageName": "money.super.payments" },
    { "id": "jupiter", "name": "Jupiter", "packageName": "money.jupiter" },
    { "id": "slice", "name": "slice", "packageName": "indwin.c3.shareapp" },
    { "id": "groww", "name": "Groww", "packageName": "com.nextbillion.groww" },
    { "id": "kiwi", "name": "Kiwi", "packageName": "in.gokiwi.kiwitpap" },
    { "id": "airtel", "name": "Airtel Thanks / Airtel Payments Bank", "packageName": "com.myairtelapp" },
    { "id": "jiofinance", "name": "JioFinance", "packageName": "in.jfs.jiofinance" },
    { "id": "sbi-yono", "name": "SBI YONO", "packageName": "com.sbi.lotusintouch" },
    { "id": "bhim-sbi-pay", "name": "BHIM SBI Pay", "packageName": "com.sbi.upi" },
    { "id": "hdfc", "name": "HDFC Bank", "packageName": "com.hdfcbank.android.now" },
    { "id": "payzapp", "name": "PayZapp", "packageName": "com.hdfcbank.payzapp" },
    { "id": "icici-imobile", "name": "ICICI iMobile", "packageName": "com.csam.icici.bank.imobile" },
    { "id": "axis", "name": "Axis Mobile", "packageName": "com.axis.mobile" },
    { "id": "kotak", "name": "Kotak Bank", "packageName": "com.kotak.bank.mobile" },
    { "id": "kotak-811", "name": "Kotak 811", "packageName": "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge" },
    { "id": "bob-world", "name": "bob World", "packageName": "com.bankofbaroda.mconnect" },
    { "id": "pnb-one", "name": "PNB ONE", "packageName": "com.Version1" },
    { "id": "idfc-first", "name": "IDFC FIRST Bank", "packageName": "com.idfcfirstbank.optimus" },
    { "id": "indusind", "name": "IndusInd Bank", "packageName": "com.indusind.indie" },
    { "id": "yes-iris", "name": "IRIS by YES BANK", "packageName": "in.irisbyyes.app" },
    { "id": "federal-fedmobile", "name": "Federal Bank FedMobile", "packageName": "com.fedmobile" }
  ]
}
"""
}

/** Who a message came from. The parser only labels the sender; deciding whom to trust is core/filters' job. */
/** The TRAI suffix of an SMS header: what kind of message the sender registered it for. */
enum class HeaderCategory { SERVICE, TRANSACTIONAL, PROMOTIONAL, GOVERNMENT }

sealed interface SenderKind {
    /**
     * A TRAI-registered SMS header such as `VM-HDFCBK-S`: [entity] is the 6-character part (`HDFCBK`), and
     * [category] the suffix (`-S`), or null when there is none. Kept so promotional headers can be dropped (SEC-006).
     * [prefixed] is false for a bare entity (`HDFCBK`) with no Indian operator prefix (SEC-003, filters-dedup report).
     */
    data class Header(val entity: String, val category: HeaderCategory?, val prefixed: Boolean = true) : SenderKind
    data class App(val app: UpiApp) : SenderKind
    data object PhoneNumber : SenderKind
    data object Unknown : SenderKind
}

object SenderClassifier {

    // Operator/circle prefix, entity, optional TRAI category suffix (Service, Transactional, Promotional, Govt).
    private val PREFIXED_HEADER = Regex("""^[A-Z]{2}-([A-Z0-9]{3,9})(?:-([STPG]))?$""", RegexOption.IGNORE_CASE)
    private val BARE_HEADER = Regex("""^([A-Z]{6})$""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""^\+?\d{10,13}$""")

    fun classify(sender: String, apps: UpiAppList = UpiApps.default): SenderKind {
        val trimmed = sender.trim()
        apps.byPackage(trimmed)?.let { return SenderKind.App(it) }
        val prefixed = PREFIXED_HEADER.matchEntire(trimmed)
        (prefixed ?: BARE_HEADER.matchEntire(trimmed))?.let {
            val category = when (if (it.groups.size > 2) it.groups[2]?.value?.uppercase() else null) {
                "S" -> HeaderCategory.SERVICE
                "T" -> HeaderCategory.TRANSACTIONAL
                "P" -> HeaderCategory.PROMOTIONAL
                "G" -> HeaderCategory.GOVERNMENT
                else -> null
            }
            return SenderKind.Header(it.groupValues[1].uppercase(), category, prefixed = prefixed != null)
        }
        if (PHONE.matches(trimmed.filterNot { it == ' ' || it == '-' })) return SenderKind.PhoneNumber
        return SenderKind.Unknown
    }
}
