package app.vyaya.core.backup

import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Budget
import app.vyaya.core.model.CapturedAt
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntityId
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
import app.vyaya.core.model.SystemCategories
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.time.Instant

fun id(s: String) = EntityId.parse(s)!!
val T = Instant.parse("2026-10-27T10:00:00Z")
val IST = TimeZone.of("Asia/Kolkata")
val META = ExportMeta(appVersion = "1.0.0", createdOn = "android", exportedAt = Instant.parse("2026-10-27T12:00:00Z"))
val NOW = Instant.parse("2026-10-28T00:00:00Z")

/** For random snapshots, whose timestamps run up to 2099: an export made (and restored) at the very end of the range. */
val LATE = Instant.parse("2100-12-31T00:00:00Z")
val META_LATE = META.copy(exportedAt = LATE)

// ids chosen so the golden file is easy to read
val ACC = id("00000000-0000-4000-8000-000000000001")
val FOOD = id("00000000-0000-4000-8000-000000000002")
val GROC = id("00000000-0000-4000-8000-000000000003")
val E1 = id("00000000-0000-4000-8000-000000000004")
val RAHUL = id("00000000-0000-4000-8000-000000000005")
val L1 = id("00000000-0000-4000-8000-000000000006")
val R1 = id("00000000-0000-4000-8000-000000000007")
val B1 = id("00000000-0000-4000-8000-000000000008")
val M1 = id("00000000-0000-4000-8000-000000000009")

/** A small snapshot with one of everything; its exact JSON is in GOLDEN_JSON. */
val GOLDEN_SNAPSHOT = Snapshot(
    settings = Settings("INR", IST, MonthSchedule.of(1).withStartDay(25, LocalDate(2026, 3, 10)), alertsEnabled = true),
    accounts = listOf(Account(ACC, "HDFC Salary", AccountKind.BANK, "1234", T, false)),
    categories = listOf(Category(FOOD, "Food", CategoryKind.EXPENSE, T, false), Category(GROC, "Groceries", CategoryKind.EXPENSE, T, true)),
    entries = listOf(
        Entry(
            E1, EntryType.EXPENSE, Money(12050), CapturedAt(Instant.parse("2026-10-26T13:05:00Z"), IST), ACC, null, FOOD,
            "Swiggy", "lunch", listOf("office"), EntrySource.NOTIFICATION, "429112345678",
            listOf(Split(FOOD, Money(10050)), Split(SystemCategories.SAVED.id, Money(2000))), null, T, T, false,
        ),
    ),
    recurring = listOf(RecurringRule(R1, EntryType.EXPENSE, Money(11900), 5, FOOD, ACC, "Spotify", false, LocalDate(2026, 1, 5), LocalDate(2026, 10, 5), T, false)),
    people = listOf(Person(RAHUL, "Rahul", T, false)),
    ledger = listOf(LedgerEntry(L1, RAHUL, LedgerDirection.LENT, Money(30000), LocalDate(2026, 10, 20), "Dinner", false, T, false)),
    budgets = listOf(Budget(B1, FOOD, Money(500000), T, false)),
    merchantRules = listOf(MerchantRule(M1, "Swiggy", FOOD, T, false)),
)

val GOLDEN_JSON = """
{
  "format": "vyaya-backup",
  "schemaVersion": 1,
  "appVersion": "1.0.0",
  "createdOn": "android",
  "exportedAt": "2026-10-27T12:00:00Z",
  "currency": "INR",
  "settings": {
    "monthStartDay": 1,
    "startDayChanges": [
      {
        "effectiveFrom": "2026-04-01",
        "transitionEnd": "2026-04-25",
        "startDay": 25
      }
    ],
    "timezone": "Asia/Kolkata",
    "alertsEnabled": true
  },
  "accounts": [
    {
      "id": "00000000-0000-4000-8000-000000000001",
      "name": "HDFC Salary",
      "kind": "BANK",
      "mask": "1234",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "categories": [
    {
      "id": "00000000-0000-4000-8000-000000000002",
      "name": "Food",
      "kind": "EXPENSE",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    },
    {
      "id": "00000000-0000-4000-8000-000000000003",
      "name": "Groceries",
      "kind": "EXPENSE",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": true
    }
  ],
  "entries": [
    {
      "id": "00000000-0000-4000-8000-000000000004",
      "type": "EXPENSE",
      "amountPaise": 12050,
      "occurredAt": "2026-10-26T13:05:00Z",
      "timezone": "Asia/Kolkata",
      "accountId": "00000000-0000-4000-8000-000000000001",
      "toAccountId": null,
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "payee": "Swiggy",
      "note": "lunch",
      "tags": [
        "office"
      ],
      "source": "NOTIFICATION",
      "upiRef": "429112345678",
      "splits": [
        {
          "categoryId": "00000000-0000-4000-8000-000000000002",
          "amountPaise": 10050
        },
        {
          "categoryId": "sys.saved",
          "amountPaise": 2000
        }
      ],
      "linkedEntryId": null,
      "createdAt": "2026-10-27T10:00:00Z",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "recurring": [
    {
      "id": "00000000-0000-4000-8000-000000000007",
      "type": "EXPENSE",
      "amountPaise": 11900,
      "dayOfMonth": 5,
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "accountId": "00000000-0000-4000-8000-000000000001",
      "note": "Spotify",
      "paused": false,
      "startsOn": "2026-01-05",
      "lastGeneratedOn": "2026-10-05",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "people": [
    {
      "id": "00000000-0000-4000-8000-000000000005",
      "name": "Rahul",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "ledger": [
    {
      "id": "00000000-0000-4000-8000-000000000006",
      "personId": "00000000-0000-4000-8000-000000000005",
      "direction": "LENT",
      "amountPaise": 30000,
      "date": "2026-10-20",
      "note": "Dinner",
      "settled": false,
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "budgets": [
    {
      "id": "00000000-0000-4000-8000-000000000008",
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "limitPaise": 500000,
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "merchantRules": [
    {
      "id": "00000000-0000-4000-8000-000000000009",
      "payee": "swiggy",
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ]
}
""".trim()

private val ZONES = listOf("Asia/Kolkata", "America/New_York", "Europe/London", "Asia/Dubai", "Asia/Singapore")
private val NAMES = listOf("Food", "Rent", "Travel", "Rahul", "Priya", "HDFC", "Cash", "Swiggy", "Café ☕", "Sharma & Sons", "\"Quoted\"", "line\nbreak")

/** A random, internally consistent snapshot (references always point at existing records). */
fun randomSnapshot(random: Random, maxPerList: Int = 8): Snapshot {
    fun instant() = Instant.fromEpochMilliseconds(random.nextLong(978_307_200_000, 4_102_444_799_000)) // 2001..2099
    fun date() = LocalDate(2001, 1, 1).plus(random.nextInt(0, 36_000), DateTimeUnit.DAY)
    fun money() = Money(random.nextLong(1, 10_00_00_000))
    fun name() = NAMES[random.nextInt(NAMES.size)]
    fun n() = random.nextInt(0, maxPerList + 1)
    fun <T> pickOrNull(list: List<T>) = if (list.isEmpty() || random.nextBoolean()) null else list[random.nextInt(list.size)]

    val accounts = List(n()) { Account(EntityId.random(), name(), AccountKind.entries.random(random), if (random.nextBoolean()) "${random.nextInt(1000, 9999)}" else null, instant(), random.nextBoolean()) }
    val categories = List(n()) { Category(EntityId.random(), name(), if (random.nextBoolean()) CategoryKind.EXPENSE else CategoryKind.INCOME, instant(), random.nextBoolean()) }
    val categoryIds = categories.map { it.id } + SystemCategories.all.map { it.id }
    val people = List(n()) { Person(EntityId.random(), name(), instant(), random.nextBoolean()) }
    val entries = mutableListOf<Entry>()
    repeat(n()) {
        val amount = money()
        val splits = if (random.nextInt(4) == 0 && amount.paise >= 2) {
            val first = Money(random.nextLong(1, amount.paise))
            listOf(Split(categoryIds.random(random), first), Split(categoryIds.random(random), amount - first))
        } else emptyList()
        entries += Entry(
            EntityId.random(), EntryType.entries.random(random), amount, CapturedAt(instant(), TimeZone.of(ZONES.random(random))),
            pickOrNull(accounts)?.id, pickOrNull(accounts)?.id, pickOrNull(categoryIds), if (random.nextBoolean()) name() else null,
            if (random.nextBoolean()) name() else "", List(random.nextInt(0, 3)) { "tag$it" }, EntrySource.entries.random(random),
            if (random.nextBoolean()) "${random.nextLong(100_000_000_000, 999_999_999_999)}" else null, splits,
            pickOrNull(entries)?.id, instant(), instant(), random.nextBoolean(),
        )
    }
    val recurring = List(n()) {
        RecurringRule(EntityId.random(), if (random.nextBoolean()) EntryType.EXPENSE else EntryType.INCOME, money(), random.nextInt(1, 32),
            pickOrNull(categoryIds), pickOrNull(accounts)?.id, name(), random.nextBoolean(), date(), if (random.nextBoolean()) date() else null,
            instant(), random.nextBoolean())
    }
    val ledger = if (people.isEmpty()) emptyList() else List(n()) {
        LedgerEntry(EntityId.random(), people.random(random).id, LedgerDirection.entries.random(random), money(), date(), name(), random.nextBoolean(), instant(), random.nextBoolean())
    }
    val budgets = if (categories.isEmpty()) emptyList() else List(n()) { Budget(EntityId.random(), categories.random(random).id, money(), instant(), random.nextBoolean()) }
    val rules = if (categories.isEmpty()) emptyList() else List(n()) { MerchantRule(EntityId.random(), name(), categories.random(random).id, instant(), random.nextBoolean()) }

    var schedule = MonthSchedule.of(random.nextInt(1, 32))
    var day = LocalDate(2010, 1, 1)
    repeat(random.nextInt(0, 4)) { day = day.plus(random.nextInt(1, 500), DateTimeUnit.DAY); schedule = schedule.withStartDay(random.nextInt(1, 32), day) }
    val settings = Settings("INR", TimeZone.of(ZONES.random(random)), schedule, random.nextBoolean())
    return Snapshot(settings, accounts, categories, entries, recurring, people, ledger, budgets, rules)
}
