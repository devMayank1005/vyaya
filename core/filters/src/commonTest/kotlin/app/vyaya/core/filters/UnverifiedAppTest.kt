package app.vyaya.core.filters

import app.vyaya.core.model.CapturedAt
import app.vyaya.core.parser.MessageParser
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * SEC-003 (reports/android-capture-2026-11-04.md): a listed app's package name is unique on the phone, but anyone can
 * publish an APK under it. A notification from an app the phone could not verify waits for the user.
 */
class UnverifiedAppTest {

    private val at = CapturedAt(Instant.parse("2026-11-04T10:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private fun judge(unverified: Boolean): Verdict {
        val m = RawMessage(Source.NOTIFICATION, "com.popclub.android", "Received ₹50,000 from Rahul S", at, appUnverified = unverified)
        return Filters.judge(m, MessageParser.parse(m))
    }

    @Test
    fun a_verified_apps_payment_is_accepted() {
        assertIs<Verdict.Accept>(judge(unverified = false))
    }

    @Test
    fun an_unverified_apps_payment_goes_to_review() {
        assertEquals(ReviewReason.UNVERIFIED_APP, assertIs<Verdict.Review>(judge(unverified = true)).reason)
    }
}
