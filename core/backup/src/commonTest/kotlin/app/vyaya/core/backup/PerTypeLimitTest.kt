package app.vyaya.core.backup

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.MerchantRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PEN-002 (reports/core-data-2026-11-04.md): records other than entries are kept in memory while a backup is read, so
 * each type has a limit far above real use (a few hundred), shared by writer and reader.
 */
class PerTypeLimitTest {

    private val limits = BackupLimits(maxRecordsPerType = 5)

    private fun withRules(n: Int) = GOLDEN_V3_SNAPSHOT.copy(
        merchantRules = List(n) { MerchantRule(EntityId.random(), "payee $it", FOOD, T, false) },
    )

    @Test
    fun the_default_limit_is_ten_thousand_per_type() {
        assertEquals(10_000, BackupLimits().maxRecordsPerType)
    }

    @Test
    fun the_writer_refuses_more_than_the_limit_of_one_type() {
        assertIs<EncodeResult.Success>(BackupCodec.write(withRules(5), META_LATE, limits) {})
        val refused = assertIs<EncodeResult.Failure>(BackupCodec.write(withRules(6), META_LATE, limits) {})
        assertTrue("merchant rules" in refused.reason, refused.reason)
        val lines = RecordLines.of(withRules(6).normalized())
        val counts = RecordCounts(lines.accounts.size, lines.categories.size, lines.entries.size, lines.recurring.size, lines.people.size,
            lines.ledger.size, lines.budgets.size, lines.merchantRules.size)
        assertEquals(refused, StreamingBackup.begin(META_LATE, lines.settings, counts, limits) {}.second)
    }

    @Test
    fun the_reader_refuses_more_than_the_limit_as_soon_as_it_is_passed() {
        val text = BackupCodec.encode(withRules(6), META_LATE)
        val refused = assertIs<DecodeResult.Failure>(BackupCodec.decode(text, LATE, limits))
        assertEquals(listOf("The backup has too many merchant rules (limit 5)"), refused.problems)
        val d = BackupDecoder(LATE, limits)
        val stoppedAt = text.lines().indexOfFirst { !d.accept(it) }
        assertEquals(text.lines().indexOfLast { it.startsWith("{\"t\":\"merchantRule\"") }, stoppedAt, "stops at the sixth rule, before the end")
    }

    @Test
    fun entries_have_no_such_limit() {
        val s = GOLDEN_V3_SNAPSHOT.let { g -> g.copy(entries = g.entries + List(30) { g.entries.first().copy(id = EntityId.random()) }) }
        val most = BackupCodec.toContent(s, META_LATE).counts().filterIndexed { i, _ -> i != BackupLimits.ENTRIES }.max()
        assertTrue(s.entries.size > most)
        assertIs<DecodeResult.Success>(BackupCodec.decode(BackupCodec.encode(s, META_LATE), LATE, BackupLimits(maxRecordsPerType = most)))
    }

    @Test
    fun a_first_version_file_has_the_same_limit() {
        val refused = assertIs<DecodeResult.Failure>(BackupCodec.decode(GOLDEN_JSON, LATE, BackupLimits(maxRecordsPerType = 0)))
        assertTrue(refused.problems.single().startsWith("The backup has too many "), refused.problems.toString())
    }
}
