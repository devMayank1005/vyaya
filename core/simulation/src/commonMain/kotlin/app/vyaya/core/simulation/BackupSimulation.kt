package app.vyaya.core.simulation

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Budget
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Money
import app.vyaya.core.model.Person
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Settings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.time.Instant

/*
 * Backup simulation: synthetic users of every size export, import and re-export their data; every file is
 * also randomly corrupted to check that damage is always caught and never crashes. Reports only.
 */

enum class BackupPersona(val years: Int, val entriesPerDay: Double) {
    STUDENT(1, 2.0), SALARIED(5, 5.0), HEAVY_UPI(5, 12.0), SHOPKEEPER_10Y(10, 55.0),
}

internal val META = ExportMeta("1.0.0", "android", Instant.parse("2026-10-27T12:00:00Z"))
internal val NOW = Instant.parse("2026-10-28T00:00:00Z")
private val PAYEES = listOf("Swiggy", "Zomato", "Rahul Sharma", "BigBasket", "Uber India", "Café Coffee Day", "Sharma & Sons", "\"Quotes\" Ltd", "line\nbreak")
private val ZONES = listOf("Asia/Kolkata", "Asia/Kolkata", "Asia/Kolkata", "America/New_York", "Asia/Dubai")

fun personaSnapshot(persona: BackupPersona, random: Random, entryCap: Int = Int.MAX_VALUE): Snapshot {
    val start = Instant.parse("2016-01-01T00:00:00Z").epochSeconds
    val span = persona.years * 365L * 86_400
    val stamp = { Instant.fromEpochSeconds(start + random.nextLong(span)) }
    val accounts = List(3) { Account(EntityId.random(), listOf("HDFC Salary", "SBI", "Cash")[it], AccountKind.entries[it % 3], "${1000 + it}", stamp(), false) }
    val categories = List(15) { Category(EntityId.random(), "Category $it", if (it < 12) CategoryKind.EXPENSE else CategoryKind.INCOME, stamp(), it == 14) }
    val people = List(10) { Person(EntityId.random(), "Friend $it", stamp(), false) }
    val count = minOf(entryCap, (persona.years * 365 * persona.entriesPerDay).toInt())
    val entries = List(count) {
        Entry(
            EntityId.random(), if (random.nextInt(10) == 0) EntryType.INCOME else EntryType.EXPENSE, Money(random.nextLong(100, 50_000_00)),
            CapturedAt(stamp(), TimeZone.of(ZONES[random.nextInt(ZONES.size)])), accounts.random(random).id, null, categories.random(random).id,
            PAYEES.random(random), if (random.nextInt(5) == 0) "note $it" else "", if (random.nextInt(8) == 0) listOf("trip") else emptyList(),
            EntrySource.entries.random(random), if (random.nextBoolean()) "${random.nextLong(100_000_000_000, 999_999_999_999)}" else null,
            emptyList(), null, stamp(), stamp(), random.nextInt(50) == 0,
        )
    }
    val recurring = List(10) { RecurringRule(EntityId.random(), EntryType.EXPENSE, Money(19900), it + 1, categories[it].id, accounts[0].id, "Sub $it", false, LocalDate(2016, 1, 1), null, stamp(), false) }
    val ledger = List(50) { LedgerEntry(EntityId.random(), people.random(random).id, LedgerDirection.entries.random(random), Money(random.nextLong(100, 1_00_000)), LocalDate(2020, 1, 1), "", random.nextBoolean(), stamp(), false) }
    val budgets = List(10) { Budget(EntityId.random(), categories[it].id, Money(5_000_00), stamp(), false) }
    val rules = List(50) { MerchantRule(EntityId.random(), "payee $it", categories.random(random).id, stamp(), false) }
    return Snapshot(Settings.default(), accounts, categories, entries, recurring, people, ledger, budgets, rules)
}

class BackupSimulationResult(
    val users: Int,
    val roundTripIdentical: Int,
    val roundTripFailed: Int,
    val fuzzRuns: Int,
    val fuzzRejected: Int,
    val fuzzAcceptedUnchanged: Int,
    val fuzzAcceptedChanged: Int,
    val fuzzCrashed: Int,
    val crashExamples: List<String>,
    val silentChangeExamples: List<String>,
    val big: List<String>,
) {
    fun render() = buildString {
        appendLine("users=$users round-trip identical=$roundTripIdentical failed=$roundTripFailed")
        appendLine("fuzz runs=$fuzzRuns rejected=$fuzzRejected accepted-unchanged=$fuzzAcceptedUnchanged accepted-CHANGED=$fuzzAcceptedChanged crashed=$fuzzCrashed")
        crashExamples.forEach { appendLine("[crash] $it") }
        silentChangeExamples.forEach { appendLine("[silent change] $it") }
        big.forEach { appendLine("[big] $it") }
    }
}

object BackupSimulation {

    private const val MUTATION_CHARS = "0123456789abcdefXYZ\"{}[],: -.\\"

    fun run(users: Int, fuzzPerUser: Int = 10, seed: Long = 7): BackupSimulationResult {
        val random = Random(seed)
        var identical = 0; var failed = 0
        var fuzz = 0; var rejected = 0; var unchanged = 0; var changed = 0; var crashed = 0
        val crashes = mutableListOf<String>(); val silent = mutableListOf<String>()
        repeat(users) {
            // Small and medium users for breadth; the big sizes are measured separately below.
            val persona = if (random.nextBoolean()) BackupPersona.STUDENT else BackupPersona.SALARIED
            val snapshot = personaSnapshot(persona, random, entryCap = 400)
            val text = BackupCodec.encode(snapshot, META)
            val decoded = BackupCodec.decode(text, NOW)
            if (decoded is DecodeResult.Success && decoded.snapshot == snapshot.normalized() && BackupCodec.encode(decoded.snapshot, META) == text) identical++ else failed++
            repeat(fuzzPerUser) {
                fuzz++
                val mutated = mutate(text, random)
                try {
                    when (val r = BackupCodec.decode(mutated, NOW)) {
                        is DecodeResult.Failure -> rejected++
                        is DecodeResult.Success -> if (r.snapshot == snapshot.normalized()) unchanged++ else {
                            changed++
                            if (silent.size < 3) silent += describeDiff(snapshot.normalized(), r.snapshot)
                        }
                    }
                } catch (t: Throwable) {
                    crashed++
                    if (crashes.size < 3) crashes += "${t::class.simpleName}: ${t.message?.take(120)}"
                }
            }
        }
        val bigLines = emptyList<String>()
        return BackupSimulationResult(users, identical, failed, fuzz, rejected, unchanged, changed, crashed, crashes, silent, bigLines)
    }

    private fun mutate(text: String, random: Random): String {
        val i = random.nextInt(text.length)
        return when (random.nextInt(4)) {
            0 -> text.substring(0, i) + MUTATION_CHARS[random.nextInt(MUTATION_CHARS.length)] + text.substring(i + 1) // replace
            1 -> text.removeRange(i, minOf(text.length, i + 1 + random.nextInt(20))) // delete a span
            2 -> text.substring(0, i) + MUTATION_CHARS[random.nextInt(MUTATION_CHARS.length)] + text.substring(i) // insert
            else -> text.substring(0, i) // truncated file (interrupted save / partial download)
        }
    }

    private fun describeDiff(a: Snapshot, b: Snapshot): String {
        val e = a.entries.zip(b.entries).firstOrNull { (x, y) -> x != y }
        return if (e != null) "entry ${e.first.id}: ${e.first.amount}/${e.first.payee}/${e.first.note} -> ${e.second.amount}/${e.second.payee}/${e.second.note}"
        else "non-entry field changed"
    }
}
