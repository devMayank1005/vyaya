package app.vyaya.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * FLAW-001 (core-backup-v2-fixes): a backup that looks "made in the future" can be restored anyway after the
 * user confirms. Every date after this phone's time is reset to it, so the restored records can't win every
 * later merge (the reason SEC-005 refuses such files).
 */
class RestoreAnywayTest {

    private val made = BackupCodec.encode(GOLDEN_V3_SNAPSHOT, META) // exported 27 Oct 2026 12:00 UTC
    private val phoneBehind = Instant.parse("2026-10-24T09:00:00Z")

    @Test
    fun a_backup_from_a_phone_whose_clock_was_ahead_can_be_restored_anyway() {
        val refused = assertIs<DecodeResult.Failure>(BackupCodec.decode(made, phoneBehind))
        assertTrue(refused.clockBehind, "the app can offer Restore anyway")

        val restored = assertIs<DecodeResult.Success>(BackupCodec.decode(made, phoneBehind, restoreAnyway = true))
        val s = restored.snapshot
        val stamps = s.accounts.map { it.updatedAt } + s.categories.map { it.updatedAt } + s.people.map { it.updatedAt } +
            s.entries.flatMap { listOf(it.createdAt, it.updatedAt) } + s.recurring.map { it.updatedAt } +
            s.ledger.map { it.updatedAt } + s.budgets.map { it.updatedAt } + s.merchantRules.map { it.updatedAt }
        assertTrue(stamps.all { it <= phoneBehind }, "nothing restored is dated after this phone's time")
        assertEquals(phoneBehind, restored.meta.exportedAt)

        // Only the dates that were ahead change: the data itself is the same.
        val original = GOLDEN_V3_SNAPSHOT.normalized()
        assertEquals(original.entries.map { Triple(it.id, it.amount, it.occurredAt) }, s.entries.map { Triple(it.id, it.amount, it.occurredAt) })
        assertEquals(original.settings, s.settings)
        assertEquals(original.ledger.map { it.copy(updatedAt = phoneBehind) }, s.ledger)
    }

    @Test
    fun older_backup_formats_can_be_restored_anyway_too() {
        for (old in listOf(BackupCodec.encodeV1(GOLDEN_SNAPSHOT, META), BackupCodec.encodeV2(GOLDEN_SNAPSHOT, META))) {
            assertTrue(assertIs<DecodeResult.Failure>(BackupCodec.decode(old, phoneBehind)).clockBehind)
            assertIs<DecodeResult.Success>(BackupCodec.decode(old, phoneBehind, restoreAnyway = true))
        }
    }

    @Test
    fun restore_anyway_is_offered_only_when_the_clock_is_the_only_problem() {
        // A normal failure: nothing to do with the clock.
        val damaged = made.replace("\"amountPaise\":30000", "\"amountPaise\":90000")
        assertFalse(assertIs<DecodeResult.Failure>(BackupCodec.decode(damaged, NOW)).clockBehind)
        assertFalse(assertIs<DecodeResult.Failure>(BackupCodec.decode(damaged, phoneBehind)).clockBehind, "the clock and damage")
        assertIs<DecodeResult.Failure>(BackupCodec.decode(damaged, phoneBehind, restoreAnyway = true), "damage is never restored")
        // A file that restores normally needs no confirmation.
        assertIs<DecodeResult.Success>(BackupCodec.decode(made, NOW, restoreAnyway = true))
    }

    @Test
    fun restore_anyway_still_refuses_records_newer_than_the_backup_itself() { // SEC-005 still holds
        val lines = made.trimEnd().lines().dropLast(1).toMutableList()
        val person = lines.indexOfFirst { it.startsWith("""{"t":"person"""") }
        lines[person] = lines[person].replace("2026-10-27T10:00:00Z", "2026-10-29T10:00:00Z") // after the export
        val sha = app.vyaya.core.model.Sha256().apply { lines.forEach { update(it + "\n") } }.hexDigest()
        val text = (lines + """{"t":"end","records":${lines.size - 1},"sha256":"$sha"}""").joinToString("\n")
        val p = assertIs<DecodeResult.Failure>(BackupCodec.decode(text, phoneBehind, restoreAnyway = true)).problems
        assertTrue(p.any { "after the backup was made" in it }, p.toString())
    }
}
