package app.vyaya.core.backup

import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Settings
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * DATA-001 (recommendations-fixes report): the app must never write a backup it then refuses to restore. Every
 * snapshot made of valid records either writes and restores, or is refused when writing, with the reader's
 * own message.
 */
class WriterReaderAgreementTest {

    private fun writes(s: Snapshot) = BackupCodec.write(s, META_LATE) {}
    private fun reads(s: Snapshot): DecodeResult {
        val out = StringBuilder()
        BackupLines.write(BackupCodec.toContent(s, META_LATE), BackupCodec.SCHEMA_VERSION, BackupLimits()) { out.append(it).append('\n') }
        return BackupCodec.decode(out.toString(), LATE)
    }

    private val base = GOLDEN_V3_SNAPSHOT
    private val generated = base.entries.single { it.generatedBy != null }
    private val far = Instant.parse("2101-06-01T00:00:00Z")

    /** Valid records, but together (or against the export time) something the reader refuses. */
    private val refusedByReader: Map<String, Snapshot> = mapOf(
        "generated entry of another type" to base.copy(entries = base.entries - generated + generated.copy(type = EntryType.INCOME)),
        "rule starts after its entries" to base.copy(recurring = base.recurring.map { it.copy(startsOn = LocalDate(2026, 11, 5)) }),
        "unknown account" to base.copy(accounts = emptyList()),
        "unknown person" to base.copy(people = emptyList()),
        "unknown rule" to base.copy(recurring = emptyList()),
        "duplicate id" to base.copy(people = base.people + base.people),
        "stored system category" to base.copy(categories = base.categories + Category(EntityId.parse("sys.custom")!!, "Mine", CategoryKind.EXPENSE, T, false)),
        "other currency" to base.copy(settings = Settings("USD", IST, base.settings.monthSchedule, true)),
        "time after 2100" to base.copy(people = base.people.map { it.copy(updatedAt = far) }),
        "date after 2100" to base.copy(ledger = base.ledger.map { it.copy(date = LocalDate(2101, 1, 1)) }),
    )

    @Test
    fun the_writer_refuses_whatever_the_reader_would_refuse() {
        for ((case, snapshot) in refusedByReader) {
            val read = assertIs<DecodeResult.Failure>(reads(snapshot), case)
            val written = assertIs<EncodeResult.Failure>(writes(snapshot), "$case: written although restore refuses it: ${read.problems}")
            assertTrue(read.problems.any { it in written.reason }, "$case: the writer gives the reader's reason. ${written.reason} / ${read.problems}")
        }
    }

    @Test
    fun the_writer_refuses_changes_after_its_export_time_and_future_keys() { // SEC-005, SEC-001 at write time
        val earlyMeta = META.copy(exportedAt = Instant.parse("2026-09-15T00:00:00Z")) // before the records' changes
        assertIs<EncodeResult.Failure>(BackupCodec.write(base, earlyMeta) {})
        assertIs<EncodeResult.Success>(BackupCodec.write(base, META) {})
    }

    @Test
    fun anything_the_app_writes_it_can_restore() = runTest {
        checkAll(200, Arb.long()) { seed ->
            val random = Random(seed)
            val snapshot = randomSnapshot(random, generated = true).let { s ->
                if (random.nextBoolean()) s else refusedByReader.values.random(random) // mix in refused ones
            }
            val written = writes(snapshot) is EncodeResult.Success
            val restored = reads(snapshot) is DecodeResult.Success
            assertEquals(restored, written, "seed=$seed: write ${if (written) "succeeded" else "failed"} but read ${if (restored) "succeeded" else "failed"}")
        }
    }
}
