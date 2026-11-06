package app.vyaya.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.engine.Recurring
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Recurring rules on the database (rent, subscriptions, salary). Catch-up makes their entries when the app opens
 * ([AppOpen]); here they are added, changed, paused, resumed and deleted (soft delete, rule 8.2).
 */
class RulesStore(private val db: VyayaDatabase) {

    private val snapshots = SnapshotStore(db)
    private val entries = EntryStore(db)

    suspend fun all(): List<RecurringRule> = snapshots.loadRecords().recurring

    suspend fun active(): List<RecurringRule> = all().filter { !it.deleted }

    /** A new rule; it starts on its next due day from today (this month's, if that hasn't passed). */
    suspend fun add(type: EntryType, amount: Money, dayOfMonth: Int, categoryId: EntityId?, note: String, now: Instant, zone: TimeZone): RecurringRule {
        val today = now.toLocalDateTime(zone).date
        val rule = RecurringRule(EntityId.random(), type, amount, dayOfMonth, categoryId, null, note, false, Recurring.startsOnFor(today, dayOfMonth), now, false)
        save(rule)
        return rule
    }

    suspend fun save(rule: RecurringRule) = snapshots.saveRecords(Snapshot.empty().copy(recurring = listOf(rule)))

    suspend fun pause(rule: RecurringRule, now: Instant) = save(rule.copy(paused = true, updatedAt = now))

    suspend fun delete(rule: RecurringRule, now: Instant) = save(rule.copy(deleted = true, updatedAt = now))

    /** The months [rule] was due while paused and has no entry for: the app asks whether to add them (FLAW-003). */
    suspend fun missedWhilePaused(rule: RecurringRule, now: Instant, zone: TimeZone): List<YearMonth> =
        Recurring.missedWhilePaused(rule, entries.generated(), now.toLocalDateTime(zone).date)

    /**
     * Resumes [rule]. With [addMissed], catch-up adds the months it missed when the app next opens; without, they are
     * marked done (deleted entries with their keys), so catch-up leaves them empty. One write transaction.
     */
    suspend fun resume(rule: RecurringRule, addMissed: Boolean, now: Instant, zone: TimeZone) {
        val skipped = if (addMissed) emptyList() else Recurring.skip(rule, missedWhilePaused(rule, now, zone), zone, now)
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                entries.save(skipped)
                db.records().upsert(snapshotRows(rule.copy(paused = false, updatedAt = now)))
            }
        }
    }

    private fun snapshotRows(rule: RecurringRule) = SnapshotStore.recordRows(Snapshot.empty().copy(recurring = listOf(rule)))
}
