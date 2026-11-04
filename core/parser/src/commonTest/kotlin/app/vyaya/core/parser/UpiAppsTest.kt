package app.vyaya.core.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The UPI apps whose notifications Vyaya reads: versioned data, every package checked on the Indian Play Store. */
class UpiAppsTest {

    /** Package names confirmed on play.google.com (gl=IN) on 2026-11-04. */
    private val confirmed = listOf(
        "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping", "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android",
        "com.naviapp", "com.popclub.android", "money.super.payments", "money.jupiter", "indwin.c3.shareapp",
        "com.nextbillion.groww", "in.gokiwi.kiwitpap", "com.myairtelapp", "in.jfs.jiofinance",
        "com.sbi.lotusintouch", "com.sbi.upi", "com.hdfcbank.android.now", "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile", "com.axis.mobile", "com.kotak.bank.mobile",
        "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge", "com.bankofbaroda.mconnect", "com.Version1",
        "com.idfcfirstbank.optimus", "com.indusind.indie", "in.irisbyyes.app", "com.fedmobile",
    )

    @Test
    fun every_confirmed_upi_app_is_on_the_list_once() {
        val apps = UpiApps.default
        assertEquals(confirmed.toSet(), apps.apps.map { it.packageName }.toSet())
        assertEquals(apps.apps.size, apps.apps.map { it.packageName }.toSet().size, "no package twice")
        assertEquals(apps.apps.size, apps.apps.map { it.id }.toSet().size, "no id twice")
        assertTrue(apps.apps.all { it.name.isNotBlank() })
    }

    @Test
    fun whatsapp_is_not_on_the_list() { // its notifications are mostly chats: a friend's "₹500 paid" would become an entry
        assertFalse(UpiApps.default.apps.any { it.packageName == "com.whatsapp" })
    }

    @Test
    fun a_bad_list_fails_loudly() {
        assertFailsWith<IllegalArgumentException> { UpiApps.load("""{"version":99,"apps":[]}""") }
        assertFailsWith<IllegalArgumentException> {
            UpiApps.load("""{"version":1,"apps":[{"id":"a","name":"A","packageName":"x.y"},{"id":"b","name":"B","packageName":"x.y"}]}""")
        }
    }
}
