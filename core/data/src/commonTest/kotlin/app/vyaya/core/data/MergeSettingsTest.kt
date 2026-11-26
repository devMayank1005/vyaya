package app.vyaya.core.data

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * core-backup FLAW-008: a merge onto a new phone (default settings, nothing logged) takes the backup's settings, so the
 * months start on the same day as before; a phone in use keeps its own.
 */
class MergeSettingsTest {

    private val t = Instant.parse("2026-11-26T06:00:00Z")
    private val ny = TimeZone.of("America/New_York")

    /** A backup from a phone whose month starts on the 25th, in New York, with one entry. */
    private suspend fun oldPhoneBackup(): String {
        val old = inMemoryDatabase()
        AppOpen(old).run(t)
        val s = SnapshotStore(old).load()
        val settings = s.settings.copy(homeTimeZone = ny, monthSchedule = s.settings.monthSchedule.withStartDay(25, LocalDate(2026, 10, 1)))
        SnapshotStore(old).save(s.copy(settings = settings))
        EntryStore(old).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(100_00), CapturedAt(t, ny), null, null, null, "Deli", "",
            emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)))
        return BackupCodec.encode(SnapshotStore(old).load(), ExportMeta("1", "android", t + 1.days)).also { old.close() }
    }

    @Test
    fun backup_flaw_008_a_new_phone_takes_the_backups_settings_on_merge() = runTest {
        val backup = oldPhoneBackup()
        val fresh = inMemoryDatabase()
        AppOpen(fresh).run(t + 1.days)
        assertEquals(Settings.default(), SnapshotStore(fresh).loadRecords().settings)
        assertIs<RestoreResult.Restored>(BackupStore(fresh).restore(backup.lineSequence(), RestoreMode.MERGE, t + 2.days))
        val settings = SnapshotStore(fresh).loadRecords().settings
        assertEquals(ny to 25, settings.homeTimeZone to settings.monthSchedule.monthContaining(LocalDate(2026, 11, 26)).startDay)
        fresh.close()
    }

    @Test
    fun backup_flaw_008_a_phone_in_use_keeps_its_own_settings() = runTest {
        val backup = oldPhoneBackup()
        val used = inMemoryDatabase()
        AppOpen(used).run(t + 1.days)
        EntryStore(used).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(50_00), CapturedAt(t, TimeZone.of("Asia/Kolkata")), null, null,
            null, "Chai", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)))
        BackupStore(used).restore(backup.lineSequence(), RestoreMode.MERGE, t + 2.days)
        assertEquals(Settings.default(), SnapshotStore(used).loadRecords().settings)
        assertEquals(2, EntryStore(used).all().size)
        used.close()
    }

    @Test
    fun backlog_flaw_002_payments_captured_before_the_restore_get_the_question_not_silence() = runTest {
        val backup = oldPhoneBackup()
        val phone = inMemoryDatabase()
        AppOpen(phone).run(t + 1.days)
        // Notification access came first: a payment was captured before the restore.
        CaptureStore(phone).process(app.vyaya.core.parser.RawMessage(app.vyaya.core.parser.Source.NOTIFICATION, "com.phonepe.app", "₹120 paid to Swiggy",
            CapturedAt(t + 1.days, TimeZone.of("Asia/Kolkata"))), t + 1.days)
        val restored = assertIs<RestoreResult.Restored>(BackupStore(phone).restore(backup.lineSequence(), RestoreMode.MERGE, t + 2.days))
        assertEquals(Settings.default(), SnapshotStore(phone).loadRecords().settings, "not changed without asking")
        val offered = restored.offeredSettings!!
        assertEquals(ny, offered.homeTimeZone)
        BackupStore(phone).useSettings(offered)
        assertEquals(ny to 25, SnapshotStore(phone).loadRecords().settings.let { it.homeTimeZone to it.monthSchedule.monthContaining(LocalDate(2026, 11, 26)).startDay })
        assertEquals(2, EntryStore(phone).all().size, "nothing else changes")
        phone.close()
    }

    @Test
    fun backlog_flaw_002_no_question_when_this_phone_was_set_up_or_the_settings_are_the_same() = runTest {
        val backup = oldPhoneBackup()
        val used = inMemoryDatabase()
        AppOpen(used).run(t + 1.days)
        MonthStartStore(used).setStartDay(10, LocalDate(2026, 11, 27))
        EntryStore(used).save(listOf(Entry(EntityId.random(), EntryType.EXPENSE, Money(50_00), CapturedAt(t, TimeZone.of("Asia/Kolkata")), null, null,
            null, "Chai", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false)))
        assertEquals(null, assertIs<RestoreResult.Restored>(BackupStore(used).restore(backup.lineSequence(), RestoreMode.MERGE, t + 2.days)).offeredSettings)
        used.close()
    }
}
