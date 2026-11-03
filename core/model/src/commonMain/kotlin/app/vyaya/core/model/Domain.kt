package app.vyaya.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.TimeZone
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * Vyaya's records. Design rules (plan.md Part C): UUID ids (2), `updatedAt` + soft delete on every record (3),
 * fixed ids for system categories (5). Every constructor validates, so an invalid record can't exist,
 * whether it comes from the UI, the parser or a restored backup.
 */

/** A record id: a lowercase UUID, or a fixed system id such as `sys.saved`. */
@JvmInline
value class EntityId private constructor(val value: String) {
    override fun toString(): String = value

    /** True for an id derived from a generation key (version 8); never for a random one (version 4). */
    val isDerived: Boolean get() = value.length == 36 && value[14] == '8'

    companion object {
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val SYSTEM = Regex("^sys\\.[a-z]+$")

        @OptIn(ExperimentalUuidApi::class)
        fun random(): EntityId = EntityId(Uuid.random().toString())

        fun parse(text: String): EntityId? = when {
            text.startsWith("sys.") -> text.takeIf { SYSTEM.matches(it) }?.let(::EntityId)
            else -> text.lowercase().takeIf { UUID.matches(it) }?.let(::EntityId)
        }

        internal fun system(name: String): EntityId = EntityId("sys.$name")

        /** A UUID (version 8, RFC 9562) from the first 16 bytes of SHA-256([name]); the same on every platform. */
        internal fun derived(name: String): EntityId {
            val h = Sha256().apply { update(name) }.hexDigest().take(32).toCharArray()
            h[12] = '8' // version 8
            h[16] = "89ab"[h[16].digitToInt(16) and 0x3] // variant 10xx
            val hex = h.concatToString()
            return EntityId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
        }
    }
}

enum class CategoryKind { EXPENSE, INCOME, SYSTEM }
enum class AccountKind { BANK, CARD, CASH, WALLET }
enum class EntryType { EXPENSE, INCOME, TRANSFER, SAVED }
enum class EntrySource { MANUAL, SMS, NOTIFICATION, SCREENSHOT, SHORTCUT, RECURRING, SYSTEM }
enum class LedgerDirection { LENT, BORROWED }

internal const val MAX_NAME = 100
internal const val MAX_NOTE = 500

internal fun requireName(name: String, what: String) =
    require(name.isNotBlank() && name.length <= MAX_NAME) { "$what must be 1..$MAX_NAME characters" }

internal fun requirePositive(amount: Money, what: String) = require(amount.paise > 0) { "$what must be positive" }

data class Category(val id: EntityId, val name: String, val kind: CategoryKind, val updatedAt: Instant, val deleted: Boolean) {
    init { requireName(name, "Category name") }
}

/** Categories the app creates itself; their ids never change, on any platform or in any backup. */
object SystemCategories {
    private val EPOCH = Instant.fromEpochSeconds(0)
    val SAVED = Category(EntityId.system("saved"), "Saved", CategoryKind.SYSTEM, EPOCH, false)
    val TRANSFER = Category(EntityId.system("transfer"), "Transfer", CategoryKind.SYSTEM, EPOCH, false)
    val LENT = Category(EntityId.system("lent"), "Lent & Borrowed", CategoryKind.SYSTEM, EPOCH, false)
    val all: List<Category> = listOf(SAVED, TRANSFER, LENT)
}

data class Account(
    val id: EntityId,
    val name: String,
    val kind: AccountKind,
    /** Visible digits of the account/card number, as seen in bank SMS (`1234`). */
    val mask: String?,
    val updatedAt: Instant,
    val deleted: Boolean,
) {
    init {
        requireName(name, "Account name")
        require(mask == null || (mask.length in 3..6 && mask.all { it in '0'..'9' })) { "mask must be 3..6 digits" }
    }
}

/** Part of an entry's amount assigned to a category (one DMart bill: groceries + household). */
data class Split(val categoryId: EntityId, val amount: Money) {
    init { requirePositive(amount, "Split amount") }
}

data class Entry(
    val id: EntityId,
    val type: EntryType,
    val amount: Money,
    val occurredAt: CapturedAt,
    val accountId: EntityId?,
    /** For transfers: where the money went (own account, cash wallet, credit card). */
    val toAccountId: EntityId?,
    val categoryId: EntityId?,
    val payee: String?,
    val note: String,
    val tags: List<String>,
    val source: EntrySource,
    val upiRef: String?,
    val splits: List<Split>,
    /** A refund points at its original payment; a reversal at what it reverses. */
    val linkedEntryId: EntityId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deleted: Boolean,
    /** Set when a recurring rule made this entry: the key that keeps it to one entry per due date. */
    val generatedBy: Generation? = null,
) {
    init {
        requirePositive(amount, "Entry amount")
        require(payee == null || payee.length <= MAX_NAME) { "payee must be at most $MAX_NAME characters" }
        require(note.length <= MAX_NOTE) { "note must be at most $MAX_NOTE characters" }
        require(tags.size <= MAX_TAGS) { "at most $MAX_TAGS tags" }
        tags.forEach { require(it.isNotBlank() && it.length <= MAX_TAG) { "tags must be 1..$MAX_TAG characters" } }
        require(upiRef == null || upiRef.length <= 64) { "upiRef too long" }
        require(splits.size <= MAX_SPLITS) { "at most $MAX_SPLITS splits" }
        if (generatedBy != null) {
            require(source == EntrySource.RECURRING) { "only recurring entries have a generation key" }
            require(id == generatedBy.entryId) { "a generated entry's id must come from its rule and month" }
            // DATA-001: paid a little early or late is fine; further away, the key and the date tell different stories.
            val paid = occurredAt.localDate.let { it.year * 12 + it.month.ordinal }
            val due = generatedBy.month.let { it.year * 12 + it.month.ordinal }
            require(paid - due in -1..1) { "a generated entry is dated in its month (${generatedBy.month}) or the month either side" }
        } else {
            // PEN-003: such an id belongs to a rule's month; taking it would block that month's catch-up.
            require(!id.isDerived) { "only an entry made by a recurring rule can have a derived id" }
        }
        if (splits.isNotEmpty()) {
            require(splits.fold(Money(0)) { sum, s -> sum + s.amount } == amount) { "splits must add up to the amount" }
        }
    }

    /** Only expenses count towards spending; transfers, Saved and lent/borrowed never do. */
    val countsAsSpending: Boolean get() = type == EntryType.EXPENSE

    val countsAsIncome: Boolean get() = type == EntryType.INCOME

    fun markDeleted(at: Instant): Entry = copy(deleted = true, updatedAt = at)

    companion object {
        const val MAX_TAGS = 20
        const val MAX_TAG = 30

        /** Keeps every valid entry within the backup's line limit (PEN-002). */
        const val MAX_SPLITS = 20
    }
}

/**
 * The key of an entry a recurring rule made: rule + calendar month (CLAUDE.md rule 8.4).
 *
 * The entry's id is derived from the key, so the same rent payment generated on two phones (or before and
 * after a restore) has the same id, and a merge by id keeps one copy. The key is the month, not the due date,
 * so moving a rule's day can't make a second entry for a month that already has one (DATA-002). It is the
 * calendar month because a rule is "day N of every month"; the budget month moves when the start day changes.
 */
data class Generation(val ruleId: EntityId, val month: YearMonth) {
    /** A version-8 UUID from SHA-256("vyaya.generated.v1|<ruleId>|<yyyy-MM>"). FROZEN: changing it duplicates entries. */
    val entryId: EntityId get() = EntityId.derived("vyaya.generated.v1|$ruleId|$month")
}

/** Something that repeats every month, like pocket money on the 1st or Spotify on the 5th. */
data class RecurringRule(
    val id: EntityId,
    val type: EntryType,
    val amount: Money,
    /** 1..31; clamped to the month's last day in short months. */
    val dayOfMonth: Int,
    val categoryId: EntityId?,
    val accountId: EntityId?,
    val note: String,
    val paused: Boolean,
    val startsOn: LocalDate,
    // No "last generated" date: it is worked out from the entries' generation keys (CLAUDE.md rule 8.5).
    val updatedAt: Instant,
    val deleted: Boolean,
) {
    init {
        require(type == EntryType.EXPENSE || type == EntryType.INCOME) { "recurring rules are expenses or income" }
        requirePositive(amount, "Recurring amount")
        require(dayOfMonth in 1..31) { "dayOfMonth must be 1..31" }
        require(note.length <= MAX_NOTE) { "note must be at most $MAX_NOTE characters" }
    }
}

data class Person(val id: EntityId, val name: String, val updatedAt: Instant, val deleted: Boolean) {
    init { requireName(name, "Person name") }
}

/** "I paid for dinner, Rahul owes me ₹300". Never counts towards spending. */
data class LedgerEntry(
    val id: EntityId,
    val personId: EntityId,
    val direction: LedgerDirection,
    val amount: Money,
    val date: LocalDate,
    val note: String,
    val settled: Boolean,
    val updatedAt: Instant,
    val deleted: Boolean,
) {
    init {
        requirePositive(amount, "Ledger amount")
        require(note.length <= MAX_NOTE) { "note must be at most $MAX_NOTE characters" }
    }
}

data class Budget(val id: EntityId, val categoryId: EntityId, val limit: Money, val updatedAt: Instant, val deleted: Boolean) {
    init { requirePositive(limit, "Budget limit") }
}

/** "Swiggy → Food": learned once, applied to every later payment to the same payee. */
class MerchantRule(
    val id: EntityId,
    payee: String,
    val categoryId: EntityId,
    val updatedAt: Instant,
    val deleted: Boolean,
) {
    /** Stored trimmed and lowercase so "SWIGGY" and "Swiggy" are one rule. */
    val payee: String = payee.trim().lowercase()

    init { requireName(this.payee, "Merchant payee") }

    override fun equals(other: Any?): Boolean = other is MerchantRule && id == other.id && payee == other.payee &&
        categoryId == other.categoryId && updatedAt == other.updatedAt && deleted == other.deleted

    override fun hashCode(): Int = listOf(id, payee, categoryId, updatedAt, deleted).hashCode()

    override fun toString(): String =
        "MerchantRule(id=$id, payee=$payee, categoryId=$categoryId, updatedAt=$updatedAt, deleted=$deleted)"
}

data class Settings(
    val currency: String,
    val homeTimeZone: TimeZone,
    val monthSchedule: MonthSchedule,
    val alertsEnabled: Boolean,
) {
    companion object {
        fun default(): Settings = Settings("INR", TimeZone.of("Asia/Kolkata"), MonthSchedule.of(1), alertsEnabled = true)
    }
}
