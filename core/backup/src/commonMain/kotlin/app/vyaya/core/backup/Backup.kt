package app.vyaya.core.backup

import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Budget
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Generation
import app.vyaya.core.model.LedgerDirection
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Money
import app.vyaya.core.model.MonthSchedule
import app.vyaya.core.model.Person
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Settings
import app.vyaya.core.model.Split
import app.vyaya.core.model.StartDayChange
import app.vyaya.core.model.SystemCategories
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Everything Vyaya stores: what a backup contains and what a restore produces. */
data class Snapshot(
    val settings: Settings,
    val accounts: List<Account>,
    val categories: List<Category>,
    val entries: List<Entry>,
    val recurring: List<RecurringRule>,
    val people: List<Person>,
    val ledger: List<LedgerEntry>,
    val budgets: List<Budget>,
    val merchantRules: List<MerchantRule>,
) {
    /** Every list sorted by id, so the same data always gives the same file. */
    fun normalized(): Snapshot = copy(
        accounts = accounts.sortedBy { it.id.value },
        categories = categories.sortedBy { it.id.value },
        entries = entries.sortedBy { it.id.value },
        recurring = recurring.sortedBy { it.id.value },
        people = people.sortedBy { it.id.value },
        ledger = ledger.sortedBy { it.id.value },
        budgets = budgets.sortedBy { it.id.value },
        merchantRules = merchantRules.sortedBy { it.id.value },
    )

    companion object {
        fun empty(): Snapshot = Snapshot(
            Settings.default(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
    }
}

/** Who made the file and when. [createdOn] is the platform ("android" or "ios"). */
data class ExportMeta(val appVersion: String, val createdOn: String, val exportedAt: Instant)

sealed interface DecodeResult {
    data class Success(val snapshot: Snapshot, val meta: ExportMeta) : DecodeResult

    /**
     * Human-readable problems, at most [BackupCodec.MAX_PROBLEMS] plus a "… more" line. Nothing is restored.
     *
     * [clockBehind]: the only problem is that the backup looks made in the future (this phone's clock is behind,
     * or the exporting phone's was ahead). The app may then offer "Restore anyway" and read again with
     * `restoreAnyway = true` (FLAW-001).
     */
    data class Failure(val problems: List<String>, val clockBehind: Boolean = false) : DecodeResult
}

/**
 * Reads and writes the Vyaya backup file (writes schema v3; reads v1, v2 and v3), byte-for-byte identical on
 * Android and iOS.
 *
 * [decode] never throws. Anything wrong in the file becomes a reported problem and nothing is restored:
 * wrong format, a newer schema, oversized input, bad ids, unknown time zones (SEC-008), dates outside
 * 2000–2100 (PEN-009), invalid records, duplicates and dangling references.
 */
object BackupCodec {

    const val FORMAT = "vyaya-backup"

    /** The schema this app writes. It reads every earlier one (v1 and v2 are migrated when read). */
    const val SCHEMA_VERSION = 3
    internal const val V1 = 1
    const val MAX_PROBLEMS = 20

    @OptIn(ExperimentalSerializationApi::class)
    internal val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /**
     * Writes the backup (schema v2) line by line to [sink]; each call is one line without its newline.
     *
     * Refuses, with [EncodeResult.Failure], anything the reader would refuse ([limits] are shared), so the app
     * never writes a backup it can't restore (DATA-001). On failure, part of the file may already have been
     * written, so callers write to a temporary file and keep it only on [EncodeResult.Success].
     */
    fun write(snapshot: Snapshot, meta: ExportMeta, limits: BackupLimits = BackupLimits(), sink: (String) -> Unit): EncodeResult {
        // DATA-001: refuse here, with the reader's own words, anything a restore would refuse.
        val problems = Consistency.problems(snapshot, meta.exportedAt)
        if (problems.isNotEmpty()) {
            val shown = problems.take(MAX_PROBLEMS).joinToString("; ")
            val more = if (problems.size > MAX_PROBLEMS) "; … and ${problems.size - MAX_PROBLEMS} more problems" else ""
            return EncodeResult.Failure("This data can't be backed up because it couldn't be restored: $shown$more")
        }
        return BackupLines.write(toContent(snapshot, meta), SCHEMA_VERSION, limits, sink)
    }

    /** Reads a backup from its lines (v2, or a v1 file from an earlier version). Never throws. */
    fun read(lines: Sequence<String>, now: Instant, limits: BackupLimits = BackupLimits(), restoreAnyway: Boolean = false): DecodeResult =
        BackupLines.read(lines, now, limits, restoreAnyway)

    /** The whole v2 file as one string (convenience for tests and small backups). */
    fun encode(snapshot: Snapshot, meta: ExportMeta, limits: BackupLimits = BackupLimits()): String {
        val out = StringBuilder()
        val result = write(snapshot, meta, limits) { out.append(it).append('\n') }
        require(result is EncodeResult.Success) { (result as EncodeResult.Failure).reason }
        return out.toString()
    }

    fun decode(text: String, now: Instant, limits: BackupLimits = BackupLimits(), restoreAnyway: Boolean = false): DecodeResult =
        read(text.lineSequence(), now, limits, restoreAnyway)

    /** Writes the old v2 format. Kept only to produce migration test fixtures. */
    internal fun encodeV2(snapshot: Snapshot, meta: ExportMeta): String {
        val out = StringBuilder()
        val result = BackupLines.write(toContent(snapshot, meta), 2, BackupLimits()) { out.append(it).append('\n') }
        check(result is EncodeResult.Success) { (result as EncodeResult.Failure).reason }
        return out.toString()
    }

    /** Writes the old v1 format. Kept only to produce migration test fixtures; the app always writes v2. */
    internal fun encodeV1(snapshot: Snapshot, meta: ExportMeta): String =
        json.encodeToString(BackupFileV1.serializer(), toContent(snapshot, meta).toV1File())

    internal fun toContent(snapshot: Snapshot, meta: ExportMeta): BackupContent {
        val s = snapshot.normalized()
        return BackupContent(
            appVersion = meta.appVersion,
            createdOn = meta.createdOn,
            exportedAt = meta.exportedAt.toString(),
            currency = s.settings.currency,
            settings = SettingsV1(
                monthStartDay = s.settings.monthSchedule.initialStartDay,
                startDayChanges = s.settings.monthSchedule.changes.map {
                    StartDayChangeV1(it.effectiveFrom.toString(), it.transitionEnd.toString(), it.startDay)
                },
                timezone = s.settings.homeTimeZone.id,
                alertsEnabled = s.settings.alertsEnabled,
            ),
            accounts = s.accounts.map { AccountV1(it.id.value, it.name, it.kind.name, it.mask, it.updatedAt.toString(), it.deleted) },
            categories = s.categories.map { CategoryV1(it.id.value, it.name, it.kind.name, it.updatedAt.toString(), it.deleted) },
            entries = s.entries.map { e ->
                EntryV3(
                    e.id.value, e.type.name, e.amount.paise, e.occurredAt.instant.toString(), e.occurredAt.timeZone.id,
                    e.accountId?.value, e.toAccountId?.value, e.categoryId?.value, e.payee, e.note, e.tags, e.source.name,
                    e.upiRef, e.splits.map { SplitV1(it.categoryId.value, it.amount.paise) }, e.linkedEntryId?.value,
                    e.createdAt.toString(), e.updatedAt.toString(), e.deleted,
                    e.generatedBy?.let { GenerationV3(it.ruleId.value, it.month.toString()) },
                )
            },
            recurring = s.recurring.map { r ->
                RecurringV3(
                    r.id.value, r.type.name, r.amount.paise, r.dayOfMonth, r.categoryId?.value, r.accountId?.value, r.note,
                    r.paused, r.startsOn.toString(), r.updatedAt.toString(), r.deleted,
                )
            },
            people = s.people.map { PersonV1(it.id.value, it.name, it.updatedAt.toString(), it.deleted) },
            ledger = s.ledger.map { l ->
                LedgerV1(l.id.value, l.personId.value, l.direction.name, l.amount.paise, l.date.toString(), l.note, l.settled,
                    l.updatedAt.toString(), l.deleted)
            },
            budgets = s.budgets.map { BudgetV1(it.id.value, it.categoryId.value, it.limit.paise, it.updatedAt.toString(), it.deleted) },
            merchantRules = s.merchantRules.map { MerchantRuleV1(it.id.value, it.payee, it.categoryId.value, it.updatedAt.toString(), it.deleted) },
        )
    }

    internal fun decodeV1(text: String, now: Instant, maxChars: Int, restoreAnyway: Boolean = false): DecodeResult {
        if (text.length > maxChars) return fail("Backup file is too large (more than $maxChars characters)")
        JsonKeys.firstDuplicate(text)?.let { return fail("The backup file has a duplicate key '$it' (it was edited)") }
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return fail("This is not a Vyaya backup file (it is not valid JSON)")
        }
        if (root !is JsonObject || (root["format"] as? JsonPrimitive)?.content != FORMAT) {
            return fail("This is not a Vyaya backup file")
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return fail("This is not a Vyaya backup file (no schema version)")
        if (version > SCHEMA_VERSION) {
            return fail("This backup was made by a newer version of Vyaya (schema $version). Update the app to restore it.")
        }
        if (version != V1) return fail("Unsupported backup schema version $version")
        val file = try {
            json.decodeFromJsonElement(BackupFileV1.serializer(), root)
        } catch (e: Exception) {
            return fail("The backup file is damaged or was edited: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
        }
        return Reader.read(file.toContent(), now, restoreAnyway)
    }

    internal fun fail(problem: String) = DecodeResult.Failure(listOf(problem))
}

/**
 * Turns backup records into validated domain records one at a time, collecting problems instead of throwing.
 *
 * Each record is converted as soon as it is read and only the domain record is kept (PEN-003); a record that
 * fails validation is dropped straight away, so oversized fields are never held (PEN-002). References between
 * records are checked in [finish], so the order of records in the file doesn't matter.
 */
internal class Reader(private val now: Instant, private val restoreAnyway: Boolean = false) {

    private val problems = mutableListOf<String>()

    private val earliest = Instant.parse("2000-01-01T00:00:00Z")
    private val latest = Instant.parse("2101-01-01T00:00:00Z")
    private val firstDay = LocalDate(2000, 1, 1)
    private val lastDay = LocalDate(2100, 12, 31)

    private var settings: Settings? = null

    /** The backup's export time is after this phone's time (FLAW-001); [clockProblem] is the message if refused. */
    private var clockProblem: String? = null

    /** Restoring anyway: every date after [now] is reset to [now] (FLAW-001). */
    private var resetFutureDates = false
    private var homeZone: TimeZone? = null
    private var exportedAt: Instant? = null
    private val accounts = mutableListOf<Account>()
    private val categories = mutableListOf<Category>()
    private val entries = mutableListOf<Entry>()
    private val recurring = mutableListOf<RecurringRule>()
    private val people = mutableListOf<Person>()
    private val ledger = mutableListOf<LedgerEntry>()
    private val budgets = mutableListOf<Budget>()
    private val merchantRules = mutableListOf<MerchantRule>()

    /** More problems than the user will be shown: reading further only costs time and memory. */
    val full: Boolean get() = problems.size > BackupCodec.MAX_PROBLEMS

    /** The first [BackupCodec.MAX_PROBLEMS] problems and a line saying reading stopped at [line]. */
    fun stoppedAt(line: Int): DecodeResult.Failure = DecodeResult.Failure(
        // FLAW-004: reading stopped before the checksum was reached, so say plainly what this most likely means.
        listOf("This file looks damaged or edited: it has too many problems to restore.") +
            problems.take(BackupCodec.MAX_PROBLEMS) + "… and more problems; stopped reading at line $line",
    )

    fun header(currency: String, exportedAt: String, s: SettingsV1) {
        if (currency != "INR") problems += "Unsupported currency '$currency' (only INR)"
        val zone = try { TimeZone.of(s.timezone) } catch (e: Exception) { problems += "settings: unknown time zone '${s.timezone}'"; null }
        val at = instant(exportedAt, "exportedAt")
        if (at != null && at > now + 1.days) { // FLAW-004: most often this phone's clock is behind
            if (restoreAnyway) {
                resetFutureDates = true // FLAW-001: the user confirmed
            } else {
                val shownIn = zone ?: TimeZone.UTC
                val message = "This backup was made on ${at.toLocalDateTime(shownIn).date}, which is later than this phone's date " +
                    "(${now.toLocalDateTime(shownIn).date}). Check that this phone's date and time are correct, then try again."
                problems += message
                clockProblem = message
            }
        }
        this.exportedAt = at
        homeZone = zone
        settings = settings(currency, zone, s)
    }

    fun account(a: AccountV1) {
        val id = id(a.id, "account") ?: return
        record("account $id") { Account(id, a.name, enum<AccountKind>(a.kind, "kind"), a.mask, instant(a.updatedAt, "updatedAt")!!, a.deleted) }
            ?.also { notAfterExport("account", id, it.updatedAt) }?.let(accounts::add)
    }

    fun category(c: CategoryV1) {
        val id = id(c.id, "category") ?: return
        if (id.value.startsWith("sys.")) { problems += "category $id: system ids are built in and can't be stored"; return }
        record("category $id") { Category(id, c.name, enum<CategoryKind>(c.kind, "kind"), instant(c.updatedAt, "updatedAt")!!, c.deleted) }
            ?.also { notAfterExport("category", id, it.updatedAt) }?.let(categories::add)
    }

    fun person(p: PersonV1) {
        val id = id(p.id, "person") ?: return
        record("person $id") { Person(id, p.name, instant(p.updatedAt, "updatedAt")!!, p.deleted) }
            ?.also { notAfterExport("person", id, it.updatedAt) }?.let(people::add)
    }

    fun entry(e: EntryV3) {
        val id = id(e.id, "entry") ?: return
        record("entry $id") {
            val zone = try { TimeZone.of(e.timezone) } catch (x: Exception) { throw IllegalArgumentException("unknown time zone '${e.timezone}'") }
            Entry(
                id, enum<EntryType>(e.type, "type"), Money(e.amountPaise), CapturedAt(instant(e.occurredAt, "occurredAt")!!, zone),
                ref(e.accountId, "account"), ref(e.toAccountId, "account"), ref(e.categoryId, "category"), e.payee, e.note, e.tags,
                enum<EntrySource>(e.source, "source"), e.upiRef, e.splits.map { Split(ref(it.categoryId, "category")!!, Money(it.amountPaise)) },
                ref(e.linkedEntryId, "linked entry"), instant(e.createdAt, "createdAt")!!, instant(e.updatedAt, "updatedAt")!!, e.deleted,
                e.generatedBy?.let { Generation(ref(it.ruleId, "recurring rule")!!, month(it.month, "generatedBy month")!!) },
            )
        }?.also { notAfterExport("entry", id, it.createdAt, it.updatedAt); keyNotAfterExport(it) }?.let(entries::add)
    }

    fun recurring(r: RecurringV3) {
        val id = id(r.id, "recurring rule") ?: return
        record("recurring rule $id") {
            RecurringRule(
                id, enum<EntryType>(r.type, "type"), Money(r.amountPaise), r.dayOfMonth, ref(r.categoryId, "category"),
                ref(r.accountId, "account"), r.note, r.paused, date(r.startsOn, "startsOn")!!, instant(r.updatedAt, "updatedAt")!!, r.deleted,
            )
        }?.also { notAfterExport("recurring rule", id, it.updatedAt) }?.let(recurring::add)
    }

    fun ledger(l: LedgerV1) {
        val id = id(l.id, "ledger entry") ?: return
        record("ledger entry $id") {
            LedgerEntry(
                id, ref(l.personId, "person")!!, enum<LedgerDirection>(l.direction, "direction"), Money(l.amountPaise),
                date(l.date, "date")!!, l.note, l.settled, instant(l.updatedAt, "updatedAt")!!, l.deleted,
            )
        }?.also { notAfterExport("ledger entry", id, it.updatedAt) }?.let(ledger::add)
    }

    fun budget(b: BudgetV1) {
        val id = id(b.id, "budget") ?: return
        record("budget $id") { Budget(id, ref(b.categoryId, "category")!!, Money(b.limitPaise), instant(b.updatedAt, "updatedAt")!!, b.deleted) }
            ?.also { notAfterExport("budget", id, it.updatedAt) }?.let(budgets::add)
    }

    fun merchantRule(m: MerchantRuleV1) {
        val id = id(m.id, "merchant rule") ?: return
        record("merchant rule $id") { MerchantRule(id, m.payee, ref(m.categoryId, "category")!!, instant(m.updatedAt, "updatedAt")!!, m.deleted) }
            ?.also { notAfterExport("merchant rule", id, it.updatedAt) }?.let(merchantRules::add)
    }

    /** Checks references and duplicates across all records, then gives the snapshot or every problem found. */
    fun finish(appVersion: String, createdOn: String): DecodeResult {
        problems += Consistency.crossRecord(accounts, categories, entries, recurring, people, ledger, budgets, merchantRules)

        // A missing settings or export time always comes with a problem, so "no problems" means both are present.
        val settings = settings; val exportedAt = exportedAt
        if (problems.isNotEmpty() || settings == null || exportedAt == null) {
            val capped = if (problems.size <= BackupCodec.MAX_PROBLEMS) problems
            else problems.take(BackupCodec.MAX_PROBLEMS) + "… and ${problems.size - BackupCodec.MAX_PROBLEMS} more problems"
            return DecodeResult.Failure(capped, clockBehind = clockProblem != null && problems == listOf(clockProblem))
        }
        if (resetFutureDates) {
            // FLAW-001: dates after this phone's time would win every later merge (SEC-005). DATA-002: so every change
            // time moves back by the clock difference, as if the backup were made now. That keeps its order against
            // this phone's own edits (resetting to now made every restored record the newest). Never before 2000, so
            // the result can be backed up again. Only change times move; when things happened stays as it was.
            val difference = exportedAt - now
            val thisMonth = now.toLocalDateTime(homeZone ?: TimeZone.UTC).date.yearMonth
            fun Instant.shiftedBack() = maxOf(this - difference, earliest)
            val snapshot = Snapshot(
                settings,
                accounts.map { it.copy(updatedAt = it.updatedAt.shiftedBack()) },
                categories.map { it.copy(updatedAt = it.updatedAt.shiftedBack()) },
                // #19b: entries generated for months this phone hasn't reached are left out; catch-up makes them again,
                // with the same id, when the month comes. Kept, they would block this phone's backups until then.
                entries.filter { e -> e.generatedBy.let { it == null || it.month <= thisMonth } }
                    .map { it.copy(createdAt = it.createdAt.shiftedBack(), updatedAt = it.updatedAt.shiftedBack()) },
                recurring.map { it.copy(updatedAt = it.updatedAt.shiftedBack()) },
                people.map { it.copy(updatedAt = it.updatedAt.shiftedBack()) },
                ledger.map { it.copy(updatedAt = it.updatedAt.shiftedBack()) },
                budgets.map { it.copy(updatedAt = it.updatedAt.shiftedBack()) },
                merchantRules.map { MerchantRule(it.id, it.payee, it.categoryId, it.updatedAt.shiftedBack(), it.deleted) },
            ).normalized()
            return DecodeResult.Success(snapshot, ExportMeta(appVersion, createdOn, now))
        }
        val snapshot = Snapshot(settings, accounts, categories, entries, recurring, people, ledger, budgets, merchantRules).normalized()
        return DecodeResult.Success(snapshot, ExportMeta(appVersion, createdOn, exportedAt))
    }

    /**
     * SEC-001 (recurring key): catch-up only generates months that have started, so a key for a month after the
     * one the backup was made in can't be genuine. Merged in, it would stop catch-up from creating that month.
     */
    private fun keyNotAfterExport(e: Entry) {
        // Against the backup's own export time, also when restoring anyway: a key for the month the backup was made in
        // is genuine even if this phone's clock hasn't reached that month (PR #2 review).
        Consistency.keyAfterExport(e, exportedAt ?: return, homeZone ?: TimeZone.UTC)?.let { problems += it }
    }

    /** SEC-005: nothing in a backup can be newer than the backup itself, or it would win every future merge. */
    private fun notAfterExport(what: String, id: EntityId, vararg times: Instant) {
        val at = exportedAt ?: return
        if (times.any { it > at }) problems += Consistency.changedAfterExport(what, id, at)
    }

    private fun settings(currency: String, zone: TimeZone?, s: SettingsV1): Settings? {
        val changes = s.startDayChanges.map { c ->
            val from = date(c.effectiveFrom, "settings effectiveFrom") ?: return null.also { problems += "settings: invalid month start schedule" }
            val end = date(c.transitionEnd, "settings transitionEnd") ?: return null.also { problems += "settings: invalid month start schedule" }
            StartDayChange(from, end, c.startDay)
        }
        val schedule = MonthSchedule.restore(s.monthStartDay, changes)
        if (schedule == null) problems += "settings: invalid month start schedule"
        if (zone == null || schedule == null) return null
        return Settings(currency, zone, schedule, s.alertsEnabled)
    }

    private fun id(raw: String, what: String): EntityId? =
        EntityId.parse(raw) ?: null.also { problems += "$what: invalid id '${raw.take(60)}'" }

    /** A reference's id; whether it points at an existing record is checked in [finish]. */
    private fun ref(raw: String?, what: String): EntityId? =
        raw?.let { EntityId.parse(it) ?: throw IllegalArgumentException("unknown $what ${it.take(60)}") }

    /** Builds one record; a validation failure becomes a problem naming the record. */
    private fun <T> record(what: String, build: () -> T): T? = try {
        build()
    } catch (e: Exception) {
        problems += "$what: ${e.message ?: "invalid"}"
        null
    }

    private fun instant(raw: String, what: String): Instant? {
        val value = try { Instant.parse(raw) } catch (e: Exception) { null }
        if (value == null || value < earliest || value >= latest) {
            problems += "$what '${raw.take(40)}' is not a valid time or is out of range (2000–2100)"
            return null
        }
        return value
    }

    private fun date(raw: String, what: String): LocalDate? {
        val value = try { LocalDate.parse(raw) } catch (e: Exception) { null }
        if (value == null || value < firstDay || value > lastDay) {
            problems += "$what '${raw.take(40)}' is not a valid date or is out of range (2000–2100)"
            return null
        }
        return value
    }

    private fun month(raw: String, what: String): YearMonth? {
        val value = try { YearMonth.parse(raw) } catch (e: Exception) { null }
        if (value == null) throw IllegalArgumentException("$what '${raw.take(40)}' is not a valid month")
        if (value < YearMonth(2000, 1) || value > YearMonth(2100, 12)) throw IllegalArgumentException("$what '$raw' is out of range (2000–2100)")
        return value
    }

    private inline fun <reified E : Enum<E>> enum(raw: String, what: String): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: throw IllegalArgumentException("unknown $what '${raw.take(40)}'")

    companion object {
        /** A whole file already in memory (a v1 file, migrated). */
        fun read(file: BackupContent, now: Instant, restoreAnyway: Boolean = false): DecodeResult = Reader(now, restoreAnyway).run {
            header(file.currency, file.exportedAt, file.settings)
            file.accounts.forEach(::account); file.categories.forEach(::category); file.people.forEach(::person)
            file.entries.forEach(::entry); file.recurring.forEach(::recurring); file.ledger.forEach(::ledger)
            file.budgets.forEach(::budget); file.merchantRules.forEach(::merchantRule)
            finish(file.appVersion, file.createdOn)
        }
    }
}

enum class RestoreMode {
    /** Keep everything; where both sides have the same record, the newer `updatedAt` wins. Current settings stay. */
    MERGE,

    /** The backup replaces all current data and settings. */
    REPLACE,
}

object Restore {

    fun apply(current: Snapshot, backup: Snapshot, mode: RestoreMode): Snapshot = when (mode) {
        RestoreMode.REPLACE -> backup.normalized()
        RestoreMode.MERGE -> Snapshot(
            settings = current.settings,
            accounts = merge(current.accounts, backup.accounts, { it.id }, { it.updatedAt }),
            categories = merge(current.categories, backup.categories, { it.id }, { it.updatedAt }),
            entries = merge(current.entries, backup.entries, { it.id }, { it.updatedAt }),
            recurring = merge(current.recurring, backup.recurring, { it.id }, { it.updatedAt }),
            people = merge(current.people, backup.people, { it.id }, { it.updatedAt }),
            ledger = merge(current.ledger, backup.ledger, { it.id }, { it.updatedAt }),
            budgets = merge(current.budgets, backup.budgets, { it.id }, { it.updatedAt }),
            merchantRules = merge(current.merchantRules, backup.merchantRules, { it.id }, { it.updatedAt }),
        ).normalized()
    }

    /** Union by id; the newer record wins, and on a tie the current one stays. */
    private fun <T> merge(current: List<T>, backup: List<T>, id: (T) -> EntityId, updatedAt: (T) -> Instant): List<T> {
        val byId = LinkedHashMap<String, T>()
        current.forEach { byId[id(it).value] = it }
        backup.forEach { incoming ->
            val existing = byId[id(incoming).value]
            if (existing == null || updatedAt(incoming) > updatedAt(existing)) byId[id(incoming).value] = incoming
        }
        return byId.values.toList()
    }
}
