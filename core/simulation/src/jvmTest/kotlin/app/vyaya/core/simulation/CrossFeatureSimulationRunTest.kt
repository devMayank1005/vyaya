package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.data.AppOpen
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.CatalogResult
import app.vyaya.core.data.CatalogStore
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.DetectedKind
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.LedgerStore
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.QuickLookStore
import app.vyaya.core.data.RulesStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.SplitStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.monthOf
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Cross-feature money simulation (branch review 2026-11-18 SIM-006 / FLAW-009): the same users go through two months
 * of everything at once. The app opens every morning (catch-up). Payments are captured from PhonePe and, half the time,
 * the bank's SMS too. A salary and a rent rule run, and the rent is sometimes paid early by UPI. Payments are split with
 * friends, who pay back by UPI later (the app asks; the user says yes). Some payments get a category.
 *
 * The reference is a ledger written here, from what each user did. It never calls the engine's own functions. Checked:
 * - each month's spending and income;
 * - spending by category;
 * - what each friend owes;
 * - rent counted exactly once a month;
 * - open lending in safe to spend.
 * Reports only. Size: -Psim.engine.users=N
 */
class CrossFeatureSimulationRunTest {

    private val home = TimeZone.of("Asia/Kolkata")
    private val first = LocalDate(2026, 11, 1)
    private val days = 61 // November and December

    @Test
    fun run_cross_feature_simulation(): Unit = runBlocking {
        val users = System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100
        val random = Random(47)
        var payments = 0; var splits = 0; var payBacks = 0; var earlyRent = 0
        var spentOff = 0; var incomeOff = 0; var categoryOff = 0; var owedOff = 0; var rentOff = 0; var lentOff = 0; var askMissed = 0
        val started = System.nanoTime()
        repeat(users) {
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(LocalTime(hour, minute)).toInstant(home)
            val start = at(first, 8)
            AppOpen(db).run(start)
            val food = (CatalogStore(db).addCategory("Food out", CategoryKind.EXPENSE, start) as CatalogResult.Saved).item
            val rent = 15_000L * 100; val salary = 60_000L * 100
            RulesStore(db).add(EntryType.EXPENSE, Money(rent), 5, null, "Rent", start, home)
            RulesStore(db).add(EntryType.INCOME, Money(salary), 1, null, "Salary", start, home)
            val ledger = LedgerStore(db)
            val friends = listOf("Rahul", "Priya").map { ledger.addPerson(it, start) }

            // The reference: what the user did, month by month (calendar months: the default start day is 1).
            val spent = HashMap<Int, Long>(); val income = HashMap<Int, Long>(); val foodSpent = HashMap<Int, Long>()
            val owes = HashMap<EntityId, Long>(); val lentIn = HashMap<Pair<Int, EntityId>, Long>()
            fun key(d: LocalDate) = d.monthNumber
            val used = HashSet<Long>()
            fun amount(): Long { while (true) { val r = random.nextLong(50, 9_000) * 100; if (used.add(r) && r != rent && r != salary) return r } }

            for (d in 0 until days) {
                val day = first.plus(d, DateTimeUnit.DAY)
                // Salary and rent: what the rules stand for, once each month.
                if (day.day == 1) income[key(day)] = (income[key(day)] ?: 0) + salary
                if (day.day == 5) spent[key(day)] = (spent[key(day)] ?: 0) + rent
                // Rent paid early by UPI on the 3rd, some months: the rule's month is linked to it, never doubled.
                if (day.day == 3 && random.nextInt(3) == 0) {
                    earlyRent++
                    CaptureStore(db).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹15,000 paid to Landlord", CapturedAt(at(day, 7), home)), at(day, 7))
                }
                AppOpen(db).run(at(day, 9))

                repeat(random.nextInt(0, 3)) { n ->
                    val paise = amount(); payments++
                    val t = at(day, 10 + n * 3, random.nextInt(0, 50))
                    val rupees = "%,d".format(paise / 100)
                    val shop = listOf("Swiggy", "Zomato", "DMart", "Uber India", "Barbeque Nation").random(random)
                    val out = CaptureStore(db).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹$rupees paid to $shop", CapturedAt(t, home)), t)
                    if (random.nextBoolean()) { // the bank's SMS for the same payment, minutes later
                        val s = t + random.nextInt(1, 6).minutes
                        CaptureStore(db).process(RawMessage(Source.SMS, "VM-HDFCBK-S", "Sent Rs.${paise / 100}.00 From HDFC Bank A/C *1234 To $shop On 26/10/26 " +
                            "Ref ${random.nextLong(100_000_000_000, 999_999_999_999)} Not You? Call 18002586161/SMS BLOCK UPI to 7308080808", CapturedAt(s, home)), s)
                    }
                    val entry = (out.single() as Outcome.Added).entry
                    when (random.nextInt(4)) {
                        0 -> { // split with a friend: the user's share is spending, the friend's goes to People
                            val friend = friends.random(random)
                            val share = (paise / 2) / 100 * 100
                            SplitStore(db).save(entry.copy(categoryId = food.id), emptyList(), listOf(friend.id to Money(share)), t)
                            splits++
                            spent[key(day)] = (spent[key(day)] ?: 0) + (paise - share)
                            foodSpent[key(day)] = (foodSpent[key(day)] ?: 0) + (paise - share)
                            owes[friend.id] = (owes[friend.id] ?: 0) + share
                            lentIn[key(day) to friend.id] = (lentIn[key(day) to friend.id] ?: 0) + share
                        }
                        1 -> { // put in a category
                            EntryStore(db).save(listOf(entry.copy(categoryId = food.id, updatedAt = t)))
                            spent[key(day)] = (spent[key(day)] ?: 0) + paise
                            foodSpent[key(day)] = (foodSpent[key(day)] ?: 0) + paise
                        }
                        else -> spent[key(day)] = (spent[key(day)] ?: 0) + paise
                    }
                }
                // A friend pays some back by UPI: asked, answered yes; it isn't income.
                if (random.nextInt(5) == 0) {
                    val friend = friends.random(random)
                    val owed = owes[friend.id] ?: 0
                    if (owed >= 100_00) {
                        val back = random.nextLong(1, owed / 100 + 1) * 100
                        val t = at(day, 20)
                        CaptureStore(db).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "Received ₹${"%,d".format(back / 100)} from ${friend.name}",
                            CapturedAt(t, home)), t)
                        val ask = DetectedInbox(db).items().firstOrNull { it.kind == DetectedKind.REPAY }
                        if (ask == null) { askMissed++; income[key(day)] = (income[key(day)] ?: 0) + back }
                        else { DetectedInbox(db).confirmRepayment(ask.id, t); owes[friend.id] = owed - back; payBacks++ }
                    }
                }
            }

            // Against the reference.
            val settings = SnapshotStore(db).loadRecords().settings
            for (m in listOf(11, 12)) {
                val month = settings.monthSchedule.monthContaining(LocalDate(2026, m, 15))
                val summary = MonthStore(db).watch(month, settings).first().summary
                if (summary.spent.paise != (spent[m] ?: 0)) spentOff++
                if (summary.income.paise != (income[m] ?: 0)) incomeOff++
                if ((summary.spentByCategory[food.id]?.paise ?: 0) != (foodSpent[m] ?: 0)) categoryOff++
                val rentEntries = EntryStore(db).all().count { !it.deleted && it.amount.paise == rent && settings.monthSchedule.monthOf(it.occurredAt) == month }
                if (rentEntries != 1) rentOff++
            }
            val balances = ledger.balances()
            friends.forEach { f -> if (balances.of(f.id).paise != (owes[f.id] ?: 0)) owedOff++ }
            // Open lending in December, as safe to spend sees it on the last day: each friend's December lending, at most
            // what they owe now.
            val openLent = friends.sumOf { f -> minOf(lentIn[12 to f.id] ?: 0, owes[f.id] ?: 0) }
            val safe = QuickLookStore(db).safeToSpend(at(LocalDate(2026, 12, 31), 21))
            if (safe?.lent?.paise != openLent) lentOff++
            db.close()
        }
        val report = buildString {
            appendLine("Cross-feature simulation: $users users, 2 months each (${"%.1f".format((System.nanoTime() - started) / 1e9)} s)")
            appendLine("payments $payments (split $splits); rent paid early $earlyRent; pay-backs $payBacks")
            appendLine("month spending off $spentOff; income off $incomeOff; food category off $categoryOff; rent not once a month $rentOff; " +
                "owed per friend off $owedOff; open lending off $lentOff; pay-back not asked $askMissed (all must be 0)")
        }
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("cross-feature-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }
}
