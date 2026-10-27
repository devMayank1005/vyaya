package app.vyaya.core.backup

import app.vyaya.core.model.Money
import app.vyaya.core.model.Split
import app.vyaya.core.model.SystemCategories
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Backup format v2: one compact JSON object per line, ending with a SHA-256 trailer. */
class BackupV2Test {

    private val goldenBody = listOf(
        """{"t":"header","format":"vyaya-backup","schemaVersion":2,"appVersion":"1.0.0","createdOn":"android","exportedAt":"2026-10-27T12:00:00Z","currency":"INR","settings":{"monthStartDay":1,"startDayChanges":[{"effectiveFrom":"2026-04-01","transitionEnd":"2026-04-25","startDay":25}],"timezone":"Asia/Kolkata","alertsEnabled":true},"counts":{"accounts":1,"categories":2,"entries":1,"recurring":1,"people":1,"ledger":1,"budgets":1,"merchantRules":1}}""",
        """{"t":"account","id":"00000000-0000-4000-8000-000000000001","name":"HDFC Salary","kind":"BANK","mask":"1234","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"category","id":"00000000-0000-4000-8000-000000000002","name":"Food","kind":"EXPENSE","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"category","id":"00000000-0000-4000-8000-000000000003","name":"Groceries","kind":"EXPENSE","updatedAt":"2026-10-27T10:00:00Z","deleted":true}""",
        """{"t":"entry","id":"00000000-0000-4000-8000-000000000004","type":"EXPENSE","amountPaise":12050,"occurredAt":"2026-10-26T13:05:00Z","timezone":"Asia/Kolkata","accountId":"00000000-0000-4000-8000-000000000001","toAccountId":null,"categoryId":"00000000-0000-4000-8000-000000000002","payee":"Swiggy","note":"lunch","tags":["office"],"source":"NOTIFICATION","upiRef":"429112345678","splits":[{"categoryId":"00000000-0000-4000-8000-000000000002","amountPaise":10050},{"categoryId":"sys.saved","amountPaise":2000}],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"recurring","id":"00000000-0000-4000-8000-000000000007","type":"EXPENSE","amountPaise":11900,"dayOfMonth":5,"categoryId":"00000000-0000-4000-8000-000000000002","accountId":"00000000-0000-4000-8000-000000000001","note":"Spotify","paused":false,"startsOn":"2026-01-05","lastGeneratedOn":"2026-10-05","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"person","id":"00000000-0000-4000-8000-000000000005","name":"Rahul","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"ledger","id":"00000000-0000-4000-8000-000000000006","personId":"00000000-0000-4000-8000-000000000005","direction":"LENT","amountPaise":30000,"date":"2026-10-20","note":"Dinner","settled":false,"updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"budget","id":"00000000-0000-4000-8000-000000000008","categoryId":"00000000-0000-4000-8000-000000000002","limitPaise":500000,"updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
        """{"t":"merchantRule","id":"00000000-0000-4000-8000-000000000009","payee":"swiggy","categoryId":"00000000-0000-4000-8000-000000000002","updatedAt":"2026-10-27T10:00:00Z","deleted":false}""",
    )

    /** Builds a v2 file from body lines with a correct trailer, so a test can isolate one rule from the checksum. */
    private fun signed(body: List<String>, records: Int = body.size - 1): String {
        val sha = Sha256().apply { body.forEach { update(it + "\n") } }.hexDigest()
        return (body + """{"t":"end","records":$records,"sha256":"$sha"}""").joinToString("\n") + "\n"
    }

    private fun decode(text: String, now: kotlin.time.Instant = NOW, limits: BackupLimits = BackupLimits()) =
        BackupCodec.decode(text, now, limits)

    private fun problems(text: String, now: kotlin.time.Instant = NOW, limits: BackupLimits = BackupLimits()): List<String> =
        when (val r = decode(text, now, limits)) {
            is DecodeResult.Failure -> r.problems
            is DecodeResult.Success -> fail("expected failure but decoded")
        }

    // --- the format ----------------------------------------------------------------------------

    @Test
    fun encodes_exactly_the_golden_lines_and_a_checksum_of_them() {
        // Runs on the JVM (Android) and the iOS simulator in CI: identical bytes on both.
        assertEquals(signed(goldenBody), BackupCodec.encode(GOLDEN_SNAPSHOT, META))
    }

    @Test
    fun decodes_the_golden_file() {
        val result = assertIs<DecodeResult.Success>(decode(signed(goldenBody)))
        assertEquals(GOLDEN_SNAPSHOT.normalized(), result.snapshot)
        assertEquals(META, result.meta)
    }

    @Test
    fun export_import_export_is_identical_for_any_snapshot() = runTest {
        checkAll(300, Arb.long()) { seed ->
            val snapshot = randomSnapshot(Random(seed))
            val text = BackupCodec.encode(snapshot, META_LATE)
            val decoded = assertIs<DecodeResult.Success>(decode(text, now = LATE), "seed=$seed").snapshot
            assertEquals(snapshot.normalized(), decoded, "seed=$seed")
            assertEquals(text, BackupCodec.encode(decoded, META_LATE), "seed=$seed")
        }
    }

    @Test
    fun streaming_writes_line_by_line_and_reads_from_a_sequence_of_lines() {
        val lines = mutableListOf<String>()
        assertIs<EncodeResult.Success>(BackupCodec.write(GOLDEN_SNAPSHOT, META) { lines += it })
        assertEquals(goldenBody.size + 1, lines.size)
        assertTrue(lines.none { '\n' in it })
        assertIs<DecodeResult.Success>(BackupCodec.read(lines.asSequence(), NOW))
    }

    @Test
    fun v1_files_still_restore_and_any_v1_file_gives_the_same_data_as_v2() = runTest { // migration
        assertEquals(GOLDEN_SNAPSHOT.normalized(), assertIs<DecodeResult.Success>(decode(GOLDEN_JSON)).snapshot)
        checkAll(100, Arb.long()) { seed ->
            val snapshot = randomSnapshot(Random(seed))
            val fromV1 = assertIs<DecodeResult.Success>(decode(BackupCodec.encodeV1(snapshot, META_LATE), now = LATE)).snapshot
            val fromV2 = assertIs<DecodeResult.Success>(decode(BackupCodec.encode(snapshot, META_LATE), now = LATE)).snapshot
            assertEquals(fromV1, fromV2)
        }
    }

    // --- damage is detected (DATA-003) -------------------------------------------------------

    @Test
    fun one_changed_digit_is_detected_by_the_checksum() {
        val edited = signed(goldenBody).replace("\"amountPaise\":30000", "\"amountPaise\":90000")
        assertTrue(problems(edited).single().contains("damaged or edited"), problems(edited).toString())
    }

    @Test
    fun a_cut_off_file_is_detected() {
        val full = signed(goldenBody)
        val withoutTrailer = full.lines().dropLast(2).joinToString("\n") // drops the trailer and final newline
        assertTrue(problems(withoutTrailer).single().contains("cut off"))
        assertTrue(problems(full.take(full.length / 2)).isNotEmpty())
    }

    @Test
    fun a_missing_record_line_is_detected_even_with_a_valid_checksum() {
        val body = goldenBody.filterNot { it.startsWith("""{"t":"person"""") }
        assertTrue(problems(signed(body)).any { "people" in it && "count" in it })
        assertTrue(problems(signed(goldenBody, records = 8)).any { "records" in it })
    }

    @Test
    fun anything_after_the_last_line_is_refused() {
        assertTrue(problems(signed(goldenBody) + goldenBody[6] + "\n").any { "after the end" in it })
    }

    @Test
    fun unknown_or_misplaced_lines_are_refused() {
        assertTrue(problems(signed(goldenBody.take(1) + """{"t":"admin","id":"x"}""" + goldenBody.drop(1))).isNotEmpty())
        assertTrue(problems(signed(goldenBody.drop(1))).single().contains("not a Vyaya backup"))
        assertTrue(problems(signed(goldenBody.take(2) + "not json" + goldenBody.drop(2))).any { "Line 3" in it })
    }

    @Test
    fun a_newer_schema_is_refused_with_a_clear_message() {
        val newer = goldenBody.toMutableList().apply { this[0] = this[0].replace("\"schemaVersion\":2", "\"schemaVersion\":3") }
        assertTrue(problems(signed(newer)).single().contains("newer version of Vyaya"))
    }

    // --- FLAW-007 / FLAW-006 ---------------------------------------------------------------------

    @Test
    fun a_byte_order_mark_and_windows_line_endings_are_accepted() { // FLAW-007
        assertIs<DecodeResult.Success>(decode("\uFEFF" + signed(goldenBody)))
        assertIs<DecodeResult.Success>(decode(signed(goldenBody).replace("\n", "\r\n")))
        assertIs<DecodeResult.Success>(decode("\uFEFF" + GOLDEN_JSON))
    }

    @Test
    fun duplicate_keys_are_refused_even_with_a_valid_checksum() { // FLAW-006
        val dup = goldenBody.toMutableList().apply { this[7] = this[7].replace("\"amountPaise\":30000", "\"amountPaise\":30000,\"amountPaise\":99999999") }
        assertTrue(problems(signed(dup)).any { "duplicate key" in it && "amountPaise" in it }, problems(signed(dup)).toString())
        val escaped = goldenBody.toMutableList().apply { this[7] = this[7].replace("\"note\":\"Dinner\"", "\"note\":\"Dinner\",\"\\u006eote\":\"x\"") }
        assertTrue(problems(signed(escaped)).any { "duplicate key" in it }, "a key spelled with \\u escapes is the same key")
        val dupV1 = GOLDEN_JSON.replace("\"amountPaise\": 30000", "\"amountPaise\": 30000, \"amountPaise\": 99999999")
        assertTrue(problems(dupV1).any { "duplicate key" in it }, "v1 files are checked too")
    }

    @Test
    fun keys_that_only_look_alike_are_fine() {
        // Same key name in different objects, and keys inside string values, are not duplicates.
        assertIs<DecodeResult.Success>(decode(signed(goldenBody.toMutableList().apply { this[4] = this[4].replace("\"note\":\"lunch\"", "\"note\":\"\\\"note\\\": x, {\\\"id\\\": [\"") })))
    }

    // --- SEC-005 ------------------------------------------------------------------------------------

    @Test
    fun records_changed_after_the_export_time_are_refused() { // SEC-005
        val future = goldenBody.toMutableList().apply { this[6] = this[6].replace("2026-10-27T10:00:00Z", "2099-12-31T00:00:00Z") }
        assertTrue(problems(signed(future)).any { "person" in it && "after the backup was made" in it }, problems(signed(future)).toString())
        val created = goldenBody.toMutableList().apply { this[4] = this[4].replace("\"createdAt\":\"2026-10-27T10:00:00Z\"", "\"createdAt\":\"2026-10-28T10:00:00Z\"") }
        assertTrue(problems(signed(created)).any { "entry" in it && "after the backup was made" in it })
    }

    @Test
    fun a_backup_from_the_future_is_refused() { // SEC-005: phone clock far ahead when exporting
        assertTrue(problems(signed(goldenBody), now = kotlin.time.Instant.parse("2026-10-20T00:00:00Z")).any { "in the future" in it })
        assertIs<DecodeResult.Success>(decode(signed(goldenBody), now = kotlin.time.Instant.parse("2026-10-27T00:00:00Z")), "a few hours of clock difference is fine")
    }

    // --- limits shared by writer and reader (DATA-001, PEN-002) ----------------------------------

    @Test
    fun the_writer_refuses_what_the_reader_would_refuse() {
        val limits = BackupLimits(maxRecords = 3)
        assertTrue((BackupCodec.write(GOLDEN_SNAPSHOT, META, limits) {} as EncodeResult.Failure).reason.contains("records"))
        assertTrue(problems(signed(goldenBody), limits = limits).any { "records" in it })

        val manySplits = GOLDEN_SNAPSHOT.entries.single().let { e ->
            e.copy(amount = Money(5000), splits = List(5000) { Split(SystemCategories.SAVED.id, Money(1)) })
        }
        val big = GOLDEN_SNAPSHOT.copy(entries = listOf(manySplits))
        assertTrue((BackupCodec.write(big, META) {} as EncodeResult.Failure).reason.contains("too long"))
        val longLine = goldenBody.toMutableList().apply { this[4] = this[4].replace("\"note\":\"lunch\"", "\"note\":\"" + "x".repeat(70_000) + "\"") }
        assertTrue(problems(signed(longLine)).any { "too long" in it })
    }

    @Test
    fun every_kind_of_malformed_header_is_reported() {
        fun header(edit: (String) -> String) = signed(goldenBody.toMutableList().apply { this[0] = edit(this[0]) })
        assertTrue(problems(signed(goldenBody), limits = BackupLimits(maxLineChars = 100)).single().contains("Line 1 is too long"))
        assertTrue(problems(header { it.replace("\"currency\":\"INR\"", "\"currency\":\"INR\",\"currency\":\"USD\"") }).single().contains("duplicate key"))
        assertTrue(problems(header { it.replace("\"vyaya-backup\"", "\"other\"") }).single().contains("no header"))
        assertTrue(problems(header { it.replace("\"schemaVersion\":2,", "") }).single().contains("no schema version"))
        assertTrue(problems(header { it.replace("\"schemaVersion\":2", "\"schemaVersion\":1") }).single().contains("Unsupported backup schema version 1"))
        assertTrue(problems(header { it.replace("\"appVersion\":\"1.0.0\",", "") }).single().contains("Line 1 is damaged"))
        assertTrue(problems("[1,2]\n").single().contains("no header"))
        assertTrue(problems("{\"t\":\n").single().contains("not valid JSON"))
    }

    @Test
    fun a_damaged_line_of_any_record_type_is_reported() {
        for (i in 1 until goldenBody.size) {
            val body = goldenBody.toMutableList().apply { this[i] = this[i].replace("\"deleted\":", "\"deleted\":\"maybe\",\"x\":") }
            assertTrue(problems(signed(body)).single().contains("Line ${i + 1} is damaged"), "line ${i + 1}: ${problems(signed(body))}")
        }
        val notAnObject = signed(goldenBody.take(1) + "[1,2]" + goldenBody.drop(1))
        assertTrue(problems(notAnObject).single().contains("Line 2 is damaged"))
        val badTrailer = signed(goldenBody).lines().dropLast(2).joinToString("\n") + "\n{\"t\":\"end\",\"records\":\"many\"}\n"
        assertTrue(problems(badTrailer).single().contains("last line"))
    }

    @Test
    fun encode_refuses_instead_of_writing_an_unrestorable_file() {
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(GOLDEN_SNAPSHOT, META, BackupLimits(maxLineChars = 50)) }
        val tooLongHeader = BackupCodec.write(GOLDEN_SNAPSHOT, META, BackupLimits(maxLineChars = 300)) {}
        assertTrue((tooLongHeader as EncodeResult.Failure).reason.startsWith("The settings"))
    }

    @Test
    fun empty_input_is_not_a_backup() {
        assertTrue(problems("").single().contains("empty"))
        assertTrue(problems("\n\n").single().contains("empty"))
    }
}
