package app.vyaya.core.backup

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Generation
import app.vyaya.core.model.Sha256
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Backup format v3: v2 plus the key of entries made by recurring rules (CLAUDE.md rules 8.4 and 8.7). */
class BackupV3Test {

    private val golden = listOf(
        """{"t":"header","format":"vyaya-backup","schemaVersion":3,"appVersion":"1.0.0","createdOn":"android","exportedAt":"2026-10-27T12:00:00Z","currency":"INR","settings":{"monthStartDay":1,"startDayChanges":[{"effectiveFrom":"2026-04-01","transitionEnd":"2026-04-25","startDay":25}],"timezone":"Asia/Kolkata","alertsEnabled":true},"counts":{"accounts":1,"categories":2,"entries":2,"recurring":1,"people":1,"ledger":1,"budgets":1,"merchantRules":1}}""",
        """{"t":"account","id":"00000000-0000-4000-8000-000000000001","name":"HDFC Salary","kind":"BANK","mask":"1234","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"category","id":"00000000-0000-4000-8000-000000000002","name":"Food","kind":"EXPENSE","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"category","id":"00000000-0000-4000-8000-000000000003","name":"Groceries","kind":"EXPENSE","updatedAt":"2026-10-27T10:00:00Z","deleted":true}""",
        """{"t":"entry","id":"00000000-0000-4000-8000-000000000004","type":"EXPENSE","amountPaise":12050,"occurredAt":"2026-10-26T13:05:00Z","timezone":"Asia/Kolkata","accountId":"00000000-0000-4000-8000-000000000001","toAccountId":null,"categoryId":"00000000-0000-4000-8000-000000000002","payee":"Swiggy","note":"lunch","tags":["office"],"source":"NOTIFICATION","upiRef":"429112345678","splits":[{"categoryId":"00000000-0000-4000-8000-000000000002","amountPaise":10050},{"categoryId":"sys.saved","amountPaise":2000}],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false,"generatedBy":null}""",
        """{"t":"entry","id":"5405ba7b-1360-8c0c-81af-63d5e7c1ee7c","type":"EXPENSE","amountPaise":11900,"occurredAt":"2026-10-05T04:30:00Z","timezone":"Asia/Kolkata","accountId":"00000000-0000-4000-8000-000000000001","toAccountId":null,"categoryId":"00000000-0000-4000-8000-000000000002","payee":"Spotify","note":"","tags":[],"source":"RECURRING","upiRef":null,"splits":[],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false,"generatedBy":{"ruleId":"00000000-0000-4000-8000-000000000007","month":"2026-10"}}""",
        """{"t":"recurring","id":"00000000-0000-4000-8000-000000000007","type":"EXPENSE","amountPaise":11900,"dayOfMonth":5,"categoryId":"00000000-0000-4000-8000-000000000002","accountId":"00000000-0000-4000-8000-000000000001","note":"Spotify","paused":false,"startsOn":"2026-01-05","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"person","id":"00000000-0000-4000-8000-000000000005","name":"Rahul","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"ledger","id":"00000000-0000-4000-8000-000000000006","personId":"00000000-0000-4000-8000-000000000005","direction":"LENT","amountPaise":30000,"date":"2026-10-20","note":"Dinner","settled":false,"updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"budget","id":"00000000-0000-4000-8000-000000000008","categoryId":"00000000-0000-4000-8000-000000000002","limitPaise":500000,"updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"merchantRule","id":"00000000-0000-4000-8000-000000000009","payee":"swiggy","categoryId":"00000000-0000-4000-8000-000000000002","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
    )

    private fun signed(body: List<String>): String {
        val sha = Sha256().apply { body.forEach { update(it + "\n") } }.hexDigest()
        return (body + """{"t":"end","records":${body.size - 1},"sha256":"$sha"}""").joinToString("\n") + "\n"
    }

    private fun problems(text: String): List<String> = when (val r = BackupCodec.decode(text, NOW)) {
        is DecodeResult.Failure -> r.problems
        is DecodeResult.Success -> fail("expected failure but decoded")
    }

    private fun edited(line: Int, edit: (String) -> String) = golden.toMutableList().apply { this[line] = edit(this[line]) }

    @Test
    fun writes_exactly_the_v3_golden_lines() {
        // Runs on the JVM (Android) and the iOS simulator in CI: identical bytes, and identical derived ids.
        assertEquals(signed(golden), BackupCodec.encode(GOLDEN_V3_SNAPSHOT, META))
    }

    @Test
    fun reads_the_v3_golden_file_with_the_generation_key() {
        val snapshot = assertIs<DecodeResult.Success>(BackupCodec.decode(signed(golden), NOW)).snapshot
        assertEquals(GOLDEN_V3_SNAPSHOT.normalized(), snapshot)
        assertEquals(SPOTIFY_OCT, snapshot.entries.single { it.id == SPOTIFY_OCT.entryId }.generatedBy)
    }

    @Test
    fun export_import_export_is_identical_with_generated_entries() = runTest {
        checkAll(200, Arb.long()) { seed ->
            val snapshot = randomSnapshot(Random(seed), generated = true)
            val text = BackupCodec.encode(snapshot, META_LATE)
            val decoded = assertIs<DecodeResult.Success>(BackupCodec.decode(text, LATE), "seed=$seed").snapshot
            assertEquals(snapshot.normalized(), decoded, "seed=$seed")
            assertEquals(text, BackupCodec.encode(decoded, META_LATE), "seed=$seed")
        }
    }

    @Test
    fun v2_files_still_restore_and_their_entries_have_no_key() = runTest { // migration v2 -> v3
        checkAll(100, Arb.long()) { seed ->
            val snapshot = randomSnapshot(Random(seed))
            val fromV2 = assertIs<DecodeResult.Success>(BackupCodec.decode(BackupCodec.encodeV2(snapshot, META_LATE), LATE), "seed=$seed").snapshot
            assertEquals(snapshot.normalized(), fromV2, "seed=$seed")
            assertTrue(fromV2.entries.all { it.generatedBy == null })
        }
        val v2Golden = BackupCodec.encodeV2(GOLDEN_SNAPSHOT, META)
        assertTrue(v2Golden.startsWith("""{"t":"header","format":"vyaya-backup","schemaVersion":2,"""))
        assertNull(assertIs<DecodeResult.Success>(BackupCodec.decode(v2Golden, NOW)).snapshot.entries.single().generatedBy)
    }

    @Test
    fun a_v2_file_cannot_carry_a_generation_key() { // v2 is frozen: an extra field means the file was edited
        val v2 = BackupCodec.encodeV2(GOLDEN_SNAPSHOT, META).trimEnd().lines().dropLast(1).toMutableList()
        val entry = v2.indexOfFirst { it.startsWith("""{"t":"entry"""") }
        v2[entry] = v2[entry].removeSuffix("}") + ""","generatedBy":null}"""
        assertTrue(problems(signed(v2)).single().contains("Line ${entry + 1} is damaged"), problems(signed(v2)).toString())
    }

    @Test
    fun a_key_naming_an_unknown_rule_is_refused() {
        val other = "00000000-0000-4000-8000-0000000000ff"
        val derived = Generation(EntityId.parse(other)!!, YearMonth(2026, 10)).entryId.value
        val body = edited(5) { it.replace(SPOTIFY_OCT.entryId.value, derived).replace(""""ruleId":"00000000-0000-4000-8000-000000000007"""", """"ruleId":"$other"""") }
        assertTrue(problems(signed(body)).any { "unknown recurring rule $other" in it }, problems(signed(body)).toString())
    }

    @Test
    fun a_generated_entry_whose_id_does_not_match_its_key_is_refused() { // a second copy can't sneak in
        val body = edited(5) { it.replace(""""month":"2026-10"""", """"month":"2026-09"""") }
        assertTrue(problems(signed(body)).any { SPOTIFY_OCT.entryId.value in it && "must come from its rule and month" in it },
            problems(signed(body)).toString())
        val badMonth = edited(5) { it.replace(""""month":"2026-10"""", """"month":"1999-12"""") }
        assertTrue(problems(signed(badMonth)).any { "out of range" in it }, problems(signed(badMonth)).toString())
        val notAMonth = edited(5) { it.replace(""""month":"2026-10"""", """"month":"2026-10-05"""") }
        assertTrue(problems(signed(notAMonth)).any { "not a valid month" in it }, problems(signed(notAMonth)).toString())
    }

    /** The golden file with Spotify's generated entry moved to [month]. */
    private fun keyedFor(month: YearMonth, exportedAt: String = "2026-10-27T12:00:00Z"): String {
        val key = Generation(R1, month)
        return signed(golden.toMutableList().apply {
            this[0] = this[0].replace("2026-10-27T12:00:00Z", exportedAt)
            this[5] = this[5].replace(SPOTIFY_OCT.entryId.value, key.entryId.value).replace(""""month":"2026-10"""", """"month":"$month"""")
                .replace("2026-10-05T04:30:00Z", "$month-05T04:30:00Z") // paid in the key's month (DATA-001)
        })
    }

    @Test
    fun a_key_for_a_month_before_its_rule_starts_is_refused() { // DATA-004: catch-up can't have made it
        val p = problems(keyedFor(YearMonth(2025, 12))) // Spotify starts on 5 Jan 2026
        assertTrue(p.any { "2025-12" in it && "before its rule starts" in it }, p.toString())
        assertIs<DecodeResult.Success>(BackupCodec.decode(keyedFor(YearMonth(2026, 1)), NOW), "the rule's first month")
    }

    @Test
    fun a_generated_entry_keeps_its_rules_type() { // FLAW-006: amount may change, type may not
        val income = edited(5) { it.replace(""""type":"EXPENSE"""", """"type":"INCOME"""") }
        assertTrue(problems(signed(income)).any { SPOTIFY_OCT.entryId.value in it && "type INCOME" in it && "EXPENSE" in it },
            problems(signed(income)).toString())
        val cheaper = edited(5) { it.replace(""""amountPaise":11900""", """"amountPaise":5900""") }
        assertIs<DecodeResult.Success>(BackupCodec.decode(signed(cheaper), NOW), "this month's bill was smaller")
    }

    @Test
    fun a_generated_entry_dated_far_from_its_month_is_refused() { // DATA-001
        val december = edited(5) { it.replace("2026-10-05T04:30:00Z", "2026-12-05T04:30:00Z") }
        assertTrue(problems(signed(december)).any { SPOTIFY_OCT.entryId.value in it && "month either side" in it },
            problems(signed(december)).toString())
        val lateInNovember = edited(5) { it.replace("2026-10-05T04:30:00Z", "2026-11-03T04:30:00Z") }
        assertIs<DecodeResult.Success>(BackupCodec.decode(signed(lateInNovember), kotlin.time.Instant.parse("2026-11-04T00:00:00Z")))
    }

    @Test
    fun a_key_for_a_month_after_the_backup_was_made_is_refused() { // SEC-001: it would block that month's catch-up
        val p = problems(keyedFor(YearMonth(2030, 1)))
        assertTrue(p.any { "2030-01" in it && "after the backup was made" in it }, p.toString())
        assertTrue(problems(keyedFor(YearMonth(2026, 11))).any { "after the backup was made" in it }, "next month")
        assertIs<DecodeResult.Success>(BackupCodec.decode(keyedFor(YearMonth(2026, 10)), NOW), "the export month itself")
        // 31 Oct 20:00 UTC is already 1 Nov in Kolkata, the backup's time zone, so November can be generated.
        val lateOctober = keyedFor(YearMonth(2026, 11), exportedAt = "2026-10-31T20:00:00Z")
        assertIs<DecodeResult.Success>(BackupCodec.decode(lateOctober, kotlin.time.Instant.parse("2026-11-01T00:00:00Z")))
    }

    @Test
    fun an_entry_without_a_key_cannot_take_a_derived_id() { // PEN-003
        val november = Generation(R1, YearMonth(2026, 11)).entryId.value
        val body = edited(4) { it.replace("00000000-0000-4000-8000-000000000004", november) }
        assertTrue(problems(signed(body)).any { november in it && "derived id" in it }, problems(signed(body)).toString())
    }

    @Test
    fun recurring_rules_no_longer_store_when_they_last_generated() { // rule 8.5: worked out from the keys
        val stored = edited(6) { it.replace(""""startsOn":"2026-01-05",""", """"startsOn":"2026-01-05","lastGeneratedOn":"2026-10-05",""") }
        assertTrue(problems(signed(stored)).single().contains("Line 7 is damaged"), problems(signed(stored)).toString())
        val fromV2 = assertIs<DecodeResult.Success>(BackupCodec.decode(BackupCodec.encodeV2(GOLDEN_SNAPSHOT, META), NOW)).snapshot
        assertEquals(GOLDEN_SNAPSHOT.recurring, fromV2.recurring, "a v2 file's lastGeneratedOn is dropped")
    }

    @Test
    fun the_same_payment_generated_on_two_phones_merges_into_one_entry() { // why the id is derived
        val phoneA = GOLDEN_V3_SNAPSHOT
        val onA = phoneA.entries.single { it.generatedBy == SPOTIFY_OCT }
        val onB = onA.copy(createdAt = onA.createdAt.plus(kotlin.time.Duration.parse("PT2H")), updatedAt = onA.updatedAt.plus(kotlin.time.Duration.parse("PT2H")))
        val phoneB = GOLDEN_SNAPSHOT.copy(entries = listOf(onB))
        val merged = Restore.apply(phoneA, phoneB, RestoreMode.MERGE)
        assertEquals(1, merged.entries.count { it.generatedBy == SPOTIFY_OCT })
        assertEquals(onB, merged.entries.single { it.generatedBy == SPOTIFY_OCT }, "the newer copy wins")
    }

    @Test
    fun moving_a_rules_day_cannot_add_a_second_entry_for_the_month() { // DATA-002
        // Phone A generated October's Spotify on the 5th. On phone B the rule had moved to the 10th, so its
        // catch-up generated October on the 10th. Both are October, so they are one entry.
        val onA = GOLDEN_V3_SNAPSHOT.entries.single { it.generatedBy == SPOTIFY_OCT }
        val tenth = kotlin.time.Instant.parse("2026-10-10T04:30:00Z")
        val onB = onA.copy(occurredAt = onA.occurredAt.copy(instant = tenth), updatedAt = kotlin.time.Instant.parse("2026-10-27T11:00:00Z"))
        val phoneB = GOLDEN_SNAPSHOT.copy(recurring = GOLDEN_SNAPSHOT.recurring.map { it.copy(dayOfMonth = 10) }, entries = listOf(onB))
        val merged = Restore.apply(GOLDEN_V3_SNAPSHOT, phoneB, RestoreMode.MERGE)
        assertEquals(listOf(onB), merged.entries.filter { it.generatedBy?.month == YearMonth(2026, 10) })
    }
}
