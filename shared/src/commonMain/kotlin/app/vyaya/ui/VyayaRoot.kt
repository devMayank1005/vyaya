package app.vyaya.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.RulesStore
import app.vyaya.core.model.RecurringRule
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home), RECURRING("Recurring", Icons.Filled.Refresh), PEOPLE("People", Icons.Filled.Person),
    DETECTED("Detected", Icons.Filled.Notifications),
    BACKUP("Settings", Icons.Filled.Settings),
}

/**
 * The app: Home (this month, add and edit by hand) and Detected (capture's inbox), over the database. [opened] changes
 * each time the app's open routine ran (categories and recurring entries may be new), so they are read again.
 */
@Composable
fun VyayaRoot(
    db: VyayaDatabase,
    setup: Setup,
    clock: () -> Instant,
    opened: Int,
    onNotificationAccess: () -> Unit,
    onSms: () -> Unit,
    onImport: () -> Unit,
    onBattery: () -> Unit = {},
    zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    backupStatus: BackupStatus = BackupStatus.Idle,
    onExport: (CharArray?) -> Unit = {},
    onExportCsv: () -> Unit = {},
    onRestore: (app.vyaya.core.backup.RestoreMode) -> Unit = {},
    onRestoreAnyway: () -> Unit = {},
    onPassword: (CharArray) -> Unit = {},
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    lockEnabled: Boolean = false,
    onLockEnabled: (Boolean) -> Unit = {},
    deviceSecure: Boolean = true,
    onRulesChanged: () -> Unit = {},
    /** A budget was saved: the app may ask to post budget alerts (Android 13+). */
    onBudgetSet: () -> Unit = {},
    /** The Detected tab: capture from notifications and SMS (Android); iOS has no such access. */
    showDetected: Boolean = true,
) = VyayaTheme {
    VyayaRootContent(db = db, setup = setup, clock = clock, opened = opened, onNotificationAccess = onNotificationAccess, onSms = onSms, onImport = onImport, onBattery = onBattery, zone = zone, backupStatus = backupStatus, onExport = onExport, onExportCsv = onExportCsv, onRestore = onRestore, onRestoreAnyway = onRestoreAnyway, onPassword = onPassword, locked = locked, onUnlock = onUnlock, lockEnabled = lockEnabled, onLockEnabled = onLockEnabled, deviceSecure = deviceSecure, onRulesChanged = onRulesChanged, showDetected = showDetected, onBudgetSet = onBudgetSet)
}

@Composable
private fun VyayaRootContent(
    db: VyayaDatabase,
    setup: Setup,
    clock: () -> Instant,
    opened: Int,
    onNotificationAccess: () -> Unit,
    onSms: () -> Unit,
    onImport: () -> Unit,
    onBattery: () -> Unit = {},
    zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    backupStatus: BackupStatus = BackupStatus.Idle,
    onExport: (CharArray?) -> Unit = {},
    onExportCsv: () -> Unit = {},
    onRestore: (app.vyaya.core.backup.RestoreMode) -> Unit = {},
    onRestoreAnyway: () -> Unit = {},
    onPassword: (CharArray) -> Unit = {},
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    lockEnabled: Boolean = false,
    onLockEnabled: (Boolean) -> Unit = {},
    deviceSecure: Boolean = true,
    onRulesChanged: () -> Unit = {},
    /** A budget was saved: the app may ask to post budget alerts (Android 13+). */
    onBudgetSet: () -> Unit = {},
    /** The Detected tab: capture from notifications and SMS (Android); iOS has no such access. */
    showDetected: Boolean = true,
) {
    // SEC-003: while locked, nothing of the money is composed at all.
    if (locked) return LockScreen(onUnlock)

    val months = remember(db) { MonthStore(db) }
    val entries = remember(db) { EntryStore(db) }
    val inbox = remember(db) { DetectedInbox(db) }
    var catalogVersion by remember { mutableStateOf(0) }
    val records by produceState<app.vyaya.core.backup.Snapshot?>(null, db, opened, catalogVersion) { value = SnapshotStore(db).loadRecords() }
    val catalog = remember(db) { app.vyaya.core.data.CatalogStore(db) }
    val merchants = remember(db) { app.vyaya.core.data.MerchantStore(db) }
    val budgetStore = remember(db) { app.vyaya.core.data.BudgetStore(db) }
    val monthStarts = remember(db) { app.vyaya.core.data.MonthStartStore(db) }
    val ignoredStore = remember(db) { app.vyaya.core.data.IgnoredStore(db) }
    var ignoredVersion by remember { mutableStateOf(0) }
    /** The Ignored tab's "This was a payment": the message being added (its amount and day fill the form). */
    var fromIgnored by remember { mutableStateOf<app.vyaya.core.data.IgnoredItem?>(null) }
    /** DATA-002: the ignored message, and the entry that looks like it was already logged for it. */
    var alreadyLogged by remember { mutableStateOf<Pair<app.vyaya.core.data.IgnoredItem, Entry>?>(null) }
    var monthStartVersion by remember { mutableStateOf(0) }
    /** "categories" or "accounts" while that list is open; [catalogItem] the one being added ("new") or changed. */
    var catalogView by remember { mutableStateOf<String?>(null) }
    var catalogItem by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var monthOffset by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val rulesStore = remember(db) { RulesStore(db) }
    // MVP2 FLAW-001: one store for the screen's life, so finished months are read once.
    val savingsStore = remember(db) { app.vyaya.core.data.SavingsStore(db) }
    var rulesVersion by remember { mutableStateOf(0) }
    val rules by produceState(emptyList<RecurringRule>(), db, opened, rulesVersion) { value = rulesStore.active() }
    var editingRule by remember { mutableStateOf<RecurringRule?>(null) }
    var addingRule by remember { mutableStateOf(false) }
    /** Recurring report #1: a rule whose amount changed, and this month's entry's old amount. */
    var askThisMonth by remember { mutableStateOf<Pair<RecurringRule, app.vyaya.core.model.Money>?>(null) }
    fun rulesChanged() { rulesVersion++; onRulesChanged() }

    // Lent and borrowed.
    val ledger = remember(db) { app.vyaya.core.data.LedgerStore(db) }
    var ledgerVersion by remember { mutableStateOf(0) }
    // Kept on screen while the new values load (produceState would blank them on every change: a flicker).
    var people by remember { mutableStateOf(emptyList<app.vyaya.core.model.Person>()) }
    var balances by remember { mutableStateOf(app.vyaya.core.engine.Balances.of(emptyList())) }
    var person by remember { mutableStateOf<app.vyaya.core.model.Person?>(null) }
    var personEntries by remember { mutableStateOf(emptyList<app.vyaya.core.model.LedgerEntry>()) }
    androidx.compose.runtime.LaunchedEffect(db, ledgerVersion) { people = ledger.people(); balances = ledger.balances() }
    androidx.compose.runtime.LaunchedEffect(person, ledgerVersion) { personEntries = person?.let { ledger.entries(it.id) } ?: emptyList() }
    /** Which ledger form is open: "person", "rename", "lent", "borrowed", "paidBack" or "edit" ([editingLedger]). */
    var ledgerForm by remember { mutableStateOf<String?>(null) }
    var editingLedger by remember { mutableStateOf<app.vyaya.core.model.LedgerEntry?>(null) }
    fun ledgerChanged() { ledgerVersion++ }
    // Branch review 2026-11-18 #7: an action that fails (the data refuses it, a damaged row) says so; it never crashes the
    // app. Nothing is logged: the error may hold the user's data.
    var actionFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Every action on this screen runs here: a failure is caught and said, so the scope (and the app) carries on. */
    fun safely(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = scope.launchSafely({ actionFailed = true }, block)

    // Shown over whatever screen is open (forms and lists return early below).
    if (actionFailed) androidx.compose.material3.AlertDialog(
        onDismissRequest = { actionFailed = false },
        title = { Text("That didn't work") },
        text = { Text("Nothing was changed. If it happens again, check the phone's date and time, or export a backup and tell us.") },
        confirmButton = { androidx.compose.material3.TextButton({ actionFailed = false }) { Text("OK") } },
    )

    val settings = records?.settings
    val month = remember(settings, monthOffset) {
        settings?.let { s ->
            var m = s.monthSchedule.monthContaining(clock(), s.homeTimeZone)
            repeat(kotlin.math.abs(monthOffset)) { m = if (monthOffset < 0) s.monthSchedule.previous(m) else s.monthSchedule.next(m) }
            m
        }
    }
    val view by remember(month, settings) { if (month != null && settings != null) months.watch(month, settings) else flowOf(null) }.collectAsState(null)
    val categories = records?.categories.orEmpty()

    /** Split bills: the form's message, or null when saved (the entry and the friends' ledger entries, together). */
    suspend fun saveSplit(input: EntryInput, existing: Entry?): String? {
        val entry = input.toEntry(existing, clock(), zone()) ?: return "This entry can't be saved"
        val mine = input.splitCategories.map { (id, text) ->
            app.vyaya.core.model.Split(id, Money.parse(text.trim())?.takeIf { it.paise > 0 } ?: return "Enter each part (up to 2 decimals)")
        }
        val friends = input.friendShares.map { (id, text) -> id to (Money.parse(text.trim())?.takeIf { it.paise > 0 } ?: return "Enter each friend's share") }
        return when (val r = app.vyaya.core.data.SplitStore(db).save(entry, mine, friends, clock())) {
            is app.vyaya.core.data.CatalogResult.Saved -> {
                if (friends.isNotEmpty()) ledgerChanged()
                fromIgnored?.let { ignoredStore.wasPayment(it.id); ignoredVersion++ }
                fromIgnored = null; adding = false; editing = null
                null
            }
            is app.vyaya.core.data.CatalogResult.Refused -> r.reason
        }
    }

    /** The form's message: none when [r] saved (then [onSaved]), else why the store refused. */
    fun <T> answer(r: app.vyaya.core.data.CatalogResult<T>, onSaved: () -> Unit): String? = when (r) {
        is app.vyaya.core.data.CatalogResult.Saved -> { onSaved(); null }
        is app.vyaya.core.data.CatalogResult.Refused -> r.reason
    }

    catalogView?.let { view ->
        val cats = records?.categories.orEmpty().filter { !it.deleted }.sortedWith(compareBy<app.vyaya.core.model.Category> { it.kind }.thenBy { it.name.lowercase() })
        val accs = records?.accounts.orEmpty().filter { !it.deleted }.sortedBy { it.name.lowercase() }
        fun changed() { catalogVersion++; catalogItem = null }
        val item = catalogItem
        when {
            view == "budgets" -> {
                val names = records?.categories.orEmpty().associate { it.id to it.name }
                val live = app.vyaya.core.engine.Budgets.live(records?.budgets.orEmpty(), records?.categories.orEmpty())
                    .sortedBy { names[it.categoryId].orEmpty().lowercase() }
                val budget = item?.let { i -> live.firstOrNull { it.id.value == i } }
                if (item != null && (item == "new" || budget != null)) {
                    val open = cats.filter { c -> c.kind == app.vyaya.core.model.CategoryKind.EXPENSE && live.none { it.categoryId == c.id } }
                    BudgetForm(budget, budget?.let { names[it.categoryId] }, open,
                        suggestion = { id -> records?.settings?.let { s -> budgetStore.suggestion(id, s, clock().toLocalDateTime(s.homeTimeZone).date) } },
                        onSave = { categoryId, amount ->
                            val limit = app.vyaya.core.model.Money.parse(amount.trim())?.takeIf { it.paise > 0 }
                            val category = (categoryId ?: budget?.categoryId)?.let { id -> records?.categories.orEmpty().firstOrNull { it.id == id } }
                            when {
                                category == null -> "Choose a category"
                                limit == null -> "Enter a monthly limit (up to 2 decimals, at most ₹100 crore)"
                                else -> answer(budgetStore.set(category, limit, clock())) { changed(); onBudgetSet() }
                            }
                        },
                        onRemove = { budget?.let { b -> safely { budgetStore.remove(b, clock()); changed() } } },
                        onClose = { catalogItem = null })
                } else CatalogList("Budgets", live.map { CatalogRow(it.id.value, names[it.categoryId].orEmpty(), "${it.limit.format()} a month") },
                    addLabel = "Add budget", onAdd = { catalogItem = "new" }, onOpen = { catalogItem = it }, onBack = { catalogView = null },
                    empty = "No budgets yet. Give a spending category a monthly limit, and Home shows how much of it is left.")
            }
            view == "payees" -> {
                val names = records?.categories.orEmpty().associate { it.id to it.name }
                val rules = records?.merchantRules.orEmpty().filter { !it.deleted }.sortedBy { it.payee.lowercase() }
                val rule = item?.let { i -> rules.firstOrNull { it.id.value == i } }
                if (rule != null) RememberedPayeeForm(payeeLabel(rule.payee), names[rule.categoryId].orEmpty(),
                    onForget = { safely { merchants.forget(rule, clock()); changed() } }, onClose = { catalogItem = null })
                else CatalogList("Remembered payees", rules.map { CatalogRow(it.id.value, payeeLabel(it.payee), "→ " + names[it.categoryId].orEmpty()) },
                    addLabel = null, onAdd = {}, onOpen = { catalogItem = it }, onBack = { catalogView = null },
                    empty = "None yet. When you put a detected payment in a category, tick \"Always put…\" and its payee's later payments go there too.")
            }
            item != null && view == "categories" -> {
                val c = records?.categories.orEmpty().firstOrNull { it.id.value == item } // kept if deleted meanwhile: Save says so
                CategoryForm(c, onSave = { name, kind ->
                    answer(if (c == null) catalog.addCategory(name, kind, clock()) else catalog.renameCategory(c, name, clock())) { changed() }
                }, deleteNote = { c?.let { deleteNote(catalog.uses(it)) } ?: "" },
                    onDelete = { c?.let { safely { catalog.deleteCategory(it, clock()); changed() } } }, onClose = { catalogItem = null })
            }
            item != null -> {
                val a = records?.accounts.orEmpty().firstOrNull { it.id.value == item }
                AccountForm(a, onSave = { name, kind, mask ->
                    answer(if (a == null) catalog.addAccount(name, kind, mask, clock()) else catalog.updateAccount(a, name, kind, mask, clock())) { changed() }
                }, deleteNote = { a?.let { deleteNote(catalog.uses(it)) } ?: "" },
                    onDelete = { a?.let { safely { catalog.deleteAccount(it, clock()); changed() } } }, onClose = { catalogItem = null })
            }
            view == "categories" -> CatalogList("Categories",
                cats.map { CatalogRow(it.id.value, it.name, if (it.kind == app.vyaya.core.model.CategoryKind.INCOME) "Income" else "Spending") },
                "Add category", onAdd = { catalogItem = "new" }, onOpen = { catalogItem = it }, onBack = { catalogView = null })
            else -> CatalogList("Accounts", accs.map { CatalogRow(it.id.value, accountLabel(it), it.kind.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                "Add account", onAdd = { catalogItem = "new" }, onOpen = { catalogItem = it }, onBack = { catalogView = null })
        }
        return
    }

    if (addingRule || editingRule != null) {
        val existing = editingRule
        RuleForm(existing, categories,
            onSave = { input ->
                val now = clock(); val zone = records?.settings?.homeTimeZone ?: zone()
                safely {
                    if (existing == null) rulesStore.add(input.type, input.money!!, input.dayOfMonth!!, input.categoryId, input.note.trim(), now, zone, input.accountId)
                    else {
                        val changed = existing.copy(amount = input.money!!, dayOfMonth = input.dayOfMonth!!, categoryId = input.categoryId, accountId = input.accountId,
                            note = input.note.trim(), updatedAt = now)
                        rulesStore.save(changed)
                        val thisMonth = rulesStore.thisMonthsEntry(changed, now, zone)
                        if (changed.amount != existing.amount && thisMonth != null && thisMonth.amount != changed.amount) askThisMonth = changed to thisMonth.amount
                    }
                    rulesChanged()
                }
                addingRule = false; editingRule = null
                true
            },
            onDelete = { existing?.let { r -> safely { rulesStore.delete(r, clock()); rulesChanged() } }; editingRule = null },
            onClose = { addingRule = false; editingRule = null },
            accounts = records?.accounts.orEmpty(),
        )
        return
    }

    ledgerForm?.let { form ->
        val today = clock().toLocalDateTime(settings?.homeTimeZone ?: zone()).date
        val who = person
        when (form) {
            "person" -> PersonForm(onSave = { name -> safely { ledger.addPerson(name, clock()); ledgerChanged() }; ledgerForm = null; true },
                onClose = { ledgerForm = null })
            "rename" -> if (who != null) PersonForm(initialName = who.name, onClose = { ledgerForm = null }, onSave = { name ->
                val renamed = who.copy(name = name.trim())
                safely { ledger.renamePerson(who, name, clock()); ledgerChanged() }
                person = renamed; ledgerForm = null; true
            })
            "edit" -> editingLedger?.let { entry ->
                LedgerForm(
                    title = "Change", initialAmount = plainRupees(entry.amount), initialNote = entry.note,
                    onSave = { amount, note -> safely { ledger.update(entry.copy(amount = amount, note = note.trim()), clock()); ledgerChanged() }; ledgerForm = null },
                    onDelete = { safely { ledger.delete(entry, clock()); ledgerChanged() }; ledgerForm = null },
                    onClose = { ledgerForm = null },
                )
            }
            else -> if (who != null) LedgerForm(
                title = when (form) { "lent" -> "I lent ${who.name}"; "borrowed" -> "I borrowed from ${who.name}"; else -> "Paid back" },
                onSave = { amount, note ->
                    safely {
                        when (form) {
                            "lent" -> ledger.add(who.id, app.vyaya.core.model.LedgerDirection.LENT, amount, today, note, clock())
                            "borrowed" -> ledger.add(who.id, app.vyaya.core.model.LedgerDirection.BORROWED, amount, today, note, clock())
                            else -> ledger.paidBack(who.id, amount, today, clock())
                        }
                        ledgerChanged()
                    }
                    ledgerForm = null
                },
                onClose = { ledgerForm = null },
                max = if (form == "paidBack") balances.of(who.id).let { if (it.paise < 0) app.vyaya.core.model.Money(0) - it else it } else null,
                maxMessage = balances.of(who.id).let { b ->
                    if (b.paise >= 0) "${who.name} owes you ${b.format()}; use Settle all to clear it"
                    else "You owe ${who.name} ${(app.vyaya.core.model.Money(0) - b).format()}; use Settle all to clear it"
                },
            )
        }
        return
    }

    if (adding || editing != null) {
        val existing = editing
        AddEntryScreen(
            existing, categories, today = clock().toLocalDateTime(zone()).date,
            onSave = { input ->
                val entry = input.toEntry(existing, clock(), zone())
                if (entry != null) {
                    val now = clock()
                    safely {
                        entries.save(listOf(entry))
                        // Merchant memory: later detected payments to this payee go in this category.
                        if (input.alwaysForPayee && entry.payee != null && entry.categoryId != null) {
                            merchants.remember(entry.payee!!, entry.categoryId!!, now)
                            if (input.alsoEarlier) merchants.applyToEarlier(entry.payee!!, entry.categoryId!!, entry.type, now)
                            catalogVersion++
                        }
                        // Added from the Ignored tab: that message is answered.
                        fromIgnored?.let { ignoredStore.wasPayment(it.id); ignoredVersion++ }
                        fromIgnored = null
                    }
                    adding = false; editing = null
                }
                entry != null
            },
            onDelete = {
                existing?.let { e -> safely { entries.save(listOf(e.markDeleted(clock()))) } }
                editing = null
            },
            onClose = { adding = false; editing = null; fromIgnored = null },
            accounts = records?.accounts.orEmpty(),
            rules = records?.merchantRules.orEmpty(),
            people = people,
            onAddPerson = { name -> ledger.addPerson(name, clock()).also { ledgerChanged() } },
            onSaveSplit = { input -> saveSplit(input, existing) },
            initialAmount = fromIgnored?.amount,
            initialType = fromIgnored?.let { if (it.credit) EntryType.INCOME else EntryType.EXPENSE },
            initialDate = fromIgnored?.receivedAt?.toLocalDateTime(zone())?.date,
            matches = { payee, type -> merchants.matches(payee, type) },
            keyOf = merchants::keyOf,
        )
        return
    }

    alreadyLogged?.let { (item, entry) ->
        val at = entry.occurredAt.instant.toLocalDateTime(records?.settings?.homeTimeZone ?: zone())
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { alreadyLogged = null },
            title = { Text("This looks already logged") },
            text = {
                Text("${entry.amount.format()} on ${at.date} at ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}" +
                    (entry.payee?.let { " to $it" } ?: "") + ". Open it, or add this one anyway.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    alreadyLogged = null
                    safely { ignoredStore.wasPayment(item.id); ignoredVersion++ }
                    editing = entry
                }) { Text("Open it") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton({ alreadyLogged = null }) { Text("Cancel") }
                    androidx.compose.material3.TextButton({ alreadyLogged = null; fromIgnored = item; adding = true }) { Text("Add anyway") }
                }
            },
        )
    }

    askThisMonth?.let { (rule, old) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askThisMonth = null },
            title = { Text(rule.note) },
            text = { Text("This month's entry was made with the old amount. Change it too? ${old.format()} → ${rule.amount.format()}") },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    askThisMonth = null
                    safely { rulesStore.applyToThisMonth(rule, clock(), records?.settings?.homeTimeZone ?: zone()) }
                }) { Text("Change this month's too") }
            },
            dismissButton = { androidx.compose.material3.TextButton({ askThisMonth = null }) { Text("Only from next month") } },
        )
    }

    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.filter { showDetected || it != Tab.DETECTED }.forEach { t -> NavigationBarItem(tab == t, { tab = t }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) }) }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.HOME -> {
                    val home = settings?.homeTimeZone ?: zone()
                    val today = clock().toLocalDateTime(home).date
                    val v = view
                    val end = if (v != null && settings != null) app.vyaya.core.engine.MonthEnd.of(v.entries, settings.monthSchedule, v.month, today) else null
                    // Recounted whenever the shown month changes (an entry added or edited changes it).
                    val savings by produceState<app.vyaya.core.data.SavingsSummary?>(null, settings, v) {
                        value = settings?.let { savingsStore.summary(it, today) }
                    }
                    // Safe to spend (Phase 3): the bills' made months are read again whenever the month or the rules change.
                    val made by produceState(emptySet<app.vyaya.core.model.Generation>(), v, rules) { value = rulesStore.madeKeys() }
                    val linked by produceState(emptySet<app.vyaya.core.model.EntityId>(), v, rules) { value = rulesStore.linkedPayments() }
                    val safe = v?.takeIf { monthOffset == 0 }?.let {
                        app.vyaya.core.engine.SafeToSpend.of(it.summary, rules, made, today, payments = it.entries, taken = linked)
                    }
                    // Safe-to-spend FLAW-002: the salary day, suggested on the current month until taken or put off.
                    val salaryDay by produceState<Int?>(null, v, monthStartVersion, monthOffset) {
                        value = if (monthOffset == 0) monthStarts.salarySuggestion(today) else null
                    }
                    HomeScreen(v, categories.associate { it.id to it.name }, { monthOffset-- }, { monthOffset++ },
                        onAdd = { adding = true }, onOpen = { editing = it }, today = today, monthEnd = end, savings = savings, safe = safe,
                        salaryDay = salaryDay,
                        onStartOn = { d -> safely { monthStarts.setStartDay(d, today); monthStartVersion++; catalogVersion++ } },
                        onNotNow = { d -> safely { monthStarts.notNow(d); monthStartVersion++ } },
                        // Budgets report #3: only the current month; a past month would be shown against today's limits.
                        budgets = v?.takeIf { monthOffset == 0 }?.let { app.vyaya.core.engine.Budgets.progress(records?.budgets.orEmpty(), it.summary, categories) }.orEmpty())
                }
                Tab.RECURRING -> RulesScreen(rules, categories.associate { it.id to it.name }, onAdd = { addingRule = true },
                    onOpen = { editingRule = it },
                    onPause = { r -> safely { rulesStore.pause(r, clock()); rulesChanged() } },
                    missed = { r -> rulesStore.missedWhilePaused(r, clock(), records?.settings?.homeTimeZone ?: zone()) },
                    onResume = { r, addMissed ->
                        safely { rulesStore.resume(r, addMissed, clock(), records?.settings?.homeTimeZone ?: zone()); rulesChanged() }
                    })
                Tab.PEOPLE -> {
                    val who = person
                    if (who == null) PeopleScreen(people, balances, onAddPerson = { ledgerForm = "person" }, onOpen = { person = it })
                    else PersonScreen(who, balances.of(who.id), personEntries,
                        onLent = { ledgerForm = "lent" }, onBorrowed = { ledgerForm = "borrowed" }, onPaidBack = { ledgerForm = "paidBack" },
                        onSettleAll = { safely { ledger.settleAll(who.id, clock()); ledgerChanged() } }, onBack = { person = null },
                        onEntry = { editingLedger = it; ledgerForm = "edit" }, onRename = { ledgerForm = "rename" },
                        onRemove = { safely { ledger.deletePerson(who, clock()); ledgerChanged() }; person = null })
                }
                Tab.DETECTED -> VyayaScreen(inbox, setup, clock, onNotificationAccess, onSms, onImport, onBattery,
                    ignored = { ignoredStore.items(clock()) }, ignoredVersion = ignoredVersion,
                    // DATA-002: a payment already logged for it is offered first, so it isn't added twice.
                    onWasPayment = { item -> safely {
                        val logged = ignoredStore.alreadyLogged(item)
                        if (logged != null) alreadyLogged = item to logged else { fromIgnored = item; adding = true }
                    } },
                    onNotPayment = { item -> safely { ignoredStore.notPayment(item.id); ignoredVersion++ } },
                    homeZone = records?.settings?.homeTimeZone ?: zone())
                Tab.BACKUP -> BackupScreen(backupStatus, onExport, onRestore, onRestoreAnyway, onPassword, lockEnabled, onLockEnabled, deviceSecure,
                    onCategories = { catalogView = "categories" }, onAccounts = { catalogView = "accounts" }, onExportCsv = onExportCsv,
                    onPayees = { catalogView = "payees" }, onBudgets = { catalogView = "budgets" },
                    startDay = records?.settings?.let { st -> st.monthSchedule.monthContaining(clock().toLocalDateTime(st.homeTimeZone).date).startDay },
                    nextStart = records?.settings?.let { st ->
                        val today = clock().toLocalDateTime(st.homeTimeZone).date
                        st.monthSchedule.changes.lastOrNull()?.takeIf { it.effectiveFrom > today }?.let { it.effectiveFrom to it.startDay }
                    },
                    onStartDay = { d -> safely {
                        val st = records?.settings ?: return@safely
                        monthStarts.setStartDay(d, clock().toLocalDateTime(st.homeTimeZone).date); monthStartVersion++; catalogVersion++
                    } })
            }
        }
    }
}

/** An amount as the user would type it: `250.5` → "250.50", `120` → "120". */
internal fun plainRupees(m: app.vyaya.core.model.Money): String =
    if (m.paise % 100 == 0L) "${m.paise / 100}" else "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"
