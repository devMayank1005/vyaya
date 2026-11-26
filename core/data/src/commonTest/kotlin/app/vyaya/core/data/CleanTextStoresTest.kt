package app.vyaya.core.data

import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import app.vyaya.core.parser.RawMessage
import app.vyaya.core.parser.Source
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** core-backup SEC-010: invisible characters never get into what is stored, typed or captured. */
class CleanTextStoresTest {

    private val t = Instant.parse("2026-11-26T06:00:00Z")
    private val rlo = "‮"; private val zwsp = "​"

    @Test
    fun sec_010_names_typed_in_are_stored_clean() = runTest {
        val db = inMemoryDatabase()
        val catalog = CatalogStore(db)
        assertEquals("Pets", (catalog.addCategory("Pe${zwsp}ts$rlo", CategoryKind.EXPENSE, t) as CatalogResult.Saved).item.name)
        assertEquals("HDFC", (catalog.addAccount("HD${rlo}FC", AccountKind.BANK, null, t) as CatalogResult.Saved).item.name)
        val ledger = LedgerStore(db)
        val rahul = ledger.addPerson("Ra${zwsp}hul", t)
        assertEquals("Rahul", rahul.name)
        ledger.renamePerson(rahul, "Rahul\u0000 K", t)
        assertEquals("Rahul K", ledger.people().single().name)
        assertEquals("Dinner", ledger.add(rahul.id, app.vyaya.core.model.LedgerDirection.LENT, Money(100_00), kotlinx.datetime.LocalDate(2026, 11, 26),
            "Din${rlo}ner", t).note)
        assertEquals("Rent", RulesStore(db).add(EntryType.EXPENSE, Money(1_000_00), 5, null, "Re${zwsp}nt", t, TimeZone.of("Asia/Kolkata")).note)
        db.close()
    }

    @Test
    fun sec_010_a_captured_payee_is_clean() = runTest {
        val db = inMemoryDatabase()
        val out = CaptureStore(db).process(RawMessage(Source.NOTIFICATION, "com.phonepe.app", "₹250 paid to ${rlo}yggiws",
            CapturedAt(t, TimeZone.of("Asia/Kolkata"))), t).single()
        assertEquals("yggiws", assertIs<Outcome.Added>(out).entry.payee)
        db.close()
    }
}
