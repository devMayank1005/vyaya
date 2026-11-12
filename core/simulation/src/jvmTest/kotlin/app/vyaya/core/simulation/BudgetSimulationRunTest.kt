package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.BudgetStore
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.BudgetState
import app.vyaya.core.engine.Budgets
import app.vyaya.core.model.Budget
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.Split
import app.vyaya.core.model.monthOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Budgets for many users (plan.md Part E): progress from the database (the month view the Home screen uses, then
 * Budgets.progress) against an independent sum over every entry, and the suggestion against its definition. Entries in
 * several zones, custom month starts, splits, deleted entries, transfers and income; budgets edited on two phones and
 * merged, and removed ones. Reports only. Size: -Psim.engine.users=N
 */
class BudgetSimulationRunTest {

    private val zones = listOf("Asia/Kolkata", "America/New_York", "Asia/Dubai", "Europe/London", "Pacific/Auckland").map { TimeZone.of(it) }
    private val today = LocalDate(2026, 11, 12)

    @Test
    fun run_budget_simulation(): Unit = runBlocking {
        val users = System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100
        val random = Random(17)
        var checked = 0; var same = 0; var differ = 0; var suggestSame = 0; var suggestDiffer = 0
        val states = BudgetState.entries.associateWith { 0 }.toMutableMap()
        val notes = mutableListOf<String>()
        val started = System.nanoTime()
        repeat(users) { u ->
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            val t0 = Instant.parse("2025-01-01T00:00:00Z")
            val spending = List(random.nextInt(1, 6)) { Category(EntityId.random(), "S$it", CategoryKind.EXPENSE, t0, deleted = random.nextInt(10) == 0) }
            val income = Category(EntityId.random(), "Salary", CategoryKind.INCOME, t0, false)
            val cats = spending + income
            val entries = List(random.nextInt(0, 500)) {
                val at = t0 + random.nextInt(0, 680).days + random.nextInt(0, 24).hours
                val type = listOf(EntryType.EXPENSE, EntryType.EXPENSE, EntryType.EXPENSE, EntryType.INCOME, EntryType.TRANSFER).random(random)
                val amount = Money(random.nextLong(1_000, 2_000_000))
                val splits = if (type == EntryType.EXPENSE && random.nextInt(12) == 0 && spending.size > 1) {
                    val a = Money(amount.paise / 3); listOf(Split(spending[0].id, a), Split(spending[1].id, amount - a))
                } else emptyList()
                Entry(EntityId.random(), type, amount, CapturedAt(at, zones.random(random)), null, null,
                    if (random.nextInt(8) == 0) null else cats.random(random).id, "x", "", emptyList(), EntrySource.MANUAL, null, splits, null, at, at,
                    random.nextInt(12) == 0)
            }
            // Budgets: some categories, sometimes a second copy from another phone (later or earlier), sometimes removed.
            val budgets = spending.filter { random.nextBoolean() }.flatMap { c ->
                val first = Budget(EntityId.random(), c.id, Money(random.nextLong(1_000_00, 40_000_00)), t0, deleted = random.nextInt(8) == 0)
                if (random.nextInt(4) == 0) listOf(first, Budget(EntityId.random(), c.id, Money(random.nextLong(1_000_00, 40_000_00)),
                    t0 + random.nextInt(-5, 5).days, deleted = random.nextInt(8) == 0)) else listOf(first)
            }
            val schedule = MonthSchedule.of(random.nextInt(1, 29))
            val settings = Snapshot.empty().settings.copy(monthSchedule = schedule)
            SnapshotStore(db).save(Snapshot.empty().copy(settings = settings, categories = cats, entries = entries, budgets = budgets))

            // What the database says (the Home path) for a few months, against the independent sum.
            val records = SnapshotStore(db).loadRecords()
            repeat(4) {
                val month = schedule.monthContaining(today.minusDays(random.nextInt(0, 700)))
                val view = MonthStore(db).watch(month, records.settings).first()
                val got = Budgets.progress(records.budgets, view.summary, records.categories).associateBy { it.budget.categoryId }
                val live = budgets.groupBy { it.categoryId }.mapValues { (_, b) -> b.maxWith(compareBy({ it.updatedAt }, { it.id.value })) }
                    .filter { (id, b) -> !b.deleted && spending.any { it.id == id && !it.deleted } }
                for ((id, b) in live) {
                    checked++
                    val spent = entries.filter { !it.deleted && it.type == EntryType.EXPENSE && schedule.monthOf(it.occurredAt) == month }
                        .sumOf { e -> if (e.splits.isEmpty()) (if (e.categoryId == id) e.amount.paise else 0) else e.splits.filter { it.categoryId == id }.sumOf { it.amount.paise } }
                    val p = got[id]
                    val expectedState = when { spent > b.limit.paise -> BudgetState.OVER; spent * 5 >= b.limit.paise * 4 -> BudgetState.NEAR; else -> BudgetState.OK }
                    if (p != null && p.spent.paise == spent && p.limit == b.limit && p.state == expectedState) { same++; states[p.state] = states.getValue(p.state) + 1 }
                    else { differ++; if (notes.size < 5) notes += "user $u $month ${id.value.take(8)}: got ${p?.spent}/${p?.limit} want ${Money(spent)}/${b.limit}" }
                }
                if (got.keys != live.keys) { differ++; if (notes.size < 5) notes += "user $u $month: budgets shown ${got.size}, live ${live.size}" }
            }

            // The suggestion: up to 3 finished months back, none before the first entry's month, average rounded up to ₹100.
            spending.firstOrNull { !it.deleted }?.let { c ->
                val got = BudgetStore(db).suggestion(c.id, records.settings, today)
                val live = entries.filter { !it.deleted }
                val want = if (live.isEmpty()) null else {
                    val first = schedule.monthContaining(live.minOf { it.occurredAt.instant }.toLocalDateTime(records.settings.homeTimeZone).date)
                    val months = generateSequence(schedule.previous(schedule.monthContaining(today))) { schedule.previous(it) }
                        .takeWhile { it.start >= first.start }.take(3).toList()
                    val spent = months.map { m ->
                        Money(entries.filter { !it.deleted && it.type == EntryType.EXPENSE && schedule.monthOf(it.occurredAt) == m }
                            .sumOf { e -> if (e.splits.isEmpty()) (if (e.categoryId == c.id) e.amount.paise else 0) else e.splits.filter { it.categoryId == c.id }.sumOf { it.amount.paise } })
                    }
                    Budgets.suggest(spent)?.let { BudgetStore.Suggestion(it, spent.size) }
                }
                if (got == want) suggestSame++ else { suggestDiffer++; if (notes.size < 5) notes += "user $u suggestion: got $got want $want" }
            }
            db.close()
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        // A heavy user's suggestion: 20,000 entries over 10 years.
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
        val food = Category(EntityId.random(), "Food", CategoryKind.EXPENSE, Instant.parse("2016-01-01T00:00:00Z"), false)
        val start = Instant.parse("2016-11-01T00:00:00Z")
        SnapshotStore(db).save(Snapshot.empty().copy(categories = listOf(food), entries = List(20_000) { i ->
            val at = start + (i % 3_650).days
            Entry(EntityId.random(), EntryType.EXPENSE, Money(10_000), CapturedAt(at, zones[0]), null, null, food.id, "x", "", emptyList(),
                EntrySource.MANUAL, null, emptyList(), null, at, at, false)
        }))
        val t = System.nanoTime()
        val heavy = BudgetStore(db).suggestion(food.id, Snapshot.empty().settings, today)
        val ms = (System.nanoTime() - t) / 1_000_000
        db.close()
        val report = buildString {
            appendLine("Budget simulation: $users users (${"%.1f".format(seconds)} s)")
            appendLine("budget-months checked $checked: database = independent sum $same, DIFFER $differ")
            appendLine("states: ${states.entries.joinToString { "${it.key}=${it.value}" }}")
            appendLine("suggestion = definition: $suggestSame same, DIFFER $suggestDiffer")
            appendLine("heavy user (20,000 entries, 10 years): suggestion ${heavy?.limit} over ${heavy?.months} months in $ms ms")
            notes.forEach { appendLine("  $it") }
        }
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("budget-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }

    private fun LocalDate.minusDays(n: Int): LocalDate = kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() - n)
}
