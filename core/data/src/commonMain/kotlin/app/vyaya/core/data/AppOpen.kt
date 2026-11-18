package app.vyaya.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vyaya.core.backup.ClockRepair
import app.vyaya.core.engine.Recurring
import app.vyaya.core.model.DefaultCategories
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What [AppOpen.run] did. */
data class OpenResult(val seededCategories: Boolean, val repaired: Boolean, val added: Int)

/**
 * What the app does each time it opens, on the database, in one write transaction (FLAW-004, core-data report):
 * 1. a new install gets the default categories (once: deleting them later doesn't bring them back);
 * 2. clock repair, when the clock was ahead for a while ([ClockRepair]);
 * 3. recurring catch-up ([Recurring.catchUp]).
 * Only the records (few), the entries recurring rules made and the entries changed after now are read; never all
 * entries. The result is exactly what the in-memory engine gives on the whole data.
 */
class AppOpen(private val db: VyayaDatabase) {

    private val snapshots = SnapshotStore(db)
    private val entries = EntryStore(db)

    suspend fun run(now: Instant): OpenResult = db.useWriterConnection { transactor ->
        transactor.immediateTransaction {
            var records = snapshots.loadInTransaction(withEntries = false)
            val seeded = records.categories.isEmpty()
            if (seeded) {
                records = records.copy(categories = DefaultCategories.create(now))
                snapshots.saveInTransaction(records)
            }

            // Clock repair looks at change times after now and at generated entries; nothing else can need it.
            val suspects = (entries.changedAfter(now) + entries.generated()).distinctBy { it.id }
            val seen = records.copy(entries = suspects)
            val repaired = ClockRepair.needed(seen, now)
            if (repaired) {
                val fixed = ClockRepair.repair(seen, now)
                entries.delete(suspects.map { it.id } - fixed.entries.map { it.id }.toSet())
                snapshots.saveInTransaction(fixed)
                records = fixed.copy(entries = emptyList())
            }

            val zone = records.settings.homeTimeZone
            val today = now.toLocalDateTime(zone).date
            val generated = entries.generated()
            val due = Recurring.catchUp(records.recurring, generated, today, zone, now)
            // Safe-to-spend DATA-001: a bill already paid a few days early (or late) stands for its month instead of a
            // second entry. Only the entries around the due dates are read (a day more for other time zones).
            val made = if (due.isEmpty()) due else {
                val around = (Recurring.PAID_NEARBY_DAYS + 1).days
                val nearby = entries.inRange(due.minOf { it.occurredAt.instant } - around, due.maxOf { it.occurredAt.instant } + around)
                Recurring.linkPaid(due, nearby, generated, now)
            }
            entries.save(made)
            // Ignored-tab FLAW-006: summaries past keeping leave the phone even if nothing is captured for a while.
            db.ignored().purge((now - IgnoredStore.KEEP).toEpochMilliseconds())
            OpenResult(seeded, repaired, made.count { it.generatedBy != null && !it.deleted })
        }
    }
}
