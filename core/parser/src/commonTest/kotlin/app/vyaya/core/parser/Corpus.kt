package app.vyaya.core.parser

import app.vyaya.core.model.Money

/**
 * Golden corpus: literal messages with their exact expected parse.
 * SYNTHETIC: modelled on common Indian bank SMS / UPI notifications, no real user data.
 * Real masked samples from the user are appended here later.
 */
data class GoldenCase(
    val name: String,
    val source: Source,
    val sender: String,
    val text: String,
    val expected: Expect,
)

sealed interface Expect {
    data class Txn(
        val amount: Money,
        val direction: Direction,
        val counterparty: String?,
        val upiRef: String? = null,
        val accountMask: String? = null,
        val ruleId: String = "generic",
    ) : Expect

    /** Must not become a transaction (Unrecognized or NotTransaction). */
    data object NoTxn : Expect
}

private fun rs(rupees: Long, paise: Long = 0) = Money(rupees * 100 + paise)
private val SMS = Source.SMS
private val NOTIF = Source.NOTIFICATION

val GOLDEN: List<GoldenCase> = listOf(
    // --- bank SMS ---------------------------------------------------------------------------------
    GoldenCase("hdfc upi debit", SMS, "VM-HDFCBK-S",
        "Sent Rs.120.00 From HDFC Bank A/C *1234 To SWIGGY On 26/10/26 Ref 429112345678 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808",
        Expect.Txn(rs(120), Direction.DEBIT, "SWIGGY", "429112345678", "1234")),
    GoldenCase("hdfc upi credit", SMS, "VM-HDFCBK-S",
        "Credit Alert! Rs.500.00 credited to HDFC Bank A/c XX1234 on 26-10-26 from VPA rahul.s@okaxis (UPI 429112345679)",
        Expect.Txn(rs(500), Direction.CREDIT, "rahul.s@okaxis", "429112345679", "1234")),
    GoldenCase("sbi upi debit, no currency prefix", SMS, "JD-SBIUPI-S",
        "Dear UPI user A/C X5678 debited by 120.5 on date 26Oct26 trf to ZOMATO Refno 429112345680. If not u? call 1800111109. -SBI",
        Expect.Txn(rs(120, 50), Direction.DEBIT, "ZOMATO", "429112345680", "5678", "sbi-upi-debit")),
    GoldenCase("sbi upi credit", SMS, "JD-SBIUPI-S",
        "Dear SBI UPI User, ur A/cX5678 credited by Rs500 on 26Oct26 by (Ref no 429112345681)",
        Expect.Txn(rs(500), Direction.CREDIT, null, "429112345681", "5678", "sbi-upi-credit")),
    GoldenCase("icici debit", SMS, "AX-ICICIT-S",
        "ICICI Bank Acct XX123 debited for Rs 1,250.00 on 26-Oct-26; BigBasket credited. UPI:429112345682. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.",
        Expect.Txn(rs(1250), Direction.DEBIT, "BigBasket", "429112345682", "123", "icici-debit")),
    GoldenCase("axis card spend", SMS, "AD-AXISBK-S",
        "Spent INR 2,499 Axis Bank Card no. XX9876 26-10-26 10:00:00 IST AMAZON Avl Limit: INR 45,000",
        Expect.Txn(rs(2499), Direction.DEBIT, "AMAZON", null, "9876", "card-spend")),
    GoldenCase("kotak upi debit with balance", SMS, "BZ-KOTAKB-S",
        "Rs.80.00 debited from Kotak Bank a/c XX4321 to chaiwala@ybl on 26-10-26. UPI Ref 429112345683. Avl bal Rs.10,000.00. Not you? https://kotak.com/fraud",
        Expect.Txn(rs(80), Direction.DEBIT, "chaiwala@ybl", "429112345683", "4321")),
    GoldenCase("atm withdrawal", SMS, "VM-SBIINB-S",
        "Rs.2,000.00 withdrawn at ATM from A/c XX5678 on 26-10-26. Avl Bal: Rs.5,000.00",
        Expect.Txn(rs(2000), Direction.DEBIT, "ATM", null, "5678")),
    GoldenCase("salary credit", SMS, "VM-HDFCBK-S",
        "INR 85,000 credited to A/c no. XX1234 on 26-10-26 by NEFT-ACME CORP SALARY. Avl Bal INR 1,00,000.00",
        Expect.Txn(rs(85000), Direction.CREDIT, "NEFT-ACME CORP SALARY", null, "1234")),
    GoldenCase("rs slash dash", SMS, "VM-HDFCBK-S",
        "Rs.500/- debited from A/c XX1234 to swiggy@icici on 26-10-26.",
        Expect.Txn(rs(500), Direction.DEBIT, "swiggy@icici", null, "1234")),

    // --- UPI app notifications --------------------------------------------------------------------
    GoldenCase("phonepe paid", NOTIF, "com.phonepe.app", "₹120 paid to Swiggy", Expect.Txn(rs(120), Direction.DEBIT, "Swiggy")),
    GoldenCase("phonepe received", NOTIF, "com.phonepe.app", "Received ₹500 from Rahul S", Expect.Txn(rs(500), Direction.CREDIT, "Rahul S")),
    GoldenCase("gpay paid", NOTIF, "com.google.android.apps.nbu.paisa.user", "You paid ₹1,200.50 to Uber India",
        Expect.Txn(rs(1200, 50), Direction.DEBIT, "Uber India")),
    GoldenCase("gpay received", NOTIF, "com.google.android.apps.nbu.paisa.user", "Priya K paid you ₹300",
        Expect.Txn(rs(300), Direction.CREDIT, "Priya K")),
    GoldenCase("paytm paid", NOTIF, "net.one97.paytm", "Paid Rs.45 to Meena Stores from Paytm UPI", Expect.Txn(rs(45), Direction.DEBIT, "Meena Stores")),
    GoldenCase("bhim paid", NOTIF, "in.org.npci.upiapp", "Payment of ₹99 to irctc@sbi is successful",
        Expect.Txn(rs(99), Direction.DEBIT, "irctc@sbi", ruleId = "bhim-payment")),
    GoldenCase("cred paid", NOTIF, "com.dreamplug.androidapp", "₹650 paid to Airtel via CRED UPI", Expect.Txn(rs(650), Direction.DEBIT, "Airtel")),
    GoldenCase("amazon pay paid", NOTIF, "in.amazon.mShop.android.shopping", "₹349 paid to Apollo Pharmacy using Amazon Pay UPI",
        Expect.Txn(rs(349), Direction.DEBIT, "Apollo Pharmacy")),
    GoldenCase("navi sent", NOTIF, "com.naviapp", "Payment successful! ₹1,000 sent to Dr Arjun Rao", Expect.Txn(rs(1000), Direction.DEBIT, "Dr Arjun Rao")),
    GoldenCase("mobikwik paid", NOTIF, "com.mobikwik_new", "₹75 paid successfully to Reliance Fresh", Expect.Txn(rs(75), Direction.DEBIT, "Reliance Fresh")),
    GoldenCase("freecharge paid", NOTIF, "com.freecharge.android", "Paid ₹210 to IRCTC via Freecharge UPI", Expect.Txn(rs(210), Direction.DEBIT, "IRCTC")),

    // --- normalisation through the whole pipeline -------------------------------------------------
    GoldenCase("nbsp after rupee sign", NOTIF, "com.phonepe.app", "₹ 120 paid to Swiggy", Expect.Txn(rs(120), Direction.DEBIT, "Swiggy")),
    GoldenCase("devanagari digits", NOTIF, "com.phonepe.app", "₹१२० paid to Swiggy", Expect.Txn(rs(120), Direction.DEBIT, "Swiggy")),
    GoldenCase("line breaks between title and body", NOTIF, "com.google.android.apps.nbu.paisa.user", "Payment sent\nYou paid ₹60\nto Zomato",
        Expect.Txn(rs(60), Direction.DEBIT, "Zomato")),
    GoldenCase("trailing dot after slash dash", SMS, "VM-HDFCBK-S", "Rs.500/-. debited from A/c XX1234.",
        Expect.Txn(rs(500), Direction.DEBIT, null, null, "1234")),

    // --- must not be transactions (at the parser level) -----------------------------------------
    GoldenCase("otp", SMS, "VM-HDFCBK-S", "482913 is your OTP for txn of Rs.500.00 at AMAZON on HDFC Bank card XX1234. Do not share.", Expect.NoTxn),
    GoldenCase("cashback promo", SMS, "VK-PHONPE-P", "Get flat Rs.100 cashback on paying via PhonePe UPI. T&C apply.", Expect.NoTxn),
    GoldenCase("failed payment", NOTIF, "com.phonepe.app", "Payment of ₹500 to Swiggy failed. Money will not be deducted.", Expect.NoTxn),
    GoldenCase("collect request", NOTIF, "com.google.android.apps.nbu.paisa.user", "Rahul S has requested ₹500 from you", Expect.NoTxn),
    GoldenCase("zero amount", SMS, "VM-HDFCBK-S", "Rs.0.00 debited from A/c XX1234 for mandate registration", Expect.NoTxn),
    GoldenCase("no amount", SMS, "VM-HDFCBK-S", "Your A/c XX1234 has been debited. Call us for details.", Expect.NoTxn),
)
