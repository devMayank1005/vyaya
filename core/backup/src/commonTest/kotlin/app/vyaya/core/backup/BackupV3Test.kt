package app.vyaya.core.backup

import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Sha256
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
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
        """{"t":"entry","id":"5946636a-bf94-852e-9383-122ddd79410c","type":"EXPENSE","amountPaise":11900,"occurredAt":"2026-10-05T04:30:00Z","timezone":"Asia/Kolkata","accountId":"00000000-0000-4000-8000-000000000001","toAccountId":null,"categoryId":"00000000-0000-4000-8000-000000000002","payee":"Spotify","note":"","tags":[],"source":"RECURRING","upiRef":null,"splits":[],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false,"generatedBy":{"ruleId":"00000000-0000-4000-8000-000000000007","dueOn":"2026-10-05"}}""",
        """{"t":"recurring","id":"00000000-0000-4000-8000-000000000007","type":"EXPENSE","amountPaise":11900,"dayOfMonth":5,"categoryId":"00000000-0000-4000-8000-000000000002","accountId":"00000000-0000-4000-8000-000000000001","note":"Spotify","paused":false,"startsOn":"2026-01-05","lastGeneratedOn":"2026-10-05","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
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
        val derived = app.vyaya.core.model.Generation(EntityId.parse(other)!!, kotlinx.datetime.LocalDate(2026, 10, 5)).entryId.value
        val body = edited(5) { it.replace("5946636a-bf94-852e-9383-122ddd79410c", derived).replace(""""ruleId":"00000000-0000-4000-8000-000000000007"""", """"ruleId":"$other"""") }
        assertTrue(problems(signed(body)).any { "unknown recurring rule $other" in it }, problems(signed(body)).toString())
    }

    @Test
    fun a_generated_entry_whose_id_does_not_match_its_key_is_refused() { // a second copy can't sneak in
        val body = edited(5) { it.replace(""""dueOn":"2026-10-05"""", """"dueOn":"2026-11-05"""") }
        assertTrue(problems(signed(body)).any { "5946636a-bf94-852e-9383-122ddd79410c" in it && "must come from its rule and due date" in it },
            problems(signed(body)).toString())
        val badDate = edited(5) { it.replace(""""dueOn":"2026-10-05"""", """"dueOn":"1999-12-31"""") }
        assertTrue(problems(signed(badDate)).any { "out of range" in it }, problems(signed(badDate)).toString())
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
}
