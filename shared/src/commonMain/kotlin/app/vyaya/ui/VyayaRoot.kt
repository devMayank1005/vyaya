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
    onRestore: (app.vyaya.core.backup.RestoreMode) -> Unit = {},
    onRestoreAnyway: () -> Unit = {},
    onPassword: (CharArray) -> Unit = {},
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    lockEnabled: Boolean = false,
    onLockEnabled: (Boolean) -> Unit = {},
    deviceSecure: Boolean = true,
    onRulesChanged: () -> Unit = {},
    /** The Detected tab: capture from notifications and SMS (Android); iOS has no such access. */
    showDetected: Boolean = true,
) = VyayaTheme {
    VyayaRootContent(db = db, setup = setup, clock = clock, opened = opened, onNotificationAccess = onNotificationAccess, onSms = onSms, onImport = onImport, onBattery = onBattery, zone = zone, backupStatus = backupStatus, onExport = onExport, onRestore = onRestore, onRestoreAnyway = onRestoreAnyway, onPassword = onPassword, locked = locked, onUnlock = onUnlock, lockEnabled = lockEnabled, onLockEnabled = onLockEnabled, deviceSecure = deviceSecure, onRulesChanged = onRulesChanged, showDetected = showDetected)
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
    onRestore: (app.vyaya.core.backup.RestoreMode) -> Unit = {},
    onRestoreAnyway: () -> Unit = {},
    onPassword: (CharArray) -> Unit = {},
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    lockEnabled: Boolean = false,
    onLockEnabled: (Boolean) -> Unit = {},
    deviceSecure: Boolean = true,
    onRulesChanged: () -> Unit = {},
    /** The Detected tab: capture from notifications and SMS (Android); iOS has no such access. */
    showDetected: Boolean = true,
) {
    // SEC-003: while locked, nothing of the money is composed at all.
    if (locked) return LockScreen(onUnlock)

    val months = remember(db) { MonthStore(db) }
    val entries = remember(db) { EntryStore(db) }
    val inbox = remember(db) { DetectedInbox(db) }
    val records by produceState<app.vyaya.core.backup.Snapshot?>(null, db, opened) { value = SnapshotStore(db).loadRecords() }
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
    val scope = rememberCoroutineScope()

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

    if (addingRule || editingRule != null) {
        val existing = editingRule
        RuleForm(existing, categories,
            onSave = { input ->
                val now = clock(); val zone = records?.settings?.homeTimeZone ?: zone()
                scope.launch {
                    if (existing == null) rulesStore.add(input.type, input.money!!, input.dayOfMonth!!, input.categoryId, input.note.trim(), now, zone)
                    else {
                        val changed = existing.copy(amount = input.money!!, dayOfMonth = input.dayOfMonth!!, categoryId = input.categoryId,
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
            onDelete = { existing?.let { r -> scope.launch { rulesStore.delete(r, clock()); rulesChanged() } }; editingRule = null },
            onClose = { addingRule = false; editingRule = null },
        )
        return
    }

    ledgerForm?.let { form ->
        val today = clock().toLocalDateTime(settings?.homeTimeZone ?: zone()).date
        val who = person
        when (form) {
            "person" -> PersonForm(onSave = { name -> scope.launch { ledger.addPerson(name, clock()); ledgerChanged() }; ledgerForm = null; true },
                onClose = { ledgerForm = null })
            "rename" -> if (who != null) PersonForm(initialName = who.name, onClose = { ledgerForm = null }, onSave = { name ->
                val renamed = who.copy(name = name.trim())
                scope.launch { ledger.renamePerson(who, name, clock()); ledgerChanged() }
                person = renamed; ledgerForm = null; true
            })
            "edit" -> editingLedger?.let { entry ->
                LedgerForm(
                    title = "Change", initialAmount = plainRupees(entry.amount), initialNote = entry.note,
                    onSave = { amount, note -> scope.launch { ledger.update(entry.copy(amount = amount, note = note.trim()), clock()); ledgerChanged() }; ledgerForm = null },
                    onDelete = { scope.launch { ledger.delete(entry, clock()); ledgerChanged() }; ledgerForm = null },
                    onClose = { ledgerForm = null },
                )
            }
            else -> if (who != null) LedgerForm(
                title = when (form) { "lent" -> "I lent ${who.name}"; "borrowed" -> "I borrowed from ${who.name}"; else -> "Paid back" },
                onSave = { amount, note ->
                    scope.launch {
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
                    scope.launch { entries.save(listOf(entry)) }
                    adding = false; editing = null
                }
                entry != null
            },
            onDelete = {
                existing?.let { e -> scope.launch { entries.save(listOf(e.markDeleted(clock()))) } }
                editing = null
            },
            onClose = { adding = false; editing = null },
        )
        return
    }

    askThisMonth?.let { (rule, old) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askThisMonth = null },
            title = { Text(rule.note) },
            text = { Text("This month's entry was made with the old amount. Change it too? ${old.format()} → ${rule.amount.format()}") },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    askThisMonth = null
                    scope.launch { rulesStore.applyToThisMonth(rule, clock(), records?.settings?.homeTimeZone ?: zone()) }
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
                    HomeScreen(v, categories.associate { it.id to it.name }, { monthOffset-- }, { monthOffset++ },
                        onAdd = { adding = true }, onOpen = { editing = it }, today = today, monthEnd = end, savings = savings)
                }
                Tab.RECURRING -> RulesScreen(rules, categories.associate { it.id to it.name }, onAdd = { addingRule = true },
                    onOpen = { editingRule = it },
                    onPause = { r -> scope.launch { rulesStore.pause(r, clock()); rulesChanged() } },
                    missed = { r -> rulesStore.missedWhilePaused(r, clock(), records?.settings?.homeTimeZone ?: zone()) },
                    onResume = { r, addMissed ->
                        scope.launch { rulesStore.resume(r, addMissed, clock(), records?.settings?.homeTimeZone ?: zone()); rulesChanged() }
                    })
                Tab.PEOPLE -> {
                    val who = person
                    if (who == null) PeopleScreen(people, balances, onAddPerson = { ledgerForm = "person" }, onOpen = { person = it })
                    else PersonScreen(who, balances.of(who.id), personEntries,
                        onLent = { ledgerForm = "lent" }, onBorrowed = { ledgerForm = "borrowed" }, onPaidBack = { ledgerForm = "paidBack" },
                        onSettleAll = { scope.launch { ledger.settleAll(who.id, clock()); ledgerChanged() } }, onBack = { person = null },
                        onEntry = { editingLedger = it; ledgerForm = "edit" }, onRename = { ledgerForm = "rename" },
                        onRemove = { scope.launch { ledger.deletePerson(who, clock()); ledgerChanged() }; person = null })
                }
                Tab.DETECTED -> VyayaScreen(inbox, setup, clock, onNotificationAccess, onSms, onImport, onBattery)
                Tab.BACKUP -> BackupScreen(backupStatus, onExport, onRestore, onRestoreAnyway, onPassword, lockEnabled, onLockEnabled, deviceSecure)
            }
        }
    }
}

/** An amount as the user would type it: `250.5` → "250.50", `120` → "120". */
internal fun plainRupees(m: app.vyaya.core.model.Money): String =
    if (m.paise % 100 == 0L) "${m.paise / 100}" else "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"
