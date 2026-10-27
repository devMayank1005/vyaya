package app.vyaya.core.backup

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class BackupCodecTest {

    private fun failure(text: String, maxChars: Int = BackupCodec.MAX_CHARS): List<String> =
        when (val r = BackupCodec.decode(text, maxChars)) {
            is DecodeResult.Failure -> r.problems
            is DecodeResult.Success -> fail("expected failure but decoded: ${r.snapshot}")
        }

    private fun golden(transform: (String) -> String) = transform(GOLDEN_JSON)

    // --- the format (plan.md Part D), identical on every platform -----------------------------

    @Test
    fun encodes_exactly_the_golden_file() {
        // This same test runs on the JVM (Android) and the iOS simulator in CI: identical bytes on both.
        assertEquals(GOLDEN_JSON, BackupCodec.encode(GOLDEN_SNAPSHOT, META))
    }

    @Test
    fun decodes_the_golden_file() {
        val result = assertIs<DecodeResult.Success>(BackupCodec.decode(GOLDEN_JSON))
        assertEquals(GOLDEN_SNAPSHOT.normalized(), result.snapshot)
        assertEquals(META, result.meta)
    }

    @Test
    fun export_import_export_is_identical_for_any_snapshot() = runTest {
        checkAll(300, Arb.long()) { seed ->
            val snapshot = randomSnapshot(Random(seed))
            val text = BackupCodec.encode(snapshot, META)
            val decoded = assertIs<DecodeResult.Success>(BackupCodec.decode(text), "seed=$seed").snapshot
            assertEquals(snapshot.normalized(), decoded, "seed=$seed")
            assertEquals(text, BackupCodec.encode(decoded, META), "seed=$seed")
        }
    }

    @Test
    fun record_order_does_not_change_the_file() {
        val shuffled = GOLDEN_SNAPSHOT.copy(categories = GOLDEN_SNAPSHOT.categories.reversed())
        assertEquals(BackupCodec.encode(GOLDEN_SNAPSHOT, META), BackupCodec.encode(shuffled, META))
    }

    // --- never throws; every problem is reported --------------------------------------------

    @Test
    fun not_json_or_not_a_vyaya_backup() {
        assertTrue(failure("").isNotEmpty())
        assertTrue(failure("hello").isNotEmpty())
        assertTrue(failure("[]").isNotEmpty())
        assertTrue(failure(golden { it.replace("\"vyaya-backup\"", "\"other-app\"") }).single().contains("not a Vyaya backup"))
    }

    @Test
    fun a_missing_or_odd_header_is_not_a_vyaya_backup() {
        assertTrue(failure("{}").single().contains("not a Vyaya backup"))
        assertTrue(failure("""{"format": 1}""").single().contains("not a Vyaya backup"))
        assertTrue(failure("""{"format": {"a": 1}}""").single().contains("not a Vyaya backup"))
        assertTrue(failure("""{"format": "vyaya-backup"}""").single().contains("no schema version"))
        assertTrue(failure("""{"format": "vyaya-backup", "schemaVersion": "one"}""").single().contains("no schema version"))
        assertTrue(failure("""{"format": "vyaya-backup", "schemaVersion": 1}""").single().contains("damaged"))
    }

    @Test
    fun a_newer_schema_version_is_refused_with_a_clear_message() {
        assertTrue(failure(golden { it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2") }).single().contains("newer version of Vyaya"))
        assertTrue(failure(golden { it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0") }).single().contains("schema version 0"))
    }

    @Test
    fun files_over_the_size_limit_are_refused_unread() {
        assertTrue(failure(GOLDEN_JSON, maxChars = 100).single().contains("too large"))
    }

    @Test
    fun an_unknown_time_zone_is_a_problem_not_a_crash() { // SEC-008
        val problems = failure(golden { it.replaceFirst("\"timezone\": \"Asia/Kolkata\",\n      \"accountId\"", "\"timezone\": \"Mars/Olympus\",\n      \"accountId\"") })
        assertTrue(problems.any { "Mars/Olympus" in it && "00000000-0000-4000-8000-000000000004" in it }, problems.toString())
    }

    @Test
    fun dates_outside_2000_to_2100_are_problems() { // PEN-009
        assertTrue(failure(golden { it.replace("2026-10-26T13:05:00Z", "+999999-01-01T00:00:00Z") }).any { "out of range" in it })
        assertTrue(failure(golden { it.replace("\"date\": \"2026-10-20\"", "\"date\": \"1999-12-31\"") }).any { "out of range" in it })
        assertTrue(failure(golden { it.replace("\"effectiveFrom\": \"2026-04-01\"", "\"effectiveFrom\": \"2201-04-01\"") }).isNotEmpty())
    }

    @Test
    fun every_kind_of_record_reports_a_bad_id() {
        listOf("000000000001" to "account", "000000000002" to "category", "000000000004" to "entry", "000000000005" to "person",
            "000000000006" to "ledger entry", "000000000007" to "recurring rule", "000000000008" to "budget").forEach { (suffix, what) ->
            val broken = golden { it.replaceFirst("\"id\": \"00000000-0000-4000-8000-$suffix\"", "\"id\": \"bad\"") }
            assertTrue(failure(broken).any { it.startsWith("$what: invalid id") }, "$what: ${failure(broken)}")
        }
    }

    @Test
    fun system_ids_can_not_be_stored_as_categories() {
        val broken = golden { it.replace("\"id\": \"00000000-0000-4000-8000-000000000003\"", "\"id\": \"sys.saved\"") }
        assertTrue(failure(broken).any { "system ids are built in" in it })
    }

    @Test
    fun malformed_references_and_settings_are_problems() {
        assertTrue(failure(golden { it.replace("\"accountId\": \"00000000-0000-4000-8000-000000000001\",\n      \"toAccountId\"", "\"accountId\": \"???\",\n      \"toAccountId\"") })
            .any { "unknown account ???" in it })
        assertTrue(failure(golden { it.replace("\"timezone\": \"Asia/Kolkata\",\n    \"alertsEnabled\"", "\"timezone\": \"Nowhere\",\n    \"alertsEnabled\"") })
            .any { "settings: unknown time zone" in it })
        assertTrue(failure(golden { it.replace("\"transitionEnd\": \"2026-04-25\"", "\"transitionEnd\": \"soon\"") })
            .any { "month start" in it })
        assertTrue(failure(golden { it.replace("\"exportedAt\": \"2026-10-27T12:00:00Z\"", "\"exportedAt\": \"yesterday\"") })
            .any { "exportedAt" in it })
    }

    @Test
    fun the_date_range_is_inclusive_of_2000_to_2100_only() {
        assertTrue(failure(golden { it.replace("2026-10-26T13:05:00Z", "1999-12-31T23:59:59Z") }).any { "out of range" in it })
        assertTrue(failure(golden { it.replace("2026-10-26T13:05:00Z", "2101-01-01T00:00:00Z") }).any { "out of range" in it })
        assertTrue(failure(golden { it.replace("\"date\": \"2026-10-20\"", "\"date\": \"2101-01-01\"") }).any { "out of range" in it })
        assertTrue(failure(golden { it.replace("\"date\": \"2026-10-20\"", "\"date\": \"20 Oct\"") }).any { "not a valid date" in it })
        assertIs<DecodeResult.Success>(BackupCodec.decode(golden { it.replace("2026-10-26T13:05:00Z", "2000-01-01T00:00:00Z") }))
        assertIs<DecodeResult.Success>(BackupCodec.decode(golden { it.replace("\"date\": \"2026-10-20\"", "\"date\": \"2100-12-31\"") }))
    }

    @Test
    fun invalid_records_are_reported_with_their_id() {
        val problems = failure(golden { it.replace("\"amountPaise\": 30000", "\"amountPaise\": -5") })
        assertTrue(problems.any { "00000000-0000-4000-8000-000000000006" in it && "positive" in it }, problems.toString())
    }

    @Test
    fun bad_ids_duplicates_and_dangling_references_are_problems() {
        assertTrue(failure(golden { it.replace("00000000-0000-4000-8000-000000000009", "../../etc/passwd") }).any { "id" in it })
        val dup = golden { it.replace("00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000002") }
        assertTrue(failure(dup).any { "duplicate" in it })
        val dangling = golden { it.replace("\"personId\": \"00000000-0000-4000-8000-000000000005\"", "\"personId\": \"00000000-0000-4000-8000-0000000000ff\"") }
        assertTrue(failure(dangling).any { "unknown person" in it })
        assertTrue(failure(golden { it.replace("\"categoryId\": \"sys.saved\"", "\"categoryId\": \"sys.unknown\"") }).any { "unknown category" in it })
    }

    @Test
    fun wrong_types_and_unknown_fields_are_problems() {
        assertTrue(failure(golden { it.replace("\"amountPaise\": 12050", "\"amountPaise\": \"lots\"") }).isNotEmpty())
        assertTrue(failure(golden { it.replace("\"kind\": \"BANK\"", "\"kind\": \"SWISS\"") }).isNotEmpty())
        assertTrue(failure(golden { it.replace("\"currency\": \"INR\"", "\"currency\": \"USD\"") }).any { "currency" in it })
        assertTrue(failure(golden { it.replace("\"deleted\": false\n    }\n  ],\n  \"categories\"", "\"deleted\": false, \"admin\": true\n    }\n  ],\n  \"categories\"") }).isNotEmpty())
    }

    @Test
    fun a_tampered_month_schedule_is_a_problem() {
        assertTrue(failure(golden { it.replace("\"transitionEnd\": \"2026-04-25\"", "\"transitionEnd\": \"2026-04-20\"") }).any { "month start" in it })
    }

    @Test
    fun the_problem_list_is_capped() {
        val many = golden { it.replace("\"amountPaise\": 12050", "\"amountPaise\": 0") }
        val manyEntries = many.replace(Regex("\"entries\": \\[(.*?)\\n  \\],", RegexOption.DOT_MATCHES_ALL)) { m ->
            val one = m.groupValues[1]
            "\"entries\": [" + (1..50).joinToString(",") { i -> one.replace("000000000004", "0000000001" + i.toString().padStart(2, '0')) } + "\n  ],"
        }
        val problems = failure(manyEntries)
        assertEquals(BackupCodec.MAX_PROBLEMS + 1, problems.size)
        assertTrue(problems.last().contains("more"))
    }
}
