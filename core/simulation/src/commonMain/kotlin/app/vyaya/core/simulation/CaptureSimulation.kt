package app.vyaya.core.simulation

import app.vyaya.core.dedup.Capture
import app.vyaya.core.dedup.MerchantMemory
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.fixtures.Channel
import app.vyaya.core.fixtures.Fields
import app.vyaya.core.fixtures.Flow
import app.vyaya.core.fixtures.Synthetic
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/*
 * Capture simulation (plan.md Part E, invariants 1 and 7): each user's real payments arrive as a bank SMS plus
 * zero to two UPI app notifications, delayed and out of order, mixed with noise and scams; at the end the SMS
 * import reads every SMS again. Ground truth is the list of real payments. Reports only.
 */

class CaptureSimulationResult(
    val users: Int,
    val payments: Int,
    val messages: Int,
    val noise: Int,
    /** Real payments with more than one entry (must be 0). */
    val doubled: Int,
    /** Real payments with no entry: merged into another payment or not accepted. */
    val missed: Int,
    /** Noise or scam messages that became entries (must be 0). */
    val noiseAccepted: Int,
    val review: Int,
    val p95Micros: Long,
    /** Payments taken as transfers between own accounts; none of these users has any (must be 0). */
    val falseTransfers: Int = 0,
    /** Merchant memory: payments to a remembered payee whose type suits its category, and what capture did with them. */
    val remembered: Int = 0,
    val categorised: Int = 0,
    /** Entries put in a category they shouldn't be in (must be 0). */
    val wrongCategory: Int = 0,
) {
    fun render() = buildString {
        appendLine("users=$users payments=$payments messages=$messages noise=$noise")
        appendLine("DOUBLED=$doubled MISSED=$missed NOISE-ACCEPTED=$noiseAccepted review=$review FALSE-TRANSFERS=$falseTransfers")
        appendLine("merchant memory: remembered=$remembered categorised=$categorised WRONG-CATEGORY=$wrongCategory")
        appendLine("capture p95=${p95Micros}µs per message (existing entries grow to the user's total)")
    }
}

object CaptureSimulation {

    private val IST = TimeZone.of("Asia/Kolkata")
    private val START = Instant.parse("2026-10-01T03:30:00Z")
    private val banks = Synthetic.transactions.filter { it.channel == Channel.SMS && it.id != "atm-withdrawal" && it.id != "salary-neft-credit" }
    private val apps = Synthetic.transactions.filter { it.channel == Channel.NOTIFICATION }

    private class Msg(val raw: RawMessage, val payment: Int?) // payment index, or null for noise

    fun run(users: Int, seed: Long = 23): CaptureSimulationResult {
        val random = Random(seed)
        var payments = 0; var messagesTotal = 0; var noiseTotal = 0
        var doubled = 0; var missed = 0; var noiseAccepted = 0; var review = 0; var falseTransfers = 0
        var remembered = 0; var categorised = 0; var wrongCategory = 0
        val spending = Category(EntityId.random(), "Remembered spending", CategoryKind.EXPENSE, START, false)
        val income = Category(EntityId.random(), "Remembered income", CategoryKind.INCOME, START, false)
        val micros = mutableListOf<Long>()
        repeat(users) {
            val count = random.nextInt(20, 120)
            val noiseRate = if (random.nextInt(10) == 0) 0.40 else 0.15
            val msgs = mutableListOf<Msg>()
            var clock = START
            // Merchant memory: a few payees remembered, each as spending or as income; a forgotten one does nothing.
            val parties = Synthetic.safeParties.shuffled(random).take(random.nextInt(0, 4))
            val rules = parties.map { MerchantRule(EntityId.random(), it, if (random.nextInt(4) == 0) income.id else spending.id, START, deleted = random.nextInt(8) == 0) }
            val memory = MerchantMemory(rules, listOf(spending, income))
            val paymentParty = mutableListOf<Pair<String, Flow>>()
            repeat(count) { p ->
                clock += random.nextInt(1, 240).minutes
                // Chaos: sometimes the same amount again within minutes (two teas).
                val amount = if (p > 0 && random.nextInt(15) == 0) Money(2_000) else Money(random.nextLong(100, 5_000_000))
                val fields = Fields(amount, Synthetic.safeParties.random(random), random.nextLong(100_000_000_000, 999_999_999_999).toString(), "${random.nextInt(1000, 9999)}")
                val flow = if (random.nextInt(6) == 0) Flow.CREDIT else Flow.DEBIT
                paymentParty += fields.party to flow
                val bank = banks.filter { it.expected(fields).flow == flow }.random(random)
                // The bank SMS may arrive minutes late; apps notify within seconds.
                msgs += Msg(RawMessage(Source.SMS, bank.sender, bank.text(fields), CapturedAt(clock + random.nextInt(0, 360).seconds, IST)), p)
                val appTemplates = apps.filter { it.expected(fields).flow == flow }.shuffled(random).take(random.nextInt(0, 3))
                appTemplates.forEach { t -> msgs += Msg(RawMessage(Source.NOTIFICATION, t.sender, t.text(fields), CapturedAt(clock + random.nextInt(0, 30).seconds, IST)), p) }
                if (random.nextDouble() < noiseRate) {
                    val n = Synthetic.noise.random(random)
                    msgs += Msg(RawMessage(if (n.channel == Channel.SMS) Source.SMS else Source.NOTIFICATION, n.sender, n.text(fields), CapturedAt(clock + random.nextInt(0, 600).seconds, IST)), null)
                }
            }
            // Messages are delivered out of order (a few minutes of shuffle), then the import re-reads every SMS.
            val delivered = msgs.sortedBy { it.raw.receivedAt.instant + random.nextInt(0, 180).seconds } + msgs.filter { it.raw.source == Source.SMS }
            var entries = emptyList<Entry>()
            val entryPayment = mutableMapOf<String, MutableSet<Int?>>() // entry id -> payments whose messages it absorbed
            for (m in delivered) {
                val mark = TimeSource.Monotonic.markNow()
                val outcome = Capture.process(m.raw, entries, m.raw.receivedAt.instant + 1.hours, merchants = memory)
                micros += mark.elapsedNow().inWholeMicroseconds
                when (outcome) {
                    is Outcome.Added -> { entries = entries + outcome.entry; entryPayment.getOrPut(outcome.entry.id.value) { mutableSetOf() } += m.payment }
                    is Outcome.Duplicate -> entryPayment.getOrPut(outcome.of.value) { mutableSetOf() } += m.payment
                    is Outcome.Review -> review++
                    is Outcome.Transfer -> { falseTransfers++; entries = entries.map { if (it.id == outcome.entry.id) outcome.entry else it } }
                    is Outcome.Ignored -> {}
                }
            }
            // Each entry should stand for exactly one real payment; each payment should have exactly one entry.
            val perPayment = IntArray(count)
            for ((_, ps) in entryPayment) {
                if (null in ps) noiseAccepted++
                ps.filterNotNull().toSet().let { real -> real.firstOrNull()?.let { perPayment[it]++ }; if (real.size > 1) missed += real.size - 1 }
            }
            // The category each payment's entry should have: its payee's rule, when the rule is live and suits the type.
            for (e in entries) {
                val p = entryPayment[e.id.value]?.filterNotNull()?.firstOrNull() ?: continue
                val (party, flow) = paymentParty[p]
                val rule = rules.firstOrNull { !it.deleted && Capture.payeeKey(it.payee) == Capture.payeeKey(party) }
                val expected = rule?.categoryId?.takeIf { (it == spending.id) == (flow == Flow.DEBIT) }
                if (expected != null) { remembered++; if (e.categoryId == expected) categorised++ }
                if (e.categoryId != null && e.categoryId != expected) wrongCategory++
            }
            doubled += perPayment.count { it > 1 }
            missed += perPayment.count { it == 0 }
            payments += count; messagesTotal += delivered.size; noiseTotal += msgs.count { it.payment == null }
        }
        micros.sort()
        return CaptureSimulationResult(users, payments, messagesTotal, noiseTotal, doubled, missed, noiseAccepted, review, micros.getOrElse(micros.size * 95 / 100) { 0 }, falseTransfers,
            remembered, categorised, wrongCategory)
    }
}
