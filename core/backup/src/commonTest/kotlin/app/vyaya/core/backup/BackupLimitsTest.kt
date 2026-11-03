package app.vyaya.core.backup

import app.vyaya.core.model.Sha256
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Money
import app.vyaya.core.model.Person
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Split
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/** Limits that keep a crafted file from exhausting memory (PEN-002, PEN-003) and the clock message (FLAW-004). */
class BackupLimitsTest {

    private fun problems(text: String, now: Instant = NOW, limits: BackupLimits = BackupLimits()): List<String> =
        when (val r = BackupCodec.decode(text, now, limits)) {
            is DecodeResult.Failure -> r.problems
            is DecodeResult.Success -> fail("expected failure but decoded")
        }

    // --- PEN-002: a line limit close to what real records need ----------------------------------

    /** Every text field at its maximum length, made of a character JSON must escape as six characters. */
    private fun largestValidSnapshot(): Snapshot {
        fun worst(n: Int) = "\u0001".repeat(n)
        val big = Money(Long.MAX_VALUE / 2)
        val categories = List(Entry.MAX_SPLITS) { Category(EntityId.random(), worst(100), CategoryKind.EXPENSE, T, false) }
        val account = Account(EntityId.random(), worst(100), AccountKind.CARD, "123456", T, false)
        val person = Person(EntityId.random(), worst(100), T, false)
        val splitAmount = Money(Long.MAX_VALUE / 2 / Entry.MAX_SPLITS)
        val entry = Entry(
            EntityId.random(), EntryType.TRANSFER, Money(splitAmount.paise * Entry.MAX_SPLITS), CapturedAt(T, IST),
            account.id, account.id, categories[0].id, worst(100), worst(500), List(Entry.MAX_TAGS) { worst(Entry.MAX_TAG) },
            EntrySource.SCREENSHOT, worst(64), categories.map { Split(it.id, splitAmount) }, EntityId.random(), T, T, true,
        )
        return GOLDEN_SNAPSHOT.copy(
            accounts = listOf(account),
            categories = categories,
            entries = listOf(entry, entry.copy(id = entry.linkedEntryId!!, linkedEntryId = entry.id)),
            recurring = listOf(RecurringRule(EntityId.random(), EntryType.INCOME, big, 31, categories[0].id, account.id, worst(500), true,
                LocalDate(2026, 1, 31), T, true)),
            people = listOf(person),
            ledger = listOf(LedgerEntry(EntityId.random(), person.id, LedgerDirection.BORROWED, big, LocalDate(2026, 10, 1), worst(500), true, T, true)),
            budgets = emptyList(),
            merchantRules = listOf(MerchantRule(EntityId.random(), "m".repeat(100), categories[0].id, T, true)),
        )
    }

    @Test
    fun the_largest_valid_records_fit_a_line_and_the_line_limit_is_tight() { // PEN-002
        val limits = BackupLimits()
        val lines = mutableListOf<String>()
        assertIs<EncodeResult.Success>(BackupCodec.write(largestValidSnapshot(), META, limits) { lines += it })
        val longest = lines.maxOf { it.length }
        assertTrue(longest <= limits.maxLineChars, "longest line $longest > ${limits.maxLineChars}")
        // A crafted line can't be much bigger than the biggest real one: the limit is not 100 times what's needed.
        assertTrue(limits.maxLineChars <= 16_384, "line limit ${limits.maxLineChars} is far above the largest real record ($longest)")
        assertIs<DecodeResult.Success>(BackupCodec.read(lines.asSequence(), NOW, limits))
    }

    // --- PEN-002: a total size cap, shared by writer and reader ---------------------------------------

    @Test
    fun the_size_cap_fits_ten_years_of_a_heavy_user_but_no_more_than_128_mb() { // PEN-002 (v2 fixes report #2)
        // A valid file at the old 256 MB cap needed about 371 MB to read; 200,000 real entries are about 96 MB.
        assertTrue(BackupLimits().maxTotalChars <= 128L * 1024 * 1024, "cap ${BackupLimits().maxTotalChars}")
        assertTrue(BackupLimits().maxTotalChars >= 112L * 1024 * 1024, "room above 96 MB for 200,000 entries")
    }

    @Test
    fun the_total_size_is_capped_for_writer_and_reader_alike() { // PEN-002
        val text = BackupCodec.encode(GOLDEN_SNAPSHOT, META)
        val exact = BackupLimits(maxTotalChars = text.length.toLong())
        val oneLess = BackupLimits(maxTotalChars = text.length.toLong() - 1)
        assertIs<EncodeResult.Success>(BackupCodec.write(GOLDEN_SNAPSHOT, META, exact) {})
        assertIs<DecodeResult.Success>(BackupCodec.decode(text, NOW, exact))
        val refused = assertIs<EncodeResult.Failure>(BackupCodec.write(GOLDEN_SNAPSHOT, META, oneLess) {})
        assertTrue("too large" in refused.reason, refused.reason)
        assertTrue(problems(text, limits = oneLess).single().contains("too large"))
    }

    // --- PEN-002 / PEN-003: each line is checked as it is read --------------------------------------

    @Test
    fun a_crafted_file_stops_being_read_once_the_problem_list_is_full() { // PEN-002
        val bad = 3_000
        val header = """{"t":"header","format":"vyaya-backup","schemaVersion":2,"appVersion":"1.0.0","createdOn":"android","exportedAt":"2026-10-27T12:00:00Z","currency":"INR","settings":{"monthStartDay":1,"startDayChanges":[],"timezone":"Asia/Kolkata","alertsEnabled":true},"counts":{"accounts":0,"categories":0,"entries":$bad,"recurring":0,"people":0,"ledger":0,"budgets":0,"merchantRules":0}}"""
        val note = "x".repeat(10_000) // within the line limit, but 20 times the note limit
        fun entry(i: Int) = """{"t":"entry","id":"00000000-0000-4000-8000-${i.toString().padStart(12, '0')}","type":"EXPENSE","amountPaise":100,"occurredAt":"2026-10-26T13:05:00Z","timezone":"Asia/Kolkata","accountId":null,"toAccountId":null,"categoryId":null,"payee":null,"note":"$note","tags":[],"source":"MANUAL","upiRef":null,"splits":[],"linkedEntryId":null,"createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:00:00Z","deleted":false}"""
        var pulled = 0
        val lines = sequence {
            yield(header)
            for (i in 1..bad) yield(entry(i))
            yield("""{"t":"end","records":$bad,"sha256":"0"}""")
        }.map { pulled++; it }

        val result = assertIs<DecodeResult.Failure>(BackupCodec.read(lines, NOW))
        // FLAW-004: the reader stopped before the checksum, so the first line says what the user needs to know.
        assertTrue(result.problems.first().startsWith("This file looks damaged or edited"), result.problems.first())
        assertTrue(result.problems[1].contains("note"), result.problems[1])
        assertEquals(BackupCodec.MAX_PROBLEMS + 2, result.problems.size)
        assertTrue(result.problems.last().contains("stopped reading"), result.problems.last())
        assertTrue(pulled <= BackupCodec.MAX_PROBLEMS + 2, "read $pulled lines of ${bad + 2}")
    }

    @Test
    fun records_in_an_unexpected_order_still_restore() { // references are resolved after reading
        val lines = BackupCodec.encode(GOLDEN_SNAPSHOT, META).trimEnd().lines()
        val body = lines.dropLast(1).toMutableList()
        val ledger = body.indexOfFirst { it.startsWith("""{"t":"ledger"""") }
        val account = body.indexOfFirst { it.startsWith("""{"t":"account"""") }
        body.add(1, body.removeAt(ledger)) // the ledger line now comes before its person
        body.add(body.removeAt(account + 1)) // and the account after the entries that use it
        val sha = Sha256().apply { body.forEach { update(it + "\n") } }.hexDigest()
        val text = (body + """{"t":"end","records":${body.size - 1},"sha256":"$sha"}""").joinToString("\n")
        assertEquals(GOLDEN_SNAPSHOT.normalized(), assertIs<DecodeResult.Success>(BackupCodec.decode(text, NOW)).snapshot)
    }

    // --- FLAW-004: the restoring phone's clock is behind ------------------------------------------------

    @Test
    fun a_backup_from_the_future_tells_the_user_to_check_this_phones_date_and_time() { // FLAW-004
        val text = BackupCodec.encode(GOLDEN_SNAPSHOT, META) // made on 27 Oct 2026
        val phoneBehind = Instant.parse("2026-10-24T09:00:00Z")
        val message = problems(text, now = phoneBehind).single()
        assertTrue("2026-10-27" in message && "2026-10-24" in message, message)
        assertTrue("check" in message.lowercase() && "date and time" in message, message)
        assertTrue("this phone" in message, message)
    }
}
