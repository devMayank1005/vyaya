package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.parser.Source
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.engine.SafeToSpend
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.data.DetectedKind
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.data.CatalogResult
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.LedgerStore
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.SplitStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.engine.SplitShares
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
import app.vyaya.core.model.monthOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Split bills for many users (option A): payments split across categories and with friends through SplitStore, with
 * chaos (shares that don't add up, friends taking everything, income). Checks, against independent sums: every entry's
 * parts add up to it; the month's spending is the users' own shares; budgets by category; what friends owe is their
 * shares; a refused split changes nothing. Reports only. Size: -Psim.engine.users=N
 */
class SplitBillSimulationRunTest {

    @Test
    fun run_split_bill_simulation(): Unit = runBlocking {
        val users = System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100
        val random = Random(41)
        val ist = TimeZone.of("Asia/Kolkata")
        var splits = 0; var refused = 0; var refusedChanged = 0; var partsOff = 0; var spentOff = 0; var categoryOff = 0; var owedOff = 0
        val started = System.nanoTime()
        repeat(users) {
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            val t0 = Instant.parse("2026-11-01T06:00:00Z")
            val cats = List(4) { Category(EntityId.random(), "C$it", CategoryKind.EXPENSE, t0, false) }
            SnapshotStore(db).saveRecords(Snapshot.empty().copy(categories = cats))
            val ledger = LedgerStore(db)
            val people = List(random.nextInt(1, 5)) { ledger.addPerson("Friend $it", t0) }
            var owed = 0L
            repeat(random.nextInt(5, 40)) { n ->
                val at = t0 + (n * 7).hours
                val amount = Money(random.nextLong(100, 2_000_000))
                val entry = Entry(EntityId.random(), if (random.nextInt(15) == 0) EntryType.INCOME else EntryType.EXPENSE, amount, CapturedAt(at, ist),
                    null, null, cats.random(random).id, "Shop", "", emptyList(), EntrySource.NOTIFICATION, null, emptyList(), null, at, at, false)
                val friends = people.shuffled(random).take(random.nextInt(0, people.size + 1))
                var shares = if (friends.isEmpty()) emptyList() else SplitShares.equal(amount, friends.size).friends.let { eq ->
                    if (random.nextBoolean()) eq else eq.map { Money(random.nextLong(0, it.paise + 2)) } // custom, sometimes zero
                }
                if (random.nextInt(20) == 0 && friends.isNotEmpty()) shares = shares.mapIndexed { i, s -> if (i == 0) amount else s } // chaos: one takes all
                val theirs = shares.sumOf { it.paise }
                val yours = amount.paise - theirs
                val mine = if (yours <= 1 || random.nextBoolean()) emptyList() else {
                    val a = random.nextLong(1, yours)
                    val parts = listOf(Split(cats[0].id, Money(a)), Split(cats[1].id, Money(yours - a)))
                    if (random.nextInt(10) == 0) parts.map { it.copy(amount = Money(it.amount.paise + 1)) } else parts // chaos: off by a paisa
                }
                val before = EntryStore(db).all() to SnapshotStore(db).loadRecords().ledger // what is stored, compared by value
                splits++
                when (val r = SplitStore(db).save(entry, mine.filter { it.amount.paise > 0 }, friends.map { it.id }.zip(shares), at)) {
                    is CatalogResult.Saved -> {
                        // Nothing to split (no parts, no friends) saves a plain entry (split-bills #3); else the parts add up.
                        if (r.item.splits.isNotEmpty() && r.item.splits.sumOf { it.amount.paise } != r.item.amount.paise) partsOff++
                        if (r.item.splits.isEmpty() && (mine.any { it.amount.paise > 0 } || theirs > 0)) partsOff++
                        owed += theirs
                    }
                    is CatalogResult.Refused -> {
                        refused++
                        if ((EntryStore(db).all() to SnapshotStore(db).loadRecords().ledger) != before) refusedChanged++
                    }
                }
            }
            // The month as Home shows it, against the independent sums.
            val settings = SnapshotStore(db).loadRecords().settings
            val month = settings.monthSchedule.monthContaining(kotlinx.datetime.LocalDate(2026, 11, 10))
            val summary = MonthStore(db).watch(month, settings).first().summary
            val live = EntryStore(db).all().filter { !it.deleted && it.type == EntryType.EXPENSE && settings.monthSchedule.monthOf(it.occurredAt) == month }
            val own = live.sumOf { e -> e.amount.paise - e.splits.filter { it.categoryId == SystemCategories.LENT.id }.sumOf { it.amount.paise } }
            if (summary.spent.paise != own) spentOff++
            for (c in cats) {
                val want = live.sumOf { e -> if (e.splits.isEmpty()) (if (e.categoryId == c.id) e.amount.paise else 0) else e.splits.filter { it.categoryId == c.id }.sumOf { it.amount.paise } }
                if ((summary.spentByCategory[c.id]?.paise ?: 0) != want) categoryOff++
            }
            if (ledger.balances().owedToYou.paise != owed) owedOff++
            db.close()
        }
        val report = buildString {
            appendLine("Split-bill simulation: $users users (${"%.1f".format((System.nanoTime() - started) / 1e9)} s)")
            appendLine("splits tried $splits: refused $refused (changed something while refused: $refusedChanged)")
            appendLine("parts not adding up $partsOff; month spending != own shares $spentOff; category != sum $categoryOff; owed != friends' shares $owedOff (all must be 0)")
        }
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("split-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }

    /**
     * Split-bills fixes #1–#4 for many users: split payments later edited (other friends, other shares), deleted (shares
     * taken back or kept), friends paying back by UPI (captured, asked, answered yes or no; chaos: too much, strangers,
     * the same message twice), a backup round trip. Checked against an independent model of what each friend owes:
     * balances per person, no question that shouldn't be asked, links surviving the backup, open lending ≤ owed.
     */
    @Test
    fun run_split_bill_changes_simulation(): Unit = runBlocking {
        val users = System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100
        val random = Random(43)
        val ist = TimeZone.of("Asia/Kolkata")
        val names = listOf("Rahul", "Priya", "Ankit", "Neha")
        var edits = 0; var deletes = 0; var takenBack = 0; var messages = 0; var asked = 0; var askedWrongly = 0; var missedAsks = 0
        var confirmed = 0; var denied = 0; var personOff = 0; var linksLost = 0; var backupRefused = 0; var lentOver = 0; var incomeOff = 0
        val started = System.nanoTime()
        repeat(users) {
            val db: VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
            val t0 = Instant.parse("2026-11-01T06:00:00Z")
            val food = Category(EntityId.random(), "Food", CategoryKind.EXPENSE, t0, false)
            SnapshotStore(db).saveRecords(Snapshot.empty().copy(categories = listOf(food)))
            val ledger = LedgerStore(db)
            val people = names.take(random.nextInt(1, names.size + 1)).map { ledger.addPerson(it, t0) }
            // The model: each live payment's shares, shares kept from deleted payments, and pay-backs.
            val shares = LinkedHashMap<EntityId, Pair<Entry, List<Pair<EntityId, Long>>>>()
            val kept = HashMap<EntityId, Long>(); val repaid = HashMap<EntityId, Long>()
            fun owes(p: EntityId) = shares.values.sumOf { (_, s) -> s.filter { it.first == p }.sumOf { it.second } } + (kept[p] ?: 0) - (repaid[p] ?: 0)
            var keptIncome = 0L
            var clock = t0
            suspend fun split(entry: Entry, friends: List<EntityId>, at: Instant): Boolean {
                val each = if (friends.isEmpty()) emptyList() else SplitShares.equal(entry.amount, friends.size).friends.map { Money(it.paise / 100 * 100) }
                val pairs = friends.zip(each).filter { it.second.paise > 0 }
                return when (val r = SplitStore(db).save(entry, emptyList(), pairs, at)) {
                    is CatalogResult.Saved -> { if (pairs.isEmpty()) shares.remove(entry.id) else shares[entry.id] = r.item to pairs.map { it.first to it.second.paise }; true }
                    is CatalogResult.Refused -> false
                }
            }
            repeat(random.nextInt(10, 40)) {
                clock += random.nextLong(1, 20).hours
                when (random.nextInt(10)) {
                    in 0..3 -> { // a new shared payment
                        val e = Entry(EntityId.random(), EntryType.EXPENSE, Money(random.nextLong(100, 50_000) * 100), CapturedAt(clock, ist), null, null,
                            food.id, "Shop", "", emptyList(), EntrySource.MANUAL, null, emptyList(), null, clock, clock, false)
                        split(e, people.shuffled(random).take(random.nextInt(1, people.size + 1)).map { it.id }, clock)
                    }
                    4, 5 -> shares.keys.randomOrNull(random)?.let { id -> // edited: other friends, or none
                        edits++
                        val e = EntryStore(db).byId(id)!!
                        split(e, people.shuffled(random).take(random.nextInt(0, people.size + 1)).map { it.id }, clock)
                    }
                    6 -> shares.keys.randomOrNull(random)?.let { id -> // deleted
                        deletes++
                        val back = random.nextBoolean()
                        SplitStore(db).delete(EntryStore(db).byId(id)!!, back, clock)
                        val (_, s) = shares.remove(id)!!
                        if (back) takenBack++ else s.forEach { (p, a) -> kept[p] = (kept[p] ?: 0) + a }
                    }
                    else -> { // money in by UPI
                        val stranger = random.nextInt(6) == 0
                        val who = if (stranger) null else people.random(random)
                        val owed = who?.let { owes(it.id) } ?: 0
                        val rupees = when {
                            who == null || owed < 100 || random.nextInt(6) == 0 -> random.nextLong(1, 5_000) // chaos: a stranger, or more than owed
                            else -> random.nextLong(1, owed / 100 + 1)
                        }
                        val from = who?.name?.let { if (random.nextBoolean()) "$it Sharma" else it } ?: "Vikram"
                        val raw = RawMessage(Source.NOTIFICATION, "com.phonepe.app", "Received ₹$rupees from $from", CapturedAt(clock, ist))
                        messages++
                        val outcomes = CaptureStore(db).process(raw, clock)
                        if (random.nextInt(8) == 0) CaptureStore(db).process(raw, clock) // chaos: the same message again
                        val added = outcomes.filterIsInstance<Outcome.Added>().singleOrNull() ?: return@repeat
                        val ask = DetectedInbox(db).items().singleOrNull { it.kind == DetectedKind.REPAY && it.entry.id == added.entry.id }
                        val shouldAsk = who != null && owed >= rupees * 100
                        if (ask != null) asked++
                        if (ask != null && !shouldAsk) askedWrongly++
                        if (ask == null && shouldAsk) missedAsks++
                        if (ask == null) { keptIncome += rupees * 100; return@repeat }
                        if (random.nextInt(4) == 0) { DetectedInbox(db).denyRepayment(ask.id); denied++; keptIncome += rupees * 100 }
                        else { DetectedInbox(db).confirmRepayment(ask.id, clock); confirmed++; repaid[who!!.id] = (repaid[who.id] ?: 0) + rupees * 100 }
                    }
                }
            }
            val balances = ledger.balances()
            for (p in people) if (balances.of(p.id).paise != owes(p.id)) personOff++
            val income = EntryStore(db).all().filter { !it.deleted && it.type == EntryType.INCOME }.sumOf { it.amount.paise }
            if (income != keptIncome) incomeOff++
            // The links survive a backup round trip; what's still lent this month is never more than what's owed.
            val snapshot = SnapshotStore(db).load()
            val text = BackupCodec.encode(snapshot, ExportMeta("1", "android", clock))
            when (val r = BackupCodec.decode(text, clock)) {
                is DecodeResult.Success -> if (r.snapshot.ledger.map { it.id to it.paymentId }.toSet() != snapshot.ledger.map { it.id to it.paymentId }.toSet()) linksLost++
                is DecodeResult.Failure -> backupRefused++
            }
            val settings = snapshot.settings
            for (m in listOf(kotlinx.datetime.LocalDate(2026, 11, 10), kotlinx.datetime.LocalDate(2026, 12, 10))) {
                if (SafeToSpend.openLent(snapshot.ledger, settings.monthSchedule.monthContaining(m)).paise > balances.owedToYou.paise) lentOver++
            }
            db.close()
        }
        val report = buildString {
            appendLine("Split-bill changes simulation: $users users (${"%.1f".format((System.nanoTime() - started) / 1e9)} s)")
            appendLine("edits $edits; deletes $deletes (shares taken back $takenBack); money-in messages $messages: asked $asked, yes $confirmed, no $denied")
            appendLine("asked wrongly $askedWrongly; not asked when it should $missedAsks; owed per person off $personOff; income off $incomeOff;" +
                " links lost in backup $linksLost; backup refused $backupRefused; open lending > owed $lentOver (all must be 0)")
        }
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).resolveSibling("split-changes-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }
}
