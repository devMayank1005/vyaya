package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.RulesStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.Recurring
import app.vyaya.core.engine.SafeToSpend
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.monthOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.yearMonth
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Safe to spend for many users (plan.md Part E): the app's path (catch-up on open, the database month view, the active
 * rules and the months they made, SafeToSpend.of) against an independent day-by-day count of the bills still due.
 * Rules paused, deleted, starting later, for income, on days 29–31 in short months; custom month starts; entries in
 * other zones and deleted; several "todays" per user. Reports only. Size: -Psim.engine.users=N
 */
class SafeToSpendSimulationRunTest {

    private val home = TimeZone.of("Asia/Kolkata")
    private val zones = listOf(home, TimeZone.of("America/New_York"), TimeZone.of("Europe/London"))

    @Test
    fun run_safe_to_spend_simulation(): Unit = runBlocking {
        val users = System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100
        val random = Random(29)
        var checked = 0; var same = 0; var differ = 0; var positive = 0; var short = 0; var nothing = 0; var broke = 0; var widgetDiffers = 0
        val notes = mutableListOf<String>()
        val started = System.nanoTime()
        repeat(users) { u ->
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            val t0 = Instant.parse("2026-01-01T00:00:00Z")
            val schedule = MonthSchedule.of(random.nextInt(1, 29))
            SnapshotStore(db).save(Snapshot.empty().copy(settings = Snapshot.empty().settings.copy(monthSchedule = schedule, homeTimeZone = home)))
            val rules = List(random.nextInt(0, 7)) {
                RecurringRule(EntityId.random(), if (random.nextInt(5) == 0) EntryType.INCOME else EntryType.EXPENSE, Money(random.nextLong(100_00, 30_000_00)),
                    random.nextInt(1, 32), null, null, "Bill $it", paused = random.nextInt(8) == 0,
                    startsOn = LocalDate(2026, random.nextInt(1, 12), random.nextInt(1, 29)), t0, deleted = random.nextInt(10) == 0)
            }
            SnapshotStore(db).saveRecords(Snapshot.empty().copy(recurring = rules))
            EntryStore(db).save(List(random.nextInt(0, 300)) {
                val at = t0 + random.nextInt(0, 330).days + random.nextInt(0, 24).hours
                Entry(EntityId.random(), listOf(EntryType.EXPENSE, EntryType.EXPENSE, EntryType.INCOME, EntryType.TRANSFER).random(random),
                    Money(random.nextLong(100, 3_000_000)), CapturedAt(at, zones.random(random)), null, null, null, "x", "", emptyList(),
                    EntrySource.MANUAL, null, emptyList(), null, at, at, random.nextInt(12) == 0)
            })
            // Days in order, as the user lives them: the app opens (catch-up), then shows Home.
            var day = LocalDate(2026, 2, 1)
            repeat(4) {
                day = day.plus(random.nextInt(1, 70), DateTimeUnit.DAY)
                val now = day.atTime(LocalTime(9, 0)).toInstant(home)
                AppOpen(db).run(now)
                val month = schedule.monthContaining(day)
                val settings = SnapshotStore(db).loadRecords().settings
                val view = MonthStore(db).watch(month, settings).first()
                val active = RulesStore(db).active()
                val taken = RulesStore(db).linkedPayments()
                val got = SafeToSpend.of(view.summary, active, RulesStore(db).madeKeys(), day, payments = view.entries, taken = taken)!!
                // The widget (Phase 3) works it out through QuickLookStore: it must say what Home says.
                if (app.vyaya.core.data.QuickLookStore(db).safeToSpend(now) != got) widgetDiffers++

                // Independent: every day from today to the month's end, each live rule due that day and not made; a payment
                // already made for it a few days early (the shared Recurring.paymentFor) takes it off.
                val all = EntryStore(db).all()
                val madeKeys = all.mapNotNull { it.generatedBy }.toSet()
                val live = all.filter { !it.deleted && schedule.monthOf(it.occurredAt) == month }
                val used = taken.toMutableSet()
                var bills = 0L; var expected = 0L
                var d = day
                while (d < month.endExclusive) {
                    val ym = d.yearMonth
                    for (r in active) {
                        if (r.paused || d < r.startsOn || (r.type != EntryType.EXPENSE && r.type != EntryType.INCOME)) continue
                        if (minOf(r.dayOfMonth, ym.numberOfDays) != d.day) continue
                        if (madeKeys.any { it.ruleId == r.id && it.month == YearMonth(d.year, d.month) }) continue
                        val paid = Recurring.paymentFor(r.type, r.amount, d, live, used)
                        if (paid != null) { used += paid.id; continue }
                        if (r.type == EntryType.EXPENSE) bills += r.amount.paise else expected += r.amount.paise
                    }
                    d = d.plus(1, DateTimeUnit.DAY)
                }
                val left = live.filter { it.countsAsIncome }.sumOf { it.amount.paise } - live.filter { it.countsAsSpending }.sumOf { it.amount.paise }
                val spentToday = live.filter { it.countsAsSpending && it.generatedBy == null && it.id !in used && it.occurredAt.localDate == day }.sumOf { it.amount.paise }
                val daysLeft = (month.endExclusive.toEpochDays() - day.toEpochDays()).toInt()
                val room = left + expected - bills
                val perDay = if (room + spentToday <= 0) 0L else (room + spentToday) / daysLeft / 100 * 100
                checked++
                if (got.left.paise == left && got.billsDue.paise == bills && got.expectedIncome.paise == expected && got.daysLeft == daysLeft &&
                    got.perDay.paise == perDay && got.leftToday.paise == perDay - spentToday) same++
                else { differ++; if (notes.size < 5) notes += "user $u $day: got $got want left=${Money(left)} bills=${Money(bills)} expected=${Money(expected)} days=$daysLeft perDay=${Money(perDay)}" }
                when { got.perDay.paise > 0 -> positive++; got.shortBy.paise > 0 -> short++; else -> nothing++ }
                // Spending the daily amount every day left and paying every bill never takes the month below zero.
                if (left + expected + spentToday - bills - got.perDay.paise * daysLeft < 0 && got.perDay.paise > 0) broke++
            }
            db.close()
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        val report = buildString {
            appendLine("Safe-to-spend simulation: $users users (${"%.1f".format(seconds)} s)")
            appendLine("days checked $checked: app = independent count $same, DIFFER $differ")
            appendLine("shown: a daily amount $positive, bills more than what's left $short, nothing left $nothing")
            appendLine("daily amount every day + every bill below zero (must be 0): $broke")
            appendLine("widget != Home (must be 0): $widgetDiffers")
            notes.forEach { appendLine("  $it") }
        }
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("safe-to-spend-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }
}
