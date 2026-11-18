package app.vyaya.core.data

import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.model.Settings
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Split-bills report #2: a friend paying back by UPI is asked about, so it can be a pay-back (People), not income. */
class RepaymentTest {

    private val t = Instant.parse("2026-11-18T14:00:00Z")
    private val ist = TimeZone.of("Asia/Kolkata")

    private suspend fun rahulOwes400(db: VyayaDatabase): app.vyaya.core.model.Person {
        val food = (CatalogStore(db).addCategory("Food", CategoryKind.EXPENSE, t) as CatalogResult.Saved).item
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Rahul", t)
        ledger.addPerson("Priya", t)
        SplitStore(db).save(Entry(EntityId.random(), EntryType.EXPENSE, Money(800_00), CapturedAt(t, ist), null, null, food.id, "Dinner", "",
            emptyList(), EntrySource.MANUAL, null, emptyList(), null, t, t, false), emptyList(), listOf(rahul.id to Money(400_00)), t)
        return rahul
    }

    private suspend fun received(db: VyayaDatabase, rupees: Int, from: String): Entry {
        val at = t + 1.days
        return assertIs<Outcome.Added>(CaptureStore(db).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app",
            "Received ₹$rupees from $from", CapturedAt(at, ist)), at).single()).entry
    }

    @Test
    fun split_data_002_yes_makes_it_a_pay_back_not_income() = runTest {
        val db = inMemoryDatabase()
        val rahul = rahulOwes400(db)
        val income = received(db, 400, "Rahul Sharma")
        val ask = DetectedInbox(db).items().single { it.kind == DetectedKind.REPAY }
        assertEquals(income.id to rahul.id.value, ask.entry.id to ask.possibleDuplicateOf)
        DetectedInbox(db).confirmRepayment(ask.id, t + 2.days)
        assertEquals(0L, LedgerStore(db).balances().of(rahul.id).paise, "Rahul is settled")
        assertEquals(true, EntryStore(db).byId(income.id)!!.deleted, "not income")
        val month = Settings.default().monthSchedule.monthContaining(kotlinx.datetime.LocalDate(2026, 11, 19))
        assertEquals(0L, MonthStore(db).watch(month, Settings.default()).first().summary.income.paise)
        assertEquals(0, DetectedInbox(db).items().count { it.kind == DetectedKind.REPAY })
        db.close()
    }

    @Test
    fun split_data_002_no_keeps_it_as_income() = runTest {
        val db = inMemoryDatabase()
        val rahul = rahulOwes400(db)
        val income = received(db, 300, "Rahul")
        DetectedInbox(db).denyRepayment(DetectedInbox(db).items().single { it.kind == DetectedKind.REPAY }.id)
        assertEquals(400_00L, LedgerStore(db).balances().of(rahul.id).paise)
        assertEquals(false, EntryStore(db).byId(income.id)!!.deleted)
        db.close()
    }

    @Test
    fun split_data_002_asked_only_when_it_could_be_a_pay_back() = runTest {
        val db = inMemoryDatabase()
        rahulOwes400(db)
        received(db, 500, "Rahul") // more than he owes
        received(db, 100, "Ankit") // nobody we know
        received(db, 150, "Priya") // owes nothing
        assertEquals(0, DetectedInbox(db).items().count { it.kind == DetectedKind.REPAY })
        db.close()
    }

    @Test
    fun split_data_002_a_question_overtaken_by_events_just_closes() = runTest {
        val db = inMemoryDatabase()
        val rahul = rahulOwes400(db)
        val inbox = DetectedInbox(db)
        // Settled in People before the question was answered: nothing more comes off, and it stays income.
        val first = received(db, 400, "Rahul")
        LedgerStore(db).settleAll(rahul.id, t)
        inbox.confirmRepayment(inbox.items().single { it.kind == DetectedKind.REPAY }.id, t + 2.days)
        assertEquals(false, EntryStore(db).byId(first.id)!!.deleted)
        assertEquals(0, inbox.items().count { it.kind == DetectedKind.REPAY })
        db.close()
    }

    @Test
    fun split_data_002_undone_income_or_a_second_answer_changes_nothing() = runTest {
        val db = inMemoryDatabase()
        val rahul = rahulOwes400(db)
        val inbox = DetectedInbox(db)
        val income = received(db, 400, "Rahul")
        val ask = inbox.items().single { it.kind == DetectedKind.REPAY }
        inbox.undo(inbox.items().single { it.kind == DetectedKind.ADDED && it.entry.id == income.id }.id, t + 2.days) // "not mine"
        inbox.confirmRepayment(ask.id, t + 2.days)
        assertEquals(400_00L, LedgerStore(db).balances().of(rahul.id).paise, "no pay-back for money that isn't there")
        inbox.confirmRepayment(ask.id, t + 2.days); inbox.denyRepayment(ask.id) // already closed: nothing happens
        inbox.confirmRepayment("repay:nothing", t); inbox.denyRepayment("repay:nothing")
        assertEquals(400_00L, LedgerStore(db).balances().of(rahul.id).paise)
        db.close()
    }

    @Test
    fun split_data_002_yes_on_money_that_became_a_transfer_closes_without_deleting_it() = runTest {
        val db = inMemoryDatabase()
        val rahul = rahulOwes400(db)
        val inbox = DetectedInbox(db)
        val income = received(db, 400, "Rahul")
        EntryStore(db).save(listOf(income.copy(type = app.vyaya.core.model.EntryType.TRANSFER, categoryId = null, updatedAt = t + 2.days)))
        inbox.confirmRepayment(inbox.items().single { it.kind == DetectedKind.REPAY }.id, t + 2.days)
        assertEquals(false, EntryStore(db).byId(income.id)!!.deleted)
        assertEquals(400_00L, LedgerStore(db).balances().of(rahul.id).paise)
        db.close()
    }
}
