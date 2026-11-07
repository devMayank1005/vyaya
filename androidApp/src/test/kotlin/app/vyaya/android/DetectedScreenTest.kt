package app.vyaya.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.vyaya.ui.DetectedScreen
import app.vyaya.ui.Setup
import app.vyaya.ui.SetupCard
import app.vyaya.core.data.DetectedItem
import app.vyaya.core.data.DetectedKind
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** The Detected screen and the setup card (Compose UI tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetectedScreenTest {

    @get:Rule val compose = createComposeRule()

    private val t = Instant.parse("2026-11-04T10:00:00Z")
    private fun item(id: String, kind: DetectedKind, paise: Long, payee: String?, type: EntryType = EntryType.EXPENSE, reason: String? = null, dup: String? = null) =
        DetectedItem(id, kind, Entry(EntityId.random(), type, Money(paise), CapturedAt(t, TimeZone.of("Asia/Kolkata")), null, null, null,
            payee, "", emptyList(), EntrySource.NOTIFICATION, null, emptyList(), null, t, t, false), reason, dup)

    @Test
    fun nothing_detected_says_so() {
        compose.setContent { DetectedScreen(emptyList(), {}, {}, {}) }
        compose.onNodeWithText("Nothing detected yet").assertIsDisplayed()
    }

    @Test
    fun an_added_payment_shows_its_amount_and_payee_and_can_be_undone() {
        val undone = mutableListOf<String>()
        compose.setContent { DetectedScreen(listOf(item("a", DetectedKind.ADDED, 120_50, "Swiggy")), { undone += it }, {}, {}) }
        compose.onNodeWithText("₹120.50").assertIsDisplayed()
        compose.onNodeWithText("Swiggy").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertEquals(listOf("a"), undone)
    }

    @Test
    fun a_review_item_says_why_and_can_be_added_or_dismissed() {
        val added = mutableListOf<String>(); val dismissed = mutableListOf<String>()
        compose.setContent {
            DetectedScreen(listOf(item("r", DetectedKind.REVIEW, 500_000, null, EntryType.INCOME, reason = "SAVED_CONTACT", dup = "x")),
                {}, { added += it }, { dismissed += it })
        }
        compose.onNodeWithText("+₹5,000").assertIsDisplayed()
        compose.onNodeWithText("From a saved contact, not a bank").assertIsDisplayed()
        compose.onNodeWithText("May be a payment you already have").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Not mine").performClick()
        assertEquals(listOf("r"), added)
        assertEquals(listOf("r"), dismissed)
    }

    @Test
    fun setup_asks_for_notification_access_first() {
        var opened = 0
        compose.setContent { SetupCard(Setup(notificationAccess = false, smsAvailable = false, smsGranted = false), { opened++ }, {}, {}) }
        compose.onNodeWithText("Allow notification access").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun the_full_build_asks_for_sms_then_offers_the_import() {
        var asked = 0; var imported = 0
        val setup = mutableStateOf(Setup(notificationAccess = true, smsAvailable = true, smsGranted = false))
        compose.setContent { SetupCard(setup.value, {}, { asked++ }, { imported++ }) }
        compose.onNodeWithText("Allow SMS").performClick()
        assertEquals(1, asked)
        setup.value = setup.value.copy(smsGranted = true)
        compose.onNodeWithText("Import last 30 days").performClick()
        assertEquals(1, imported)
    }
}
