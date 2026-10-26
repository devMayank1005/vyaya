package app.vyaya.core.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class SenderClassifierTest {

    @Test
    fun trai_headers_are_recognised_with_and_without_category_suffix() {
        assertEquals(SenderKind.Header("HDFCBK"), SenderClassifier.classify("VM-HDFCBK"))
        assertEquals(SenderKind.Header("HDFCBK"), SenderClassifier.classify("VM-HDFCBK-S"))
        assertEquals(SenderKind.Header("SBIUPI"), SenderClassifier.classify("jd-sbiupi-t"))
        assertEquals(SenderKind.Header("ICICIT"), SenderClassifier.classify("AX-ICICIT-T"))
    }

    @Test
    fun bare_headers_without_operator_prefix_are_recognised() {
        // Some phones show the header without the 2-letter operator/circle prefix.
        assertEquals(SenderKind.Header("KOTAKB"), SenderClassifier.classify("KOTAKB"))
    }

    @Test
    fun known_upi_app_packages_are_recognised() {
        assertEquals(SenderKind.App(UpiApp.PHONEPE), SenderClassifier.classify("com.phonepe.app"))
        assertEquals(SenderKind.App(UpiApp.GOOGLE_PAY), SenderClassifier.classify("com.google.android.apps.nbu.paisa.user"))
        assertEquals(SenderKind.App(UpiApp.PAYTM), SenderClassifier.classify("net.one97.paytm"))
        assertEquals(SenderKind.App(UpiApp.BHIM), SenderClassifier.classify("in.org.npci.upiapp"))
        assertEquals(SenderKind.App(UpiApp.CRED), SenderClassifier.classify("com.dreamplug.androidapp"))
        assertEquals(SenderKind.App(UpiApp.AMAZON_PAY), SenderClassifier.classify("in.amazon.mShop.android.shopping"))
        assertEquals(SenderKind.App(UpiApp.NAVI), SenderClassifier.classify("com.naviapp"))
        assertEquals(SenderKind.App(UpiApp.MOBIKWIK), SenderClassifier.classify("com.mobikwik_new"))
        assertEquals(SenderKind.App(UpiApp.FREECHARGE), SenderClassifier.classify("com.freecharge.android"))
    }

    @Test
    fun phone_numbers_are_classified_not_rejected() {
        // Rejecting personal numbers is core/filters' job; the parser only labels them.
        assertEquals(SenderKind.PhoneNumber, SenderClassifier.classify("+919876543210"))
        assertEquals(SenderKind.PhoneNumber, SenderClassifier.classify("9876543210"))
        assertEquals(SenderKind.PhoneNumber, SenderClassifier.classify("+91 98765 43210"))
    }

    @Test
    fun anything_else_is_unknown() {
        listOf("", "Rahul", "com.fake.phonepe", "VM-HDFC BK", "12345", "VM-").forEach {
            assertEquals(SenderKind.Unknown, SenderClassifier.classify(it), "sender: '$it'")
        }
    }
}
