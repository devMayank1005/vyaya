package app.vyaya.core.simulation

import app.vyaya.core.fixtures.Channel
import app.vyaya.core.fixtures.Expected
import app.vyaya.core.fixtures.Fields
import app.vyaya.core.fixtures.Flow
import app.vyaya.core.fixtures.NoiseCategory
import app.vyaya.core.fixtures.Synthetic
import app.vyaya.core.fixtures.TxnTemplate
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Money
import app.vyaya.core.parser.Direction
import app.vyaya.core.parser.MessageParser
import app.vyaya.core.parser.ParseResult
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/*
 * Simulates thousands of synthetic users (plan.md Part E personas), each producing a month of messages
 * with real-world chaos, and measures what the parser gets right and wrong against ground truth.
 * Reports only; it never fails on quality (the break phase reports, it doesn't fix).
 */

enum class Persona(val share: Double) {
    STUDENT(0.30), SALARIED(0.30), HEAVY_UPI(0.15), SHOPKEEPER(0.05), HINDI(0.10), TRAVELLER(0.05), SCAM_TARGET(0.05)
}

enum class Chaos {
    NONE, NBSP, DEVANAGARI_DIGITS, LINE_BREAKS, UPPERCASE, TRICKY_PARTY, SPECIAL_CHAR_PARTY, HUGE_AMOUNT, TINY_AMOUNT,
    MULTIPART_SMS, HINDI_LANGUAGE, FOREIGN_CURRENCY,
}

sealed interface Truth {
    /** A real payment the user made or received. */
    data class Real(val templateId: String, val expected: Expected, val chaos: Chaos) : Truth

    /** Not a payment. */
    data class Noise(val category: NoiseCategory) : Truth

    /** The second half of a multi-part SMS whose first half carried the payment. */
    data object Fragment : Truth
}

data class SimMessage(val raw: RawMessage, val truth: Truth)

private val TRICKY_PARTIES = listOf(
    "Cafe On Road", "Pay And Go Mart", "Not Just Coffee", "Hotel From Kerala", "Ref Kitchen", "Upi Point", "For You Gifts",
)
private val SPECIAL_PARTIES = listOf(
    "Sharma & Sons", "D'Mart", "Café Coffee Day", "Haldiram's", "A.B. Traders", "Shree Ganesh (Main)", "M/s Gupta-Ji",
)
private const val DEVANAGARI = "०१२३४५६७८९"
private val ROBUST_CHAOS = listOf(
    Chaos.NBSP, Chaos.DEVANAGARI_DIGITS, Chaos.LINE_BREAKS, Chaos.UPPERCASE, Chaos.TRICKY_PARTY, Chaos.SPECIAL_CHAR_PARTY,
    Chaos.HUGE_AMOUNT, Chaos.TINY_AMOUNT, Chaos.MULTIPART_SMS,
)

class Generator(seed: Long) {
    private val random = Random(seed)
    private val zone = TimeZone.of("Asia/Kolkata")
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private fun t(id: String) = Synthetic.transactions.single { it.id == id }

    private val studentPay = listOf("phonepe-paid", "gpay-paid", "paytm-paid", "cred-paid", "navi-sent").map(::t)
    private val studentGet = listOf("phonepe-received", "gpay-received").map(::t)
    private val bankSms = listOf("hdfc-upi-debit", "sbi-upi-debit", "icici-upi-debit", "axis-card-spend", "kotak-upi-debit", "atm-withdrawal").map(::t)
    private val bankCredit = listOf("hdfc-upi-credit", "sbi-upi-credit").map(::t)
    private val appPay = listOf("phonepe-paid", "gpay-paid", "paytm-paid", "bhim-paid", "cred-paid", "amazonpay-paid", "navi-sent", "mobikwik-paid", "freecharge-paid").map(::t)
    private val shopGet = listOf("gpay-received", "phonepe-received", "hdfc-upi-credit").map(::t)

    fun persona(): Persona {
        var r = random.nextDouble()
        for (p in Persona.entries) { r -= p.share; if (r <= 0) return p }
        return Persona.STUDENT
    }

    fun month(persona: Persona): List<SimMessage> = buildList {
        for (day in 0 until 30) {
            val dayStart = start.plus(Duration.parse("${day}d"))
            val at = { CapturedAt(dayStart.plus(Duration.parse("${random.nextInt(86_400)}s")), zone) }
            val noiseRate = if (persona == Persona.SCAM_TARGET) 0.40 else 0.15
            repeat(messagesPerDay(persona)) {
                if (random.nextDouble() < noiseRate) add(noise(at())) else addAll(real(persona, at()))
            }
            if (day == 0 && persona == Persona.SALARIED) addAll(emit(t("salary-neft-credit"), Chaos.NONE, at()))
        }
    }

    private fun messagesPerDay(p: Persona) = when (p) {
        Persona.STUDENT -> random.nextInt(1, 4)
        Persona.SALARIED -> random.nextInt(3, 7)
        Persona.HEAVY_UPI -> random.nextInt(4, 9)
        Persona.SHOPKEEPER -> random.nextInt(50, 151)
        Persona.HINDI, Persona.TRAVELLER, Persona.SCAM_TARGET -> random.nextInt(2, 6)
    }

    private fun noise(at: CapturedAt): SimMessage {
        val template = Synthetic.noise[random.nextInt(Synthetic.noise.size)]
        val text = template.text(Synthetic.fields(random))
        return SimMessage(RawMessage(source(template.channel), template.sender, text, at), Truth.Noise(template.category))
    }

    private fun real(persona: Persona, at: CapturedAt): List<SimMessage> {
        val chaos = when {
            persona == Persona.HINDI && random.nextDouble() < 0.5 ->
                if (random.nextBoolean()) Chaos.HINDI_LANGUAGE else Chaos.DEVANAGARI_DIGITS
            persona == Persona.TRAVELLER && random.nextDouble() < 0.3 -> Chaos.FOREIGN_CURRENCY
            random.nextDouble() < 0.20 -> ROBUST_CHAOS[random.nextInt(ROBUST_CHAOS.size)]
            else -> Chaos.NONE
        }
        if (chaos == Chaos.FOREIGN_CURRENCY) return emit(t("axis-card-spend"), chaos, at)
        return when (persona) {
            Persona.STUDENT, Persona.HINDI ->
                emit(if (random.nextDouble() < 0.8) pick(studentPay) else pick(studentGet), chaos, at)
            Persona.SALARIED, Persona.TRAVELLER, Persona.SCAM_TARGET ->
                emit(if (random.nextDouble() < 0.85) pick(bankSms) else pick(bankCredit), chaos, at)
            Persona.SHOPKEEPER -> emit(pick(shopGet), chaos, at)
            // The same payment reported twice: by the UPI app and by the bank (de-dup is a later module).
            Persona.HEAVY_UPI -> {
                val fields = fieldsFor(chaos)
                emit(pick(appPay), chaos, at, fields) + emit(pick(bankSms.filter { it.id != "atm-withdrawal" && it.id != "axis-card-spend" }), chaos, at, fields)
            }
        }
    }

    private fun pick(list: List<TxnTemplate>) = list[random.nextInt(list.size)]

    private fun fieldsFor(chaos: Chaos): Fields {
        val base = Synthetic.fields(random)
        return when (chaos) {
            Chaos.TRICKY_PARTY -> base.copy(party = TRICKY_PARTIES[random.nextInt(TRICKY_PARTIES.size)])
            Chaos.SPECIAL_CHAR_PARTY -> base.copy(party = SPECIAL_PARTIES[random.nextInt(SPECIAL_PARTIES.size)])
            Chaos.HUGE_AMOUNT -> base.copy(amount = Money(random.nextLong(50_00_000_00, 99_00_00_000_00)))
            Chaos.TINY_AMOUNT -> base.copy(amount = Money(100))
            else -> base
        }
    }

    private fun emit(template: TxnTemplate, chaos: Chaos, at: CapturedAt, fields: Fields = fieldsFor(chaos)): List<SimMessage> {
        val src = source(template.channel)
        var expected = template.expected(fields)
        val text = when (chaos) {
            Chaos.HINDI_LANGUAGE ->
                if (expected.flow == Flow.DEBIT) "₹${Synthetic.grouped(fields.amount)} का भुगतान ${fields.party} को सफल रहा"
                else "${fields.party} से ₹${Synthetic.grouped(fields.amount)} प्राप्त हुए"
            Chaos.FOREIGN_CURRENCY -> {
                "Spent USD ${fields.amount.paise / 10_000}.${(fields.amount.paise % 100).toString().padStart(2, '0')} Axis Bank Card no. XX${fields.mask} " +
                    "26-10-26 10:00:00 IST ${fields.party} Avl Limit: INR 45,000"
            }
            else -> template.text(fields)
        }
        val chaotic = when (chaos) {
            Chaos.NBSP -> text.replace(' ', ' ')
            Chaos.DEVANAGARI_DIGITS -> text.map { if (it in '0'..'9') DEVANAGARI[it - '0'] else it }.joinToString("")
            Chaos.LINE_BREAKS -> text.replace(". ", ".\n").replaceFirst(" ", "\n")
            Chaos.UPPERCASE -> text.uppercase()
            else -> text
        }
        if (chaos == Chaos.UPPERCASE) expected = expected.copy(counterparty = expected.counterparty?.uppercase())
        if (chaos == Chaos.MULTIPART_SMS && template.channel == Channel.SMS && chaotic.length > 70) {
            val cut = 70
            return listOf(
                SimMessage(RawMessage(src, template.sender, chaotic.substring(0, cut), at), Truth.Real(template.id, expected, chaos)),
                SimMessage(RawMessage(src, template.sender, chaotic.substring(cut), at), Truth.Fragment),
            )
        }
        val effectiveChaos = if (chaos == Chaos.MULTIPART_SMS) Chaos.NONE else chaos
        return listOf(SimMessage(RawMessage(src, template.sender, chaotic, at), Truth.Real(template.id, expected, effectiveChaos)))
    }

    private fun source(c: Channel) = if (c == Channel.SMS) Source.SMS else Source.NOTIFICATION
}

/** Counters for one group (a template, a chaos type, a noise category…). */
class Tally {
    var n = 0; var recognised = 0; var allCorrect = 0
    var wrongAmount = 0; var wrongDirection = 0; var wrongParty = 0; var wrongRef = 0; var wrongMask = 0
    fun pct(x: Int) = if (n == 0) "-" else ((1000L * x / n) / 10.0).toString() + "%"
}

class SimulationResult(
    val users: Int,
    val personas: Map<Persona, Int>,
    val messages: Int,
    val byTemplate: Map<String, Tally>,
    val byChaos: Map<Chaos, Tally>,
    val noise: Map<NoiseCategory, Tally>,
    val fragments: Tally,
    val latenciesMicros: LongArray,
    val wrongExamples: Map<String, List<String>>,
) {
    fun percentile(p: Double): Long = if (latenciesMicros.isEmpty()) 0 else latenciesMicros[((latenciesMicros.size - 1) * p).toInt()]

    fun render(): String = buildString {
        appendLine("users=$users messages=$messages personas=$personas")
        appendLine("latency_us p50=${percentile(0.50)} p95=${percentile(0.95)} p99=${percentile(0.99)} max=${latenciesMicros.lastOrNull() ?: 0}")
        appendLine("-- real payments by template (no chaos): n | recognised | all fields right | wrong amount/dir/party/ref/mask")
        byTemplate.toSortedMap().forEach { (k, t) -> appendLine(row(k, t)) }
        appendLine("-- real payments by chaos")
        byChaos.toSortedMap().forEach { (k, t) -> appendLine(row(k.name, t)) }
        appendLine("-- noise: n | parsed as a transaction")
        noise.toSortedMap().forEach { (k, t) -> appendLine("$k | ${t.n} | ${t.recognised} (${t.pct(t.recognised)})") }
        appendLine("-- multipart SMS second halves: ${fragments.n} | parsed as a transaction ${fragments.recognised} (${fragments.pct(fragments.recognised)})")
        appendLine("-- examples of wrong results")
        wrongExamples.forEach { (k, v) -> v.forEach { appendLine("[$k] $it") } }
    }

    private fun row(k: String, t: Tally) =
        "$k | ${t.n} | ${t.pct(t.recognised)} | ${t.pct(t.allCorrect)} | ${t.pct(t.wrongAmount)}/${t.pct(t.wrongDirection)}/${t.pct(t.wrongParty)}/${t.pct(t.wrongRef)}/${t.pct(t.wrongMask)}"
}

object Simulation {

    fun run(users: Int, seed: Long = 42): SimulationResult {
        val generator = Generator(seed)
        val personas = mutableMapOf<Persona, Int>()
        val byTemplate = mutableMapOf<String, Tally>()
        val byChaos = mutableMapOf<Chaos, Tally>()
        val noise = mutableMapOf<NoiseCategory, Tally>()
        val fragments = Tally()
        val examples = mutableMapOf<String, MutableList<String>>()
        val latencies = ArrayList<Long>()
        var messages = 0

        repeat(users) {
            val persona = generator.persona()
            personas[persona] = (personas[persona] ?: 0) + 1
            for (msg in generator.month(persona)) {
                messages++
                val mark = TimeSource.Monotonic.markNow()
                val result = MessageParser.parse(msg.raw)
                latencies += mark.elapsedNow().inWholeMicroseconds
                val txn = (result as? ParseResult.Transaction)?.txn
                when (val truth = msg.truth) {
                    is Truth.Noise -> noise.getOrPut(truth.category) { Tally() }.apply { n++; if (txn != null) recognised++ }
                    Truth.Fragment -> fragments.apply { n++; if (txn != null) recognised++ }
                    is Truth.Real -> {
                        val tallies = listOf(byChaos.getOrPut(truth.chaos) { Tally() }) +
                            if (truth.chaos == Chaos.NONE) listOf(byTemplate.getOrPut(truth.templateId) { Tally() }) else emptyList()
                        val e = truth.expected
                        val wrong = mutableListOf<String>()
                        if (txn == null) wrong += "not recognised" else {
                            if (txn.amount != e.amount) wrong += "amount ${txn.amount} != ${e.amount}"
                            if ((txn.direction == Direction.DEBIT) != (e.flow == Flow.DEBIT)) wrong += "direction"
                            if (txn.counterparty != e.counterparty) wrong += "party '${txn.counterparty}' != '${e.counterparty}'"
                            if (txn.upiRef != e.upiRef) wrong += "ref ${txn.upiRef} != ${e.upiRef}"
                            if (txn.accountMask != e.accountMask) wrong += "mask ${txn.accountMask} != ${e.accountMask}"
                        }
                        tallies.forEach { t ->
                            t.n++
                            if (txn != null) t.recognised++
                            if (wrong.isEmpty()) t.allCorrect++
                            if (txn != null && txn.amount != e.amount) t.wrongAmount++
                            if (txn != null && (txn.direction == Direction.DEBIT) != (e.flow == Flow.DEBIT)) t.wrongDirection++
                            if (txn != null && txn.counterparty != e.counterparty) t.wrongParty++
                            if (txn != null && txn.upiRef != e.upiRef) t.wrongRef++
                            if (txn != null && txn.accountMask != e.accountMask) t.wrongMask++
                        }
                        if (wrong.isNotEmpty()) {
                            val key = "${truth.chaos}/${truth.templateId}"
                            val list = examples.getOrPut(key) { mutableListOf() }
                            if (list.size < 2) list += "${wrong.joinToString("; ")} :: ${msg.raw.text.take(160)}"
                        }
                    }
                }
            }
        }
        return SimulationResult(users, personas, messages, byTemplate, byChaos, noise, fragments, latencies.sorted().toLongArray(), examples)
    }
}
