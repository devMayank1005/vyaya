package app.vyaya.core.backup

import app.vyaya.core.model.EntityId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Backup format v4: v3 plus the payment a ledger entry came from (split bills, report 2026-11-18 #1: a friend's share
 * linked to the dinner it was part of). CLAUDE.md rule 8.7: a schemaVersion bump, a migration and a round trip.
 */
class BackupV4Test {

    private val payment = EntityId.parse("00000000-0000-4000-8000-000000000004")!!
    private val snapshot = GOLDEN_V3_SNAPSHOT.copy(ledger = GOLDEN_V3_SNAPSHOT.ledger.map { it.copy(paymentId = payment) })

    private val golden = listOf(
        """{"t":"header","format":"vyaya-backup","schemaVersion":4,"appVersion":"1.0.0","createdOn":"android","exportedAt":"2026-10-27T12:00:00Z","currency":"INR","settings":{"monthStartDay":1,"startDayChanges":[{"effectiveFrom":"2026-04-01","transitionEnd":"2026-04-25","startDay":25}],"timezone":"Asia/Kolkata","alertsEnabled":true},"counts":{"accounts":1,"categories":2,"entries":2,"recurring":1,"people":1,"ledger":1,"budgets":1,"merchantRules":1}}""",
        """{"t":"account","id":"00000000-0000-4000-8000-000000000001","name":"HDFC Salary","kind":"BANK","mask":"1234","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"category","id":"00000000-0000-4000-8000-000000000002","name":"Food","kind":"EXPENSE","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"category","id":"00000000-0000-4000-8000-000000000003","name":"Groceries","kind":"EXPENSE","updatedAt":"2026-10-27T10:00:00Z","deleted":true}""",
        """{"t":"entry","id":"00000000-0000-4000-8000-000000000004","type":"EXPENSE","amountPaise":12050,"occurredAt":"2026-10-26T13:05:00Z","timezone":"Asia/Kolkata","accountId":"00000000-0000-4000-8000-000000000001","toAccountId":null,"categoryId":"00000000-0000-4000-8000-000000000002","payee":"Swiggy","note":"lunch","tags":["office"],"source":"NOTIFICATION","upiRef":"429112345678","splits":[{"categoryId":"00000000-0000-4000-8000-000000000002","amountPaise":10050},{"categoryId":"sys.saved","amountPaise":2000}],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false,"generatedBy":null}""",
        """{"t":"entry","id":"5405ba7b-1360-8c0c-81af-63d5e7c1ee7c","type":"EXPENSE","amountPaise":11900,"occurredAt":"2026-10-05T04:30:00Z","timezone":"Asia/Kolkata","accountId":"00000000-0000-4000-8000-000000000001","toAccountId":null,"categoryId":"00000000-0000-4000-8000-000000000002","payee":"Spotify","note":"","tags":[],"source":"RECURRING","upiRef":null,"splits":[],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false,"generatedBy":{"ruleId":"00000000-0000-4000-8000-000000000007","month":"2026-10"}}""",
        """{"t":"recurring","id":"00000000-0000-4000-8000-000000000007","type":"EXPENSE","amountPaise":11900,"dayOfMonth":5,"categoryId":"00000000-0000-4000-8000-000000000002","accountId":"00000000-0000-4000-8000-000000000001","note":"Spotify","paused":false,"startsOn":"2026-01-05","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"person","id":"00000000-0000-4000-8000-000000000005","name":"Rahul","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"ledger","id":"00000000-0000-4000-8000-000000000006","personId":"00000000-0000-4000-8000-000000000005","direction":"LENT","amountPaise":30000,"date":"2026-10-20","note":"Dinner","settled":false,"updatedAt":"2026-10-27T10:00:00Z","deleted":false,"paymentId":"00000000-0000-4000-8000-000000000004"}""",
        """{"t":"budget","id":"00000000-0000-4000-8000-000000000008","categoryId":"00000000-0000-4000-8000-000000000002","limitPaise":500000,"updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"merchantRule","id":"00000000-0000-4000-8000-000000000009","payee":"swiggy","categoryId":"00000000-0000-4000-8000-000000000002","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
    )

    private fun signed(body: List<String>): String {
        val sha = app.vyaya.core.model.Sha256().apply { body.forEach { update(it + "\n") } }.hexDigest()
        return (body + """{"t":"end","records":${body.size - 1},"sha256":"$sha"}""").joinToString("\n") + "\n"
    }

    private fun problems(text: String): List<String> = when (val r = BackupCodec.decode(text, NOW)) {
        is DecodeResult.Failure -> r.problems
        is DecodeResult.Success -> emptyList()
    }

    @Test
    fun writes_exactly_the_v4_golden_lines() {
        assertEquals(signed(golden), BackupCodec.encode(snapshot, META))
    }

    @Test
    fun reads_the_v4_golden_file_with_the_payment_link() {
        val read = assertIs<DecodeResult.Success>(BackupCodec.decode(signed(golden), NOW)).snapshot
        assertEquals(snapshot.normalized(), read)
        assertEquals(payment, read.ledger.single().paymentId)
    }

    @Test
    fun export_import_export_is_identical_with_links() = runTest {
        checkAll(200, Arb.long()) { seed ->
            val random = Random(seed)
            val base = randomSnapshot(random, generated = true)
            val linked = base.copy(ledger = base.ledger.map { l -> if (random.nextBoolean() && base.entries.isNotEmpty()) l.copy(paymentId = base.entries.random(random).id) else l })
            val text = BackupCodec.encode(linked, META_LATE)
            val decoded = assertIs<DecodeResult.Success>(BackupCodec.decode(text, LATE), "seed=$seed").snapshot
            assertEquals(linked.normalized(), decoded, "seed=$seed")
            assertEquals(text, BackupCodec.encode(decoded, META_LATE), "seed=$seed")
        }
    }

    @Test
    fun v3_files_still_restore_and_their_ledger_has_no_link() = runTest { // migration v3 -> v4
        val v3 = BackupCodec.encodeV3(GOLDEN_V3_SNAPSHOT, META)
        assertTrue(v3.startsWith("""{"t":"header","format":"vyaya-backup","schemaVersion":3,"""))
        val read = assertIs<DecodeResult.Success>(BackupCodec.decode(v3, NOW)).snapshot
        assertEquals(GOLDEN_V3_SNAPSHOT.normalized(), read)
        assertEquals(null, read.ledger.single().paymentId)
        checkAll(100, Arb.long()) { seed ->
            val s = randomSnapshot(Random(seed), generated = true)
            assertEquals(s.normalized(), assertIs<DecodeResult.Success>(BackupCodec.decode(BackupCodec.encodeV3(s, META_LATE), LATE), "seed=$seed").snapshot)
        }
    }

    @Test
    fun a_v3_file_cannot_carry_a_payment_link() { // v3 is frozen: an extra field means the file was edited
        val v3 = BackupCodec.encodeV3(GOLDEN_V3_SNAPSHOT, META).trimEnd().lines().dropLast(1).toMutableList()
        val ledger = v3.indexOfFirst { it.startsWith("""{"t":"ledger"""") }
        v3[ledger] = v3[ledger].removeSuffix("}") + ""","paymentId":null}"""
        assertTrue(problems(signed(v3)).single().contains("Line ${ledger + 1} is damaged"), problems(signed(v3)).toString())
    }

    @Test
    fun a_link_to_an_unknown_payment_is_refused() {
        val edited = golden.map { if (it.startsWith("""{"t":"ledger"""")) it.replace("000000000004\"}", "0000000000ff\"}") else it }
        assertTrue(problems(signed(edited)).any { it.contains("unknown payment") }, problems(signed(edited)).toString())
    }

    @Test
    fun a_link_that_is_not_an_id_is_refused_and_no_unknown_version_is_written() {
        val edited = golden.map { if (it.startsWith("""{"t":"ledger"""")) it.replace("\"paymentId\":\"00000000-0000-4000-8000-000000000004\"", "\"paymentId\":\"dinner\"") else it }
        assertTrue(problems(signed(edited)).isNotEmpty(), "a payment id that isn't one")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            BackupLines.write(BackupCodec.toContent(snapshot, META), BackupCodec.SCHEMA_VERSION + 1, BackupLimits()) {}
        }
    }
}
