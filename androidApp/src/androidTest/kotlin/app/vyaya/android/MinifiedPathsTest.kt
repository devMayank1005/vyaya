package app.vyaya.android

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.BudgetStore
import app.vyaya.core.data.CatalogResult
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

/**
 * Launch report FLAW-001: the paths where an R8 mistake would cost data or access, run on a phone in the minified build
 * (`staging`: the release setup, signed with the debug key). `./gradlew :androidApp:connectedPlayStagingAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class MinifiedPathsTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "minified-${System.nanoTime()}.db"
    private val db: VyayaDatabase = VyayaApp.open(context, name)
    private val ist = TimeZone.of("Asia/Kolkata")
    private val now = Clock.System.now()

    @After fun close() { db.close(); context.deleteDatabase(name) }

    private fun capture(text: String): Outcome = runBlocking {
        app.vyaya.core.data.CaptureStore(db).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", text, CapturedAt(now, ist)), now).single()
    }

    @Test
    fun a_payment_notification_is_captured() {
        val added = capture("Paid ₹250 to Swiggy")
        assertTrue("$added", added is Outcome.Added)
        assertEquals(Money(250_00), (added as Outcome.Added).entry.amount)
        assertEquals(1, runBlocking { EntryStore(db).all() }.size)
    }

    @Test
    fun a_backup_round_trips_with_and_without_a_password() = runBlocking {
        capture("Paid ₹250 to Swiggy")
        CatalogStore(db).addCategory("Snacks", CategoryKind.EXPENSE, now)
        val files = BackupFiles(db, context.cacheDir, appVersion = "smoke")
        for (password in listOf(null, "correct horse battery".toCharArray())) {
            val out = ByteArrayOutputStream()
            assertEquals(EncodeResult.Success, files.export(out, now, password?.copyOf()))
            val other = VyayaApp.open(context, "$name-restore")
            try {
                val restored = BackupFiles(other, context.cacheDir, appVersion = "smoke")
                    .restore(ByteArrayInputStream(out.toByteArray()), RestoreMode.REPLACE, now, password = password?.copyOf())
                assertTrue("$restored", restored is RestoreResult.Restored)
                assertEquals(EntryStore(db).all().map { it.id }, EntryStore(other).all().map { it.id })
            } finally {
                other.close(); context.deleteDatabase("$name-restore")
            }
        }
    }

    @Test
    fun the_spreadsheet_is_written() = runBlocking {
        capture("Paid ₹250 to Swiggy")
        val out = ByteArrayOutputStream()
        val written = BackupFiles(db, context.cacheDir, appVersion = "smoke").exportCsv(out)
        assertEquals(1, written?.rows)
        assertTrue(out.toString(Charsets.UTF_8.name()).contains("Swiggy"))
    }

    @Test
    fun the_app_lock_keeps_its_state() {
        val lock = appLockOf(context)
        val was = lock.enabled
        try {
            lock.enabled = true
            assertTrue(appLockOf(context).enabled)
        } finally {
            lock.enabled = was
        }
    }

    @Test
    fun a_budget_alert_is_posted() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val food = (CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, now) as CatalogResult.Saved).item
        BudgetStore(db).set(food, Money(100_00), now)
        val paid = capture("Paid ₹250 to Swiggy") as Outcome.Added
        EntryStore(db).save(listOf(paid.entry.copy(categoryId = food.id, updatedAt = now)))
        BudgetNotifier(context, db, locked = { false }, clock = { now }).check()
        val shown = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("a budget alert", shown.any { it.packageName == context.packageName })
    }
}
