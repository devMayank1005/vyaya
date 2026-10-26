package app.vyaya.core.fixtures

import app.vyaya.core.model.Money
import kotlin.random.Random

/*
 * SYNTHETIC test messages modelled on common Indian bank SMS and UPI app notifications.
 * No real user data. Kept independent of core/parser (its own Channel/Flow enums) so parser tests can
 * depend on it without a dependency cycle.
 */

enum class Channel { SMS, NOTIFICATION }

enum class Flow { DEBIT, CREDIT }

data class Fields(val amount: Money, val party: String, val ref: String, val mask: String)

data class Expected(
    val amount: Money,
    val flow: Flow,
    val counterparty: String?,
    val upiRef: String?,
    val accountMask: String?,
)

class TxnTemplate(
    val id: String,
    val channel: Channel,
    val sender: String,
    private val render: (Fields) -> String,
    private val expect: (Fields) -> Expected,
) {
    fun text(fields: Fields): String = render(fields)
    fun expected(fields: Fields): Expected = expect(fields)
}

enum class NoiseCategory {
    /** Must never become a transaction at the parser level. */
    OTP, PROMO, FAILED, REQUEST,

    /** The parser may read these as transactions; core/filters must drop them. */
    AUTOPAY_REMINDER, FAKE_CREDIT,
}

class NoiseTemplate(
    val id: String,
    val channel: Channel,
    val sender: String,
    val category: NoiseCategory,
    private val render: (Fields) -> String,
) {
    fun text(fields: Fields): String = render(fields)
}

object Synthetic {

    /** Payee/payer names that contain none of the parser's terminator words. */
    val safeParties = listOf(
        "Swiggy", "Zomato", "Rahul Sharma", "BigBasket", "Uber India", "Priya K", "IRCTC", "Reliance Fresh",
        "Apollo Pharmacy", "Airtel", "Meena Stores", "Dr Arjun Rao",
    )

    fun vpa(party: String): String = party.lowercase().filter { it.isLetterOrDigit() } + "@okaxis"

    /** `1200.00` */
    fun plain2(m: Money): String = "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"

    /** `1,200` or `1,200.50` (lakh grouping). */
    fun grouped(m: Money): String = m.format().removePrefix("₹")

    /** SBI style: `120.0`, `120.5` or `120.55`. */
    fun sbi(m: Money): String {
        val paise = m.paise % 100
        val decimals = if (paise % 10 == 0L) "${paise / 10}" else paise.toString().padStart(2, '0')
        return "${m.paise / 100}.$decimals"
    }

    fun fields(random: Random, parties: List<String> = safeParties): Fields = Fields(
        amount = Money(random.nextLong(1_00, 2_00_000_00)),
        party = parties[random.nextInt(parties.size)],
        ref = (1..12).map { random.nextInt(10) }.joinToString("").replaceFirstChar { if (it == '0') '4' else it },
        mask = (1..4).map { random.nextInt(10) }.joinToString(""),
    )

    private fun exp(f: Fields, flow: Flow, party: String?, ref: Boolean, mask: String?) =
        Expected(f.amount, flow, party, if (ref) f.ref else null, mask)

    val transactions: List<TxnTemplate> = listOf(
        // --- bank SMS -------------------------------------------------------------------------------
        TxnTemplate("hdfc-upi-debit", Channel.SMS, "VM-HDFCBK-S",
            { "Sent Rs.${plain2(it.amount)} From HDFC Bank A/C *${it.mask} To ${it.party} On 26/10/26 Ref ${it.ref} Not You? Call 18002586161/SMS BLOCK UPI to 7308080808" },
            { exp(it, Flow.DEBIT, it.party, ref = true, mask = it.mask) }),
        TxnTemplate("hdfc-upi-credit", Channel.SMS, "VM-HDFCBK-S",
            { "Credit Alert! Rs.${plain2(it.amount)} credited to HDFC Bank A/c XX${it.mask} on 26-10-26 from VPA ${vpa(it.party)} (UPI ${it.ref})" },
            { exp(it, Flow.CREDIT, vpa(it.party), ref = true, mask = it.mask) }),
        TxnTemplate("sbi-upi-debit", Channel.SMS, "JD-SBIUPI-S",
            { "Dear UPI user A/C X${it.mask} debited by ${sbi(it.amount)} on date 26Oct26 trf to ${it.party} Refno ${it.ref}. If not u? call 1800111109. -SBI" },
            { exp(it, Flow.DEBIT, it.party, ref = true, mask = it.mask) }),
        TxnTemplate("sbi-upi-credit", Channel.SMS, "JD-SBIUPI-S",
            { "Dear SBI UPI User, ur A/cX${it.mask} credited by Rs${plain2(it.amount)} on 26Oct26 by (Ref no ${it.ref})" },
            { exp(it, Flow.CREDIT, null, ref = true, mask = it.mask) }),
        TxnTemplate("icici-upi-debit", Channel.SMS, "AX-ICICIT-S",
            { "ICICI Bank Acct XX${it.mask.takeLast(3)} debited for Rs ${plain2(it.amount)} on 26-Oct-26; ${it.party} credited. UPI:${it.ref}. Call 18002662 for dispute. SMS BLOCK ${it.mask.takeLast(3)} to 9215676766." },
            { exp(it, Flow.DEBIT, it.party, ref = true, mask = it.mask.takeLast(3)) }),
        TxnTemplate("axis-card-spend", Channel.SMS, "AD-AXISBK-S",
            { "Spent INR ${grouped(it.amount)} Axis Bank Card no. XX${it.mask} 26-10-26 10:00:00 IST ${it.party} Avl Limit: INR 45,000" },
            { exp(it, Flow.DEBIT, it.party, ref = false, mask = it.mask) }),
        TxnTemplate("kotak-upi-debit", Channel.SMS, "BZ-KOTAKB-S",
            { "Rs.${plain2(it.amount)} debited from Kotak Bank a/c XX${it.mask} to ${vpa(it.party)} on 26-10-26. UPI Ref ${it.ref}. Avl bal Rs.10,000.00. Not you? https://kotak.com/fraud" },
            { exp(it, Flow.DEBIT, vpa(it.party), ref = true, mask = it.mask) }),
        TxnTemplate("atm-withdrawal", Channel.SMS, "VM-SBIINB-S",
            { "Rs.${plain2(it.amount)} withdrawn at ATM from A/c XX${it.mask} on 26-10-26. Avl Bal: Rs.5,000.00" },
            { exp(it, Flow.DEBIT, "ATM", ref = false, mask = it.mask) }),
        TxnTemplate("salary-neft-credit", Channel.SMS, "VM-HDFCBK-S",
            { "INR ${grouped(it.amount)} credited to A/c no. XX${it.mask} on 26-10-26 by NEFT-${it.party.uppercase()} SALARY. Avl Bal INR 1,00,000.00" },
            { exp(it, Flow.CREDIT, "NEFT-${it.party.uppercase()} SALARY", ref = false, mask = it.mask) }),

        // --- UPI app notifications --------------------------------------------------------------------
        TxnTemplate("phonepe-paid", Channel.NOTIFICATION, "com.phonepe.app",
            { "₹${grouped(it.amount)} paid to ${it.party}" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("phonepe-received", Channel.NOTIFICATION, "com.phonepe.app",
            { "Received ₹${grouped(it.amount)} from ${it.party}" }, { exp(it, Flow.CREDIT, it.party, false, null) }),
        TxnTemplate("gpay-paid", Channel.NOTIFICATION, "com.google.android.apps.nbu.paisa.user",
            { "You paid ₹${grouped(it.amount)} to ${it.party}" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("gpay-received", Channel.NOTIFICATION, "com.google.android.apps.nbu.paisa.user",
            { "${it.party} paid you ₹${grouped(it.amount)}" }, { exp(it, Flow.CREDIT, it.party, false, null) }),
        TxnTemplate("paytm-paid", Channel.NOTIFICATION, "net.one97.paytm",
            { "Paid Rs.${grouped(it.amount)} to ${it.party} from Paytm UPI" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("bhim-paid", Channel.NOTIFICATION, "in.org.npci.upiapp",
            { "Payment of ₹${grouped(it.amount)} to ${vpa(it.party)} is successful" }, { exp(it, Flow.DEBIT, vpa(it.party), false, null) }),
        TxnTemplate("cred-paid", Channel.NOTIFICATION, "com.dreamplug.androidapp",
            { "₹${grouped(it.amount)} paid to ${it.party} via CRED UPI" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("amazonpay-paid", Channel.NOTIFICATION, "in.amazon.mShop.android.shopping",
            { "₹${grouped(it.amount)} paid to ${it.party} using Amazon Pay UPI" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("navi-sent", Channel.NOTIFICATION, "com.naviapp",
            { "Payment successful! ₹${grouped(it.amount)} sent to ${it.party}" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("mobikwik-paid", Channel.NOTIFICATION, "com.mobikwik_new",
            { "₹${grouped(it.amount)} paid successfully to ${it.party}" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
        TxnTemplate("freecharge-paid", Channel.NOTIFICATION, "com.freecharge.android",
            { "Paid ₹${grouped(it.amount)} to ${it.party} via Freecharge UPI" }, { exp(it, Flow.DEBIT, it.party, false, null) }),
    )

    val noise: List<NoiseTemplate> = listOf(
        NoiseTemplate("otp", Channel.SMS, "VM-HDFCBK-S", NoiseCategory.OTP) {
            "${it.ref.take(6)} is your OTP for txn of Rs.${plain2(it.amount)} at ${it.party} on HDFC Bank card XX${it.mask}. Do not share. Valid 10 mins."
        },
        NoiseTemplate("promo-cashback", Channel.SMS, "VK-PHONPE-P", NoiseCategory.PROMO) {
            "Get flat Rs.${grouped(it.amount)} cashback on paying via PhonePe UPI at ${it.party}. T&C apply."
        },
        NoiseTemplate("failed-payment", Channel.NOTIFICATION, "com.phonepe.app", NoiseCategory.FAILED) {
            "Payment of ₹${grouped(it.amount)} to ${it.party} failed. Money will not be deducted."
        },
        NoiseTemplate("collect-request", Channel.NOTIFICATION, "com.google.android.apps.nbu.paisa.user", NoiseCategory.REQUEST) {
            "${it.party} has requested ₹${grouped(it.amount)} from you"
        },
        NoiseTemplate("autopay-reminder", Channel.SMS, "VM-HDFCBK-S", NoiseCategory.AUTOPAY_REMINDER) {
            "Your autopay of Rs.${plain2(it.amount)} for ${it.party} will be debited from A/c XX${it.mask} on 05-11-26."
        },
        NoiseTemplate("fake-credit-scam", Channel.SMS, "+919812345678", NoiseCategory.FAKE_CREDIT) {
            "Dear customer Rs.25,000 credited to your account. Claim now at http://bit.ly/kyc-update"
        },
    )
}
