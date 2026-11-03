package app.vyaya.core.simulation

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.Restore
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.engine.Balances
import app.vyaya.core.engine.MonthEnd
import app.vyaya.core.engine.MonthSummary
import app.vyaya.core.engine.Recurring
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.Money
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Settings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toInstant
import kotlinx.datetime.yearMonth
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.time.TimeSource

/*
 * Engine simulation (CLAUDE.md §4, plan.md Part E): synthetic users live 18 months, opening the app on random
 * days. Every open runs recurring catch-up; chaos is injected; every invariant is checked at the end. Reports
 * only.
 */

enum class EnginePersona(val share: Int, val rules: IntRange, val openEveryDays: IntRange) {
    STUDENT(25, 1..3, 1..3),
    SALARIED(35, 3..8, 1..7),
    MONTHLY_OPENER(20, 3..8, 25..45), // "opens the app once a month"
    TRAVELLER(20, 2..6, 1..10), // clock and time-zone trouble
}

class EngineSimulationResult(
    val users: Int,
    val opens: Int,
    val entries: Int,
    val chaos: Map<String, Int>,
    /** Rule + month keys with more than one entry, or duplicate ids (must be 0). */
    val duplicates: Int,
    /** Months that came due but have no entry for their key (must be 0). */
    val missing: Int,
    /** Keys for months whose due date is after the real today: made while the clock was ahead. */
    val early: Int,
    /** Months where Saved - overspent != left, or left != income - spent (must be 0). */
    val savedMismatches: Int,
    /** People whose balance != the sum of their unsettled entries (must be 0). */
    val balanceMismatches: Int,
    /** Users whose final data the app could not back up, with example reasons. */
    val backupRefused: Int,
    val backupRefusedExamples: List<String>,
    val catchUpP95Micros: Long,
    val catchUpMaxAdded: Int,
) {
    fun render() = buildString {
        appendLine("users=$users opens=$opens entries=$entries")
        appendLine("chaos=${chaos.entries.joinToString { "${it.key}:${it.value}" }}")
        appendLine("DUPLICATES=$duplicates MISSING=$missing early(clock ahead)=$early")
        appendLine("saved-MISMATCHES=$savedMismatches balance-MISMATCHES=$balanceMismatches")
        appendLine("backup refused=$backupRefused of $users")
        backupRefusedExamples.forEach { appendLine("[backup refused] $it") }
        appendLine("catch-up p95=${catchUpP95Micros}µs max entries added in one open=$catchUpMaxAdded")
    }
}

object EngineSimulation {

    private val IST = TimeZone.of("Asia/Kolkata")
    private val START = LocalDate(2025, 1, 1)
    private val END = LocalDate(2026, 6, 30)

    private fun at(day: LocalDate, zone: TimeZone = IST) = day.atTime(LocalTime(9, 0)).toInstant(zone)

    fun run(users: Int, seed: Long = 11): EngineSimulationResult {
        val random = Random(seed)
        val chaos = linkedMapOf<String, Int>()
        fun chaos(name: String) { chaos[name] = (chaos[name] ?: 0) + 1 }
        var opens = 0; var totalEntries = 0
        var duplicates = 0; var missing = 0; var early = 0; var savedMismatches = 0; var balanceMismatches = 0
        var backupRefused = 0; val refusedExamples = mutableListOf<String>()
        val catchUpMicros = mutableListOf<Long>(); var maxAdded = 0

        repeat(users) {
            val persona = EnginePersona.entries.let { all ->
                var pick = random.nextInt(all.sumOf { it.share }); all.first { pick -= it.share; pick < 0 }
            }
            val categories = List(6) { Category(random.nextId(), "Category $it", CategoryKind.EXPENSE, at(START), false) }
            val schedule = app.vyaya.core.model.MonthSchedule.of(if (random.nextBoolean()) 1 else random.nextInt(1, 32))
            val settings = Settings("INR", IST, schedule, true)
            var rules = List(random.nextInt(persona.rules.first, persona.rules.last + 1)) {
                val created = START.plus(random.nextInt(0, 200), DateTimeUnit.DAY)
                val day = random.nextInt(1, 32)
                RecurringRule(random.nextId(), if (random.nextInt(5) == 0) EntryType.INCOME else EntryType.EXPENSE,
                    Money(random.nextLong(1_000, 5_000_000)), day, categories.random(random).id, null, "Rule $it",
                    paused = random.nextInt(12) == 0, Recurring.startsOnFor(created, day), at(created), false)
            }
            val people = List(3) { app.vyaya.core.model.Person(random.nextId(), "Friend $it", at(START), false) }
            val ledger = List(random.nextInt(0, 12)) {
                LedgerEntry(random.nextId(), people.random(random).id, LedgerDirection.entries.random(random),
                    Money(random.nextLong(100, 500_000)), START.plus(random.nextInt(0, 500), DateTimeUnit.DAY), "", random.nextBoolean(), at(START), random.nextInt(10) == 0)
            }
            var entries = emptyList<Entry>()
            /** Saves [e] the way the database will: one record per id, a newer version replaces the older. */
            fun save(e: Entry) { entries = entries.filter { it.id != e.id } + e }
            fun open(clockToday: LocalDate) {
                val mark = TimeSource.Monotonic.markNow()
                val added = Recurring.catchUp(rules, entries, clockToday, IST, at(clockToday))
                catchUpMicros += mark.elapsedNow().inWholeMicroseconds
                maxAdded = maxOf(maxAdded, added.size)
                entries = entries + added
                opens++
            }

            var day = START
            while (day <= END) {
                // Chaos: the phone's clock is wrong for this open (ahead up to 3 months, or behind up to 40 days).
                val clock = if (persona == EnginePersona.TRAVELLER && random.nextInt(25) == 0) {
                    chaos("clock wrong"); if (random.nextBoolean()) day.plus(random.nextInt(1, 90), DateTimeUnit.DAY) else day.minus(random.nextInt(1, 40), DateTimeUnit.DAY)
                } else day
                open(clock)
                // Manual entries today, sometimes backdated (forgotten), sometimes in another time zone.
                repeat(random.nextInt(0, 3)) {
                    val backdated = random.nextInt(10) == 0
                    if (backdated) chaos("backdated entry")
                    val date = if (backdated) day.minus(random.nextInt(1, 60), DateTimeUnit.DAY) else day
                    val zone = if (persona == EnginePersona.TRAVELLER && random.nextInt(4) == 0) TimeZone.of("Asia/Dubai") else IST
                    entries = entries + Entry(random.nextId(), if (random.nextInt(6) == 0) EntryType.INCOME else EntryType.EXPENSE,
                        Money(random.nextLong(100, 300_000)), CapturedAt(at(date, zone), zone), null, null, categories.random(random).id,
                        null, "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, at(day), at(day), false)
                }
                fun generated() = entries.filter { it.generatedBy != null && !it.deleted }
                if (generated().isNotEmpty() && random.nextInt(60) == 0) { // the user deletes a generated entry
                    chaos("generated deleted"); save(generated().random(random).markDeleted(at(day)))
                }
                if (generated().isNotEmpty() && random.nextInt(60) == 0) { // paid in the next month
                    chaos("generated moved"); val g = generated().random(random)
                    runCatching { g.copy(occurredAt = CapturedAt(at(g.occurredAt.localDate.plus(random.nextInt(1, 25), DateTimeUnit.DAY)), IST), updatedAt = at(day)) }
                        .getOrNull()?.let(::save)
                }
                if (rules.isNotEmpty() && random.nextInt(120) == 0) { // the user moves a rule's day
                    chaos("rule day changed"); val r = rules.random(random); rules = rules - r + r.copy(dayOfMonth = random.nextInt(1, 32), updatedAt = at(day))
                }
                if (random.nextInt(200) == 0) { // restore mid-month: merge a second phone that ran catch-up on its own
                    chaos("two-phone merge")
                    val rulesByThen = rules.filter { it.updatedAt <= at(day) } // what phone B could know by today
                    val phoneBEntries = Recurring.catchUp(rulesByThen, emptyList(), day, IST, at(day))
                    val b = Snapshot(settings, emptyList(), categories, phoneBEntries, rulesByThen, people, ledger.filter { it.updatedAt <= at(day) }, emptyList(), emptyList())
                    val decoded = BackupCodec.decode(BackupCodec.encode(b, ExportMeta("1.0.0", "ios", at(day))), at(day))
                    if (decoded is DecodeResult.Success) {
                        val current = Snapshot(settings, emptyList(), categories, entries, rules, people, ledger, emptyList(), emptyList())
                        entries = Restore.apply(current, decoded.snapshot, RestoreMode.MERGE).entries
                    } else chaos("phone B backup refused")
                }
                day = day.plus(random.nextInt(persona.openEveryDays.first, persona.openEveryDays.last + 1), DateTimeUnit.DAY)
            }
            open(END) // the real today

            // --- invariants -------------------------------------------------------------------------------------
            if (entries.map { it.id }.toSet().size != entries.size) duplicates++
            val keys = entries.mapNotNull { it.generatedBy }
            duplicates += keys.size - keys.toSet().size

            val keySet = keys.toSet()
            for (r in rules.filter { !it.deleted && !it.paused }) {
                var m = r.startsOn.yearMonth
                while (m <= END.yearMonth) {
                    val due = Recurring.dueOn(r, m)
                    if (due >= r.startsOn && due <= END && app.vyaya.core.model.Generation(r.id, m) !in keySet) missing++
                    m = m.plusMonth()
                }
            }
            early += keys.count { k -> rules.firstOrNull { it.id == k.ruleId }?.let { Recurring.dueOn(it, k.month) > END } ?: false }

            var month = schedule.monthContaining(START)
            while (month.start <= END) {
                val summary = MonthSummary.of(entries, schedule, month)
                val independent = entries.filter { !it.deleted && it.occurredAt.localDate in month }
                val income = independent.filter { it.type == EntryType.INCOME }.fold(Money(0)) { a, e -> a + e.amount }
                val spent = independent.filter { it.type == EntryType.EXPENSE }.fold(Money(0)) { a, e -> a + e.amount }
                if (summary.income != income || summary.spent != spent || summary.left != income - spent) savedMismatches++
                MonthEnd.of(entries, schedule, month, END)?.let { if (it.saved - it.overspent != summary.left) savedMismatches++ }
                month = schedule.next(month)
            }

            val balances = Balances.of(ledger)
            for (p in people) {
                val expected = ledger.filter { it.personId == p.id && !it.settled && !it.deleted }
                    .fold(Money(0)) { a, e -> if (e.direction == LedgerDirection.LENT) a + e.amount else a - e.amount }
                if (balances.of(p.id) != expected) balanceMismatches++
            }

            // The user backs up at the end (real time): the app must be able to.
            val snapshot = Snapshot(settings, emptyList(), categories, entries, rules, people, ledger, emptyList(), emptyList())
            val written = BackupCodec.write(snapshot, ExportMeta("1.0.0", "android", at(END))) {}
            if (written is EncodeResult.Failure) {
                backupRefused++
                if (refusedExamples.size < 3) refusedExamples += "${persona.name}: ${written.reason.take(220)}"
            }
            totalEntries += entries.size
        }
        catchUpMicros.sort()
        return EngineSimulationResult(
            users, opens, totalEntries, chaos, duplicates, missing, early, savedMismatches, balanceMismatches,
            backupRefused, refusedExamples, catchUpMicros.getOrElse((catchUpMicros.size * 95) / 100) { 0 }, maxAdded,
        )
    }
}
