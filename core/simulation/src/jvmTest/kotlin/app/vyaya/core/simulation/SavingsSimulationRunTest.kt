package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.SavingsStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.MonthEnd
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Saved so far from the database (SavingsStore, a month at a time) against the engine's MonthEnd over every entry, for
 * many users: entries in several zones, deleted ones, transfers, custom month starts. Reports only.
 */
class SavingsSimulationRunTest {

    private val zones = listOf("Asia/Kolkata", "America/New_York", "Asia/Dubai", "Europe/London", "Pacific/Auckland").map { TimeZone.of(it) }

    @Test
    fun run_savings_simulation(): Unit = runBlocking {
        val users = (System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100).coerceAtMost(300)
        val random = Random(5)
        var same = 0; var differ = 0
        val today = LocalDate(2026, 11, 9)
        repeat(users) {
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            val start = Instant.parse("2024-01-01T00:00:00Z")
            val entries = List(random.nextInt(0, 400)) {
                val at = start + random.nextInt(0, 1_050).days + random.nextInt(0, 24).hours
                val type = listOf(EntryType.EXPENSE, EntryType.EXPENSE, EntryType.INCOME, EntryType.TRANSFER).random(random)
                Entry(EntityId.random(), type, Money(random.nextLong(100, 5_000_000)), CapturedAt(at, zones.random(random)), null, null, null,
                    "x", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, at, at, random.nextInt(10) == 0)
            }
            EntryStore(db).save(entries)
            val schedule = MonthSchedule.of(random.nextInt(1, 29))
            val settings = Snapshot.empty().settings.copy(monthSchedule = schedule)
            val got = SavingsStore(db).summary(settings, today)
            // The engine over every entry: each month from the first to the last finished one.
            var saved = Money(0); var over = Money(0)
            if (entries.any { !it.deleted }) {
                var m = schedule.monthContaining(LocalDate(2023, 12, 1))
                while (today >= m.endExclusive) {
                    MonthEnd.of(entries.filter { !it.deleted }, schedule, m, today)?.let { saved += it.saved; over += it.overspent }
                    m = schedule.next(m)
                }
            }
            if (got.saved == saved && got.overspent == over) same++ else differ++
            db.close()
        }
        // Time for a heavy user: 10 years, 20,000 entries.
        val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
        val start = Instant.parse("2016-11-01T00:00:00Z")
        EntryStore(db).save(List(20_000) { i ->
            val at = start + (i % 3_650).days
            Entry(EntityId.random(), if (i % 5 == 0) EntryType.INCOME else EntryType.EXPENSE, Money(10_000), CapturedAt(at, zones[0]), null, null, null,
                "x", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, at, at, false)
        })
        val t = System.nanoTime()
        val s = SavingsStore(db).summary(Snapshot.empty().settings, today)
        val ms = (System.nanoTime() - t) / 1_000_000
        db.close()
        val report = "Savings simulation: $users users; database = engine: $same same, $differ differ\n" +
            "10 years, 20,000 entries: ${s.months} months summed in $ms ms"
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("savings-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }
}
