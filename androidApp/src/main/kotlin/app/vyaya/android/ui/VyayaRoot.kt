package app.vyaya.android.ui

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
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.EntryStore
import app.vyaya.core.data.MonthStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.Entry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private enum class Tab(val label: String, val glyph: String) { HOME("Home", "₹"), DETECTED("Detected", "✓"), BACKUP("Backup", "⇅") }

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

    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t -> NavigationBarItem(tab == t, { tab = t }, icon = { Text(t.glyph) }, label = { Text(t.label) }) }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(view, categories.associate { it.id to it.name }, { monthOffset-- }, { monthOffset++ },
                    onAdd = { adding = true }, onOpen = { editing = it })
                Tab.DETECTED -> VyayaScreen(inbox, setup, clock, onNotificationAccess, onSms, onImport, onBattery)
                Tab.BACKUP -> BackupScreen(backupStatus, onExport, onRestore, onRestoreAnyway, onPassword, lockEnabled, onLockEnabled, deviceSecure)
            }
        }
    }
}
