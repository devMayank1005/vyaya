package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.ClockRepair
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.Recurring
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.DefaultCategories
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The app's open routine on the database (AppOpen) against the in-memory engine, open after open, for many users
 * over 18 months with chaos: the clock ahead and back, edits while it was ahead, rules moved, paused and resumed,
 * generated entries edited and deleted. Reports only. Size: -Psim.engine.users=N
 */
class DatabaseOpenSimulationRunTest {

    private val ist = TimeZone.of("Asia/Kolkata")

    @Test
    fun run_database_open_simulation(): Unit = runBlocking {
        val users = (System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100).coerceAtMost(1_000)
        val random = Random(21)
        var opens = 0; var mismatches = 0; var duplicates = 0; var missing = 0; var refused = 0; var clockJumps = 0
        val notes = mutableListOf<String>()
        val started = System.nanoTime()
        repeat(users) { u ->
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                val start = Instant.parse("2025-01-01T04:30:00Z")
                var rules = List(random.nextInt(1, 6)) {
                    RecurringRule(EntityId.random(), if (random.nextInt(5) == 0) EntryType.INCOME else EntryType.EXPENSE, Money(random.nextLong(100_00, 50_000_00)),
                        random.nextInt(1, 32), null, null, "Rule $it", false, LocalDate(2025, 1, 1), start, false)
                }
                SnapshotStore(db).save(Snapshot.empty().copy(categories = DefaultCategories.create(start), recurring = rules))
                var memory = SnapshotStore(db).load()
                var real = start
                repeat(random.nextInt(20, 60)) {
                    real += random.nextInt(1, 20).days + random.nextInt(0, 24).hours
                    if (real > Instant.parse("2026-07-01T00:00:00Z")) return@repeat
                    // Chaos.
                    val ahead = random.nextInt(12) == 0
                    val clock = if (ahead) real + random.nextInt(20, 90).days.also { clockJumps++ } else real
                    when (random.nextInt(8)) {
                        0 -> { // a typed-in entry
                            val e = Entry(EntityId.random(), EntryType.EXPENSE, Money(random.nextLong(10_00, 5_000_00)), CapturedAt(clock, ist), null, null, null,
                                "Shop", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, clock, clock, false)
                            memory = memory.copy(entries = memory.entries + e); SnapshotStore(db).save(memory.copy(entries = listOf(e)))
                        }
                        1 -> memory.entries.filter { it.generatedBy != null && !it.deleted }.randomOrNull(random)?.let { g -> // edit or delete a generated entry
                            val changed = if (random.nextBoolean()) g.copy(amount = Money(g.amount.paise + 100), updatedAt = clock) else g.markDeleted(clock)
                            memory = memory.copy(entries = memory.entries - g + changed); SnapshotStore(db).save(memory.copy(entries = listOf(changed)))
                        }
                        2 -> rules.randomOrNull(random)?.let { r -> // move a rule to another day, or pause / resume it
                            val changed = if (random.nextBoolean()) r.copy(dayOfMonth = random.nextInt(1, 32), updatedAt = clock) else r.copy(paused = !r.paused, updatedAt = clock)
                            rules = rules - r + changed
                            memory = memory.copy(recurring = rules); SnapshotStore(db).save(memory.copy(entries = emptyList()))
                        }
                        else -> {}
                    }
                    // The app opens with this clock: on the database, and in memory.
                    opens++
                    AppOpen(db).run(clock)
                    val repaired = ClockRepair.repair(memory, clock)
                    val today = clock.toLocalDateTime(ist).date
                    memory = repaired.copy(entries = repaired.entries + Recurring.catchUp(repaired.recurring, repaired.entries, today, ist, clock)).normalized()
                    rules = memory.recurring
                    val onDb = SnapshotStore(db).load()
                    if (onDb != memory) { mismatches++; if (notes.size < 5) notes += "user $u open $opens: the database differs from the engine" }
                    // Invariant 4: one entry per rule and calendar month; every due month of an active rule has one.
                    val keys = onDb.entries.mapNotNull { it.generatedBy }
                    duplicates += keys.size - keys.toSet().size
                    onDb.recurring.filter { !it.deleted && !it.paused }.forEach { r ->
                        if (onDb.entries.none { it.generatedBy?.ruleId == r.id && it.generatedBy?.month == today.yearMonth } &&
                            Recurring.dueOn(r, today.yearMonth) <= today) missing++
                    }
                    // A backup made now is accepted (once the clock is right again, the repair made it so).
                    if (!ahead && BackupCodec.write(onDb, ExportMeta("1", "android", clock)) {} is EncodeResult.Failure) refused++
                }
            } finally { db.close() }
        }
        val report = buildString {
            appendLine("Database open simulation: $users users, $opens opens, $clockJumps clock jumps (${"%.1f".format((System.nanoTime() - started) / 1e9)} s)")
            appendLine("database = engine: ${opens - mismatches} same, $mismatches differ")
            appendLine("DUPLICATES=$duplicates MISSING=$missing backups refused=$refused")
            notes.forEach { appendLine("  $it") }
        }
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("engine-db-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }
}
