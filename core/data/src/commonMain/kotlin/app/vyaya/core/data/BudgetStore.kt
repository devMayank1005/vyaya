package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.engine.Budgets
import app.vyaya.core.engine.MonthSummary
import app.vyaya.core.model.Budget
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Budgets (Phase 3): a monthly limit per spending category, fresh each budget month (the user's choice). Records with
 * soft delete (rule 8.2); progress is calculated by [Budgets], never stored (rule 8.5).
 */
class BudgetStore(private val db: VyayaDatabase, private val limits: BackupLimits = BackupLimits()) {

    private val snapshots = SnapshotStore(db)

    /** The budgets in use: one per spending category that still exists. */
    suspend fun budgets(): List<Budget> = snapshots.loadRecords().let { Budgets.live(it.budgets, it.categories) }

    /** Sets [category]'s monthly limit: its budget changes in place, a removed one comes back, or a new one is made. */
    suspend fun set(category: Category, limit: Money, now: Instant): CatalogResult<Budget> {
        val records = snapshots.loadRecords()
        val current = records.categories.firstOrNull { it.id == category.id }
        if (current == null || current.deleted || current.kind != CategoryKind.EXPENSE) return Refused("Budgets are for spending categories")
        val same = records.budgets.filter { it.categoryId == category.id }.maxByOrNull { it.updatedAt }
        if (same == null && records.budgets.size >= limits.maxRecordsPerType) {
            return Refused("Vyaya can keep up to ${limits.maxRecordsPerType} budgets, removed ones included")
        }
        val budget = same?.copy(limit = limit, updatedAt = now, deleted = false) ?: Budget(EntityId.random(), category.id, limit, now, false)
        save(budget)
        return Saved(budget)
    }

    suspend fun remove(budget: Budget, now: Instant) = save(budget.copy(deleted = true, updatedAt = now))

    /** A suggested monthly limit, and how many finished months it averages. */
    data class Suggestion(val limit: Money, val months: Int)

    /**
     * A limit to start from for [categoryId]: the average spent in it over the last finished budget months (up to 3),
     * counting only months since the user's first entry. Read one month at a time.
     */
    suspend fun suggestion(categoryId: EntityId, settings: Settings, today: LocalDate): Suggestion? {
        val earliest = db.entries().earliestMs() ?: return null
        val schedule = settings.monthSchedule
        val first = schedule.monthContaining(Instant.fromEpochMilliseconds(earliest).toLocalDateTime(settings.homeTimeZone).date)
        val entries = EntryStore(db)
        val spent = mutableListOf<Money>()
        var month = schedule.previous(schedule.monthContaining(today))
        while (spent.size < 3 && month.start >= first.start) {
            // A day either side, for entries in a zone other than home; the month function decides (rule 8.3).
            val from = month.start.minus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
            val to = month.endExclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
            val summary = MonthSummary.of(entries.inRange(from, to), schedule, month)
            spent += summary.spentByCategory[categoryId] ?: Money(0)
            month = schedule.previous(month)
        }
        return Budgets.suggest(spent)?.let { Suggestion(it, spent.size) }
    }

    private suspend fun save(budget: Budget) = snapshots.saveRecords(Snapshot.empty().copy(budgets = listOf(budget)))
}
