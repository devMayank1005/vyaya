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

    /** Human-readable problems, at most [BackupCodec.MAX_PROBLEMS] plus a "… more" line. Nothing is restored. */
    data class Failure(val problems: List<String>) : DecodeResult
}

/**
 * Reads and writes the Vyaya backup file (schema v1), byte-for-byte identical on Android and iOS.
 *
 * [decode] never throws. Anything wrong in the file becomes a reported problem and nothing is restored:
 * wrong format, a newer schema, oversized input, bad ids, unknown time zones (SEC-008), dates outside
 * 2000–2100 (PEN-009), invalid records, duplicates and dangling references.
 */
object BackupCodec {

    const val FORMAT = "vyaya-backup"
    const val SCHEMA_VERSION = 1
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
    fun write(snapshot: Snapshot, meta: ExportMeta, limits: BackupLimits = BackupLimits(), sink: (String) -> Unit): EncodeResult =
        BackupV2.write(toFile(snapshot, meta), limits, sink)

    /** Reads a backup from its lines (v2, or a v1 file from an earlier version). Never throws. */
    fun read(lines: Sequence<String>, now: Instant, limits: BackupLimits = BackupLimits()): DecodeResult =
        BackupV2.read(lines, now, limits)

    /** The whole v2 file as one string (convenience for tests and small backups). */
    fun encode(snapshot: Snapshot, meta: ExportMeta, limits: BackupLimits = BackupLimits()): String {
        val out = StringBuilder()
        val result = write(snapshot, meta, limits) { out.append(it).append('\n') }
        require(result is EncodeResult.Success) { (result as EncodeResult.Failure).reason }
        return out.toString()
    }

    fun decode(text: String, now: Instant, limits: BackupLimits = BackupLimits()): DecodeResult =
        read(text.lineSequence(), now, limits)

    /** Writes the old v1 format. Kept only to produce migration test fixtures; the app always writes v2. */
    internal fun encodeV1(snapshot: Snapshot, meta: ExportMeta): String =
        json.encodeToString(BackupFileV1.serializer(), toFile(snapshot, meta))

    internal fun toFile(snapshot: Snapshot, meta: ExportMeta): BackupFileV1 {
        val s = snapshot.normalized()
        return BackupFileV1(
            format = FORMAT,
            schemaVersion = SCHEMA_VERSION,
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
                EntryV1(
                    e.id.value, e.type.name, e.amount.paise, e.occurredAt.instant.toString(), e.occurredAt.timeZone.id,
                    e.accountId?.value, e.toAccountId?.value, e.categoryId?.value, e.payee, e.note, e.tags, e.source.name,
                    e.upiRef, e.splits.map { SplitV1(it.categoryId.value, it.amount.paise) }, e.linkedEntryId?.value,
                    e.createdAt.toString(), e.updatedAt.toString(), e.deleted,
                )
            },
            recurring = s.recurring.map { r ->
                RecurringV1(
                    r.id.value, r.type.name, r.amount.paise, r.dayOfMonth, r.categoryId?.value, r.accountId?.value, r.note,
                    r.paused, r.startsOn.toString(), r.lastGeneratedOn?.toString(), r.updatedAt.toString(), r.deleted,
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

    internal fun decodeV1(text: String, now: Instant, maxChars: Int): DecodeResult {
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
        if (version < SCHEMA_VERSION) return fail("Unsupported backup schema version $version")
        val file = try {
            json.decodeFromJsonElement(BackupFileV1.serializer(), root)
        } catch (e: Exception) {
            return fail("The backup file is damaged or was edited: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
        }
        return Reader(file, now).read()
    }

    internal fun fail(problem: String) = DecodeResult.Failure(listOf(problem))
}

/** Turns a decoded v1 file into validated domain records, collecting every problem instead of throwing. */
internal class Reader(private val file: BackupFileV1, private val now: Instant) {

    private val problems = mutableListOf<String>()

    private val earliest = Instant.parse("2000-01-01T00:00:00Z")
    private val latest = Instant.parse("2101-01-01T00:00:00Z")
    private val firstDay = LocalDate(2000, 1, 1)
    private val lastDay = LocalDate(2100, 12, 31)

    fun read(): DecodeResult {
        if (file.currency != "INR") problems += "Unsupported currency '${file.currency}' (only INR)"
        val exportedAt = instant(file.exportedAt, "exportedAt")
        if (exportedAt != null && exportedAt > now + 1.days) {
            problems += "This backup says it was made in the future ($exportedAt). The phone's clock may have been wrong when it was exported."
        }
        val settings = settings()

        val accounts = file.accounts.mapNotNull { a ->
            val id = id(a.id, "account") ?: return@mapNotNull null
            record("account $id") { Account(id, a.name, enum<AccountKind>(a.kind, "kind"), a.mask, instant(a.updatedAt, "updatedAt")!!, a.deleted) }
        }
        val categories = file.categories.mapNotNull { c ->
            val id = id(c.id, "category") ?: return@mapNotNull null
            if (id.value.startsWith("sys.")) { problems += "category $id: system ids are built in and can't be stored"; return@mapNotNull null }
            record("category $id") { Category(id, c.name, enum<CategoryKind>(c.kind, "kind"), instant(c.updatedAt, "updatedAt")!!, c.deleted) }
        }
        val people = file.people.mapNotNull { p ->
            val id = id(p.id, "person") ?: return@mapNotNull null
            record("person $id") { Person(id, p.name, instant(p.updatedAt, "updatedAt")!!, p.deleted) }
        }

        val accountIds = accounts.map { it.id }.toSet()
        val categoryIds = categories.map { it.id }.toSet() + SystemCategories.all.map { it.id }
        val personIds = people.map { it.id }.toSet()
        val entryIds = file.entries.mapNotNull { EntityId.parse(it.id) }.toSet()

        fun ref(raw: String?, known: Set<EntityId>, what: String, owner: String): EntityId? {
            if (raw == null) return null
            val id = EntityId.parse(raw)
            if (id == null || id !in known) throw IllegalArgumentException("unknown $what $raw")
            return id
        }

        val entries = file.entries.mapNotNull { e ->
            val id = id(e.id, "entry") ?: return@mapNotNull null
            record("entry $id") {
                val zone = try { TimeZone.of(e.timezone) } catch (x: Exception) { throw IllegalArgumentException("unknown time zone '${e.timezone}'") }
                Entry(
                    id, enum<EntryType>(e.type, "type"), Money(e.amountPaise), CapturedAt(instant(e.occurredAt, "occurredAt")!!, zone),
                    ref(e.accountId, accountIds, "account", "entry"), ref(e.toAccountId, accountIds, "account", "entry"),
                    ref(e.categoryId, categoryIds, "category", "entry"), e.payee, e.note, e.tags, enum<EntrySource>(e.source, "source"),
                    e.upiRef, e.splits.map { Split(ref(it.categoryId, categoryIds, "category", "split")!!, Money(it.amountPaise)) },
                    ref(e.linkedEntryId, entryIds, "linked entry", "entry"), instant(e.createdAt, "createdAt")!!,
                    instant(e.updatedAt, "updatedAt")!!, e.deleted,
                )
            }
        }
        val recurring = file.recurring.mapNotNull { r ->
            val id = id(r.id, "recurring rule") ?: return@mapNotNull null
            record("recurring rule $id") {
                RecurringRule(
                    id, enum<EntryType>(r.type, "type"), Money(r.amountPaise), r.dayOfMonth, ref(r.categoryId, categoryIds, "category", "rule"),
                    ref(r.accountId, accountIds, "account", "rule"), r.note, r.paused, date(r.startsOn, "startsOn")!!,
                    r.lastGeneratedOn?.let { date(it, "lastGeneratedOn")!! }, instant(r.updatedAt, "updatedAt")!!, r.deleted,
                )
            }
        }
        val ledger = file.ledger.mapNotNull { l ->
            val id = id(l.id, "ledger entry") ?: return@mapNotNull null
            record("ledger entry $id") {
                LedgerEntry(
                    id, ref(l.personId, personIds, "person", "ledger")!!, enum<LedgerDirection>(l.direction, "direction"),
                    Money(l.amountPaise), date(l.date, "date")!!, l.note, l.settled, instant(l.updatedAt, "updatedAt")!!, l.deleted,
                )
            }
        }
        val budgets = file.budgets.mapNotNull { b ->
            val id = id(b.id, "budget") ?: return@mapNotNull null
            record("budget $id") { Budget(id, ref(b.categoryId, categoryIds, "category", "budget")!!, Money(b.limitPaise), instant(b.updatedAt, "updatedAt")!!, b.deleted) }
        }
        val merchantRules = file.merchantRules.mapNotNull { m ->
            val id = id(m.id, "merchant rule") ?: return@mapNotNull null
            record("merchant rule $id") { MerchantRule(id, m.payee, ref(m.categoryId, categoryIds, "category", "rule")!!, instant(m.updatedAt, "updatedAt")!!, m.deleted) }
        }

        // SEC-005: nothing in a backup can be newer than the backup itself, or it would win every future merge.
        if (exportedAt != null) {
            fun after(what: String, id: EntityId, vararg times: Instant) {
                if (times.any { it > exportedAt }) problems += "$what $id: changed after the backup was made ($exportedAt)"
            }
            accounts.forEach { after("account", it.id, it.updatedAt) }
            categories.forEach { after("category", it.id, it.updatedAt) }
            people.forEach { after("person", it.id, it.updatedAt) }
            entries.forEach { after("entry", it.id, it.createdAt, it.updatedAt) }
            recurring.forEach { after("recurring rule", it.id, it.updatedAt) }
            ledger.forEach { after("ledger entry", it.id, it.updatedAt) }
            budgets.forEach { after("budget", it.id, it.updatedAt) }
            merchantRules.forEach { after("merchant rule", it.id, it.updatedAt) }
        }

        listOf(
            "accounts" to accounts.map { it.id }, "categories" to categories.map { it.id }, "entries" to entries.map { it.id },
            "recurring" to recurring.map { it.id }, "people" to people.map { it.id }, "ledger" to ledger.map { it.id },
            "budgets" to budgets.map { it.id }, "merchantRules" to merchantRules.map { it.id },
        ).forEach { (name, ids) ->
            ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "$name: duplicate id $it" }
        }

        // A missing settings or export time always comes with a problem, so "no problems" means both are present.
        if (problems.isNotEmpty() || settings == null || exportedAt == null) {
            val capped = if (problems.size <= BackupCodec.MAX_PROBLEMS) problems
            else problems.take(BackupCodec.MAX_PROBLEMS) + "… and ${problems.size - BackupCodec.MAX_PROBLEMS} more problems"
            return DecodeResult.Failure(capped)
        }
        val snapshot = Snapshot(settings, accounts, categories, entries, recurring, people, ledger, budgets, merchantRules).normalized()
        return DecodeResult.Success(snapshot, ExportMeta(file.appVersion, file.createdOn, exportedAt))
    }

    private fun settings(): Settings? {
        val s = file.settings
        val zone = try { TimeZone.of(s.timezone) } catch (e: Exception) { problems += "settings: unknown time zone '${s.timezone}'"; null }
        val changes = s.startDayChanges.map { c ->
            val from = date(c.effectiveFrom, "settings effectiveFrom") ?: return null.also { problems += "settings: invalid month start schedule" }
            val end = date(c.transitionEnd, "settings transitionEnd") ?: return null.also { problems += "settings: invalid month start schedule" }
            StartDayChange(from, end, c.startDay)
        }
        val schedule = MonthSchedule.restore(s.monthStartDay, changes)
        if (schedule == null) problems += "settings: invalid month start schedule"
        if (zone == null || schedule == null) return null
        return Settings(file.currency, zone, schedule, s.alertsEnabled)
    }

    private fun id(raw: String, what: String): EntityId? =
        EntityId.parse(raw) ?: null.also { problems += "$what: invalid id '${raw.take(60)}'" }

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

    private inline fun <reified E : Enum<E>> enum(raw: String, what: String): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: throw IllegalArgumentException("unknown $what '${raw.take(40)}'")
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
