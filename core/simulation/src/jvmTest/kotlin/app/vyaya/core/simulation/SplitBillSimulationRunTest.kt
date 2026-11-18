package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.Snapshot
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
                        if (r.item.splits.sumOf { it.amount.paise } != r.item.amount.paise) partsOff++
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
}
