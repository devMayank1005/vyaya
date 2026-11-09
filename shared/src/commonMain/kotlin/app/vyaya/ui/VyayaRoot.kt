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
    HOME("Home", Icons.Filled.Home), RECURRING("Recurring", Icons.Filled.Refresh), DETECTED("Detected", Icons.Filled.Notifications),
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
    var rulesVersion by remember { mutableStateOf(0) }
    val rules by produceState(emptyList<RecurringRule>(), db, opened, rulesVersion) { value = rulesStore.active() }
    var editingRule by remember { mutableStateOf<RecurringRule?>(null) }
    var addingRule by remember { mutableStateOf(false) }
    /** Recurring report #1: a rule whose amount changed, and this month's entry's old amount. */
    var askThisMonth by remember { mutableStateOf<Pair<RecurringRule, app.vyaya.core.model.Money>?>(null) }
    fun rulesChanged() { rulesVersion++; onRulesChanged() }
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
                        value = settings?.let { app.vyaya.core.data.SavingsStore(db).summary(it, today) }
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
                Tab.DETECTED -> VyayaScreen(inbox, setup, clock, onNotificationAccess, onSms, onImport, onBattery)
                Tab.BACKUP -> BackupScreen(backupStatus, onExport, onRestore, onRestoreAnyway, onPassword, lockEnabled, onLockEnabled, deviceSecure)
            }
        }
    }
}
