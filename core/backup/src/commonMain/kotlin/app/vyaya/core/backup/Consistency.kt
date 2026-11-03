package app.vyaya.core.backup

import app.vyaya.core.model.Account
import app.vyaya.core.model.Budget
import app.vyaya.core.model.Category
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.LedgerEntry
import app.vyaya.core.model.MerchantRule
import app.vyaya.core.model.Person
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.model.Settings
import app.vyaya.core.model.SystemCategories
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What a restore refuses about records that are each valid on their own: references, duplicates, generation keys
 * against their rules and the export time, change times after the export, ranges and the currency.
 *
 * The reader and the writer share it, so the app never writes a backup it then refuses (DATA-001 in
 * reports/recommendations-fixes-2026-11-03.md). Messages are the reader's own.
 */
internal object Consistency {

    private val earliest = Instant.parse("2000-01-01T00:00:00Z")
    private val latest = Instant.parse("2101-01-01T00:00:00Z")
    private val firstDay = LocalDate(2000, 1, 1)
    private val lastDay = LocalDate(2100, 12, 31)

    /** Everything a restore of [s], exported at [exportedAt], would refuse; empty if it restores. */
    fun problems(s: Snapshot, exportedAt: Instant): List<String> {
        val problems = mutableListOf<String>()
        if (s.settings.currency != "INR") problems += "Unsupported currency '${s.settings.currency}' (only INR)"

        fun time(value: Instant, what: String) {
            if (value < earliest || value >= latest) problems += "$what '$value' is not a valid time or is out of range (2000–2100)"
        }
        fun date(value: LocalDate, what: String) {
            if (value < firstDay || value > lastDay) problems += "$what '$value' is not a valid date or is out of range (2000–2100)"
        }
        time(exportedAt, "exportedAt")
        s.settings.monthSchedule.changes.forEach { date(it.effectiveFrom, "settings effectiveFrom"); date(it.transitionEnd, "settings transitionEnd") }
        s.accounts.forEach { time(it.updatedAt, "updatedAt") }
        s.categories.forEach { time(it.updatedAt, "updatedAt") }
        s.people.forEach { time(it.updatedAt, "updatedAt") }
        s.entries.forEach { time(it.occurredAt.instant, "occurredAt"); time(it.createdAt, "createdAt"); time(it.updatedAt, "updatedAt") }
        s.recurring.forEach { date(it.startsOn, "startsOn"); time(it.updatedAt, "updatedAt") }
        s.ledger.forEach { date(it.date, "date"); time(it.updatedAt, "updatedAt") }
        s.budgets.forEach { time(it.updatedAt, "updatedAt") }
        s.merchantRules.forEach { time(it.updatedAt, "updatedAt") }

        s.categories.filter { it.id.value.startsWith("sys.") }.forEach { problems += "category ${it.id}: system ids are built in and can't be stored" }

        fun after(what: String, id: EntityId, vararg times: Instant) {
            if (times.any { it > exportedAt }) problems += changedAfterExport(what, id, exportedAt)
        }
        s.accounts.forEach { after("account", it.id, it.updatedAt) }
        s.categories.forEach { after("category", it.id, it.updatedAt) }
        s.people.forEach { after("person", it.id, it.updatedAt) }
        s.entries.forEach { after("entry", it.id, it.createdAt, it.updatedAt) }
        s.recurring.forEach { after("recurring rule", it.id, it.updatedAt) }
        s.ledger.forEach { after("ledger entry", it.id, it.updatedAt) }
        s.budgets.forEach { after("budget", it.id, it.updatedAt) }
        s.merchantRules.forEach { after("merchant rule", it.id, it.updatedAt) }
        s.entries.forEach { keyAfterExport(it, exportedAt, s.settings.homeTimeZone)?.let(problems::add) }

        problems += crossRecord(s.accounts, s.categories, s.entries, s.recurring, s.people, s.ledger, s.budgets, s.merchantRules)
        return problems
    }

    /** SEC-005: nothing in a backup can be newer than the backup itself, or it would win every future merge. */
    fun changedAfterExport(what: String, id: EntityId, exportedAt: Instant) = "$what $id: changed after the backup was made ($exportedAt)"

    /**
     * SEC-001 (recurring key): catch-up only generates months that have started, so a key for a month after the
     * one the backup was made in can't be genuine. Merged in, it would stop catch-up from creating that month.
     */
    fun keyAfterExport(e: Entry, exportedAt: Instant, zone: TimeZone): String? {
        val key = e.generatedBy ?: return null
        val made = exportedAt.toLocalDateTime(zone).date.let { YearMonth(it.year, it.month) }
        return if (key.month > made) "entry ${e.id}: generated for ${key.month}, after the backup was made ($made)" else null
    }

    /** References between records, duplicate ids, and generated entries against their rules. */
    fun crossRecord(
        accounts: List<Account>, categories: List<Category>, entries: List<Entry>, recurring: List<RecurringRule>,
        people: List<Person>, ledger: List<LedgerEntry>, budgets: List<Budget>, merchantRules: List<MerchantRule>,
    ): List<String> {
        val s = Snapshot(Settings.default(), accounts, categories, entries, recurring, people, ledger, budgets, merchantRules)
        val problems = mutableListOf<String>()
        val accountIds = s.accounts.mapTo(HashSet()) { it.id }
        val categoryIds = s.categories.mapTo(HashSet()) { it.id } + SystemCategories.all.map { it.id }
        val personIds = s.people.mapTo(HashSet()) { it.id }
        val entryIds = s.entries.mapTo(HashSet()) { it.id }
        val rulesById = s.recurring.associateBy { it.id }
        s.entries.forEach { e ->
            val key = e.generatedBy ?: return@forEach
            val rule = rulesById[key.ruleId] ?: return@forEach // an unknown rule is reported below
            // DATA-004: catch-up starts at the rule's first month, so an earlier key can't be genuine.
            val first = YearMonth(rule.startsOn.year, rule.startsOn.month)
            if (key.month < first) problems += "entry ${e.id}: generated for ${key.month}, before its rule starts ($first)"
            // FLAW-006: the user may change this month's amount, not what kind of money it is.
            if (e.type != rule.type) problems += "entry ${e.id}: type ${e.type} differs from its rule's (${rule.type})"
        }
        fun check(owner: String, id: EntityId?, known: Set<EntityId>, what: String) {
            if (id != null && id !in known) problems += "$owner: unknown $what $id"
        }
        s.entries.forEach { e ->
            val owner = "entry ${e.id}"
            check(owner, e.accountId, accountIds, "account"); check(owner, e.toAccountId, accountIds, "account")
            check(owner, e.categoryId, categoryIds, "category"); e.splits.forEach { check(owner, it.categoryId, categoryIds, "category") }
            check(owner, e.linkedEntryId, entryIds, "linked entry"); check(owner, e.generatedBy?.ruleId, rulesById.keys, "recurring rule")
        }
        s.recurring.forEach { r ->
            check("recurring rule ${r.id}", r.categoryId, categoryIds, "category"); check("recurring rule ${r.id}", r.accountId, accountIds, "account")
        }
        s.ledger.forEach { check("ledger entry ${it.id}", it.personId, personIds, "person") }
        s.budgets.forEach { check("budget ${it.id}", it.categoryId, categoryIds, "category") }
        s.merchantRules.forEach { check("merchant rule ${it.id}", it.categoryId, categoryIds, "category") }

        listOf(
            "accounts" to s.accounts.map { it.id }, "categories" to s.categories.map { it.id }, "entries" to s.entries.map { it.id },
            "recurring" to s.recurring.map { it.id }, "people" to s.people.map { it.id }, "ledger" to s.ledger.map { it.id },
            "budgets" to s.budgets.map { it.id }, "merchantRules" to s.merchantRules.map { it.id },
        ).forEach { (name, ids) ->
            ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "$name: duplicate id $it" }
        }
        return problems
    }
}
