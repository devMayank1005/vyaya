package app.vyaya.core.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The database opens on every target (JVM for Android, and iOS) and keeps what is saved. */
class DatabaseTest {

    @Test
    fun a_saved_row_reads_back() = runTest {
        val db = inMemoryDatabase()
        val row = EntryRow(id = "00000000-0000-4000-8000-000000000004", line = """{"t":"entry"}""", occurredAtMs = 1, amountPaise = 25_000,
            type = "EXPENSE", upiRef = "429112345678", payeeKey = "swiggy", ruleId = null, month = null, deleted = false, updatedAtMs = 2)
        db.entries().upsert(listOf(row))
        assertEquals(row, db.entries().byId(row.id))
        db.close()
    }
}
