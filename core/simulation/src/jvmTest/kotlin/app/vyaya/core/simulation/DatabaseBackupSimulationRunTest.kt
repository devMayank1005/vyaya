package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.Restore
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.BackupStore
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * The backup simulation through the database (plan.md Part E): each user's data is on the database; export, merge and
 * replace restores and damaged files all go through [BackupStore]. Reports only. Size: -Psim.backup.users=N
 */
class DatabaseBackupSimulationRunTest {

    private fun db(): VyayaDatabase = Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()

    private suspend fun export(db: VyayaDatabase): String = StringBuilder().also { out -> BackupStore(db, pageSize = 50).export(META) { out.append(it).append('\n') } }.toString()

    @Test
    fun run_database_backup_simulation(): Unit = runBlocking {
        val users = System.getProperty("sim.backup.users")?.toIntOrNull() ?: 100
        val random = Random(11)
        var exportSame = 0; var exportDiffers = 0
        var mergeSame = 0; var mergeDiffers = 0; var replaceSame = 0; var replaceDiffers = 0
        var fuzz = 0; var refusedUnchanged = 0; var refusedChanged = 0; var acceptedAsReader = 0; var acceptedOtherwise = 0; var crashed = 0
        val notes = mutableListOf<String>()
        val started = System.nanoTime()
        repeat(users) { u ->
            val persona = if (random.nextBoolean()) BackupPersona.STUDENT else BackupPersona.SALARIED
            val a = personaSnapshot(persona, random, entryCap = 300)
            val db = db()
            try {
                SnapshotStore(db).save(a)
                val text = export(db)
                if (text == BackupCodec.encode(a, META)) exportSame++ else { exportDiffers++; if (notes.size < 5) notes += "user $u: export differs" }

                // A second phone: part of A's data edited later, plus its own records; merged into A, and replacing A.
                val b = personaSnapshot(persona, random, entryCap = 100).let { own ->
                    own.copy(entries = own.entries + a.entries.filter { random.nextInt(4) == 0 }.map { it.copy(note = "on B", updatedAt = it.updatedAt.coerceAtMost(META.exportedAt)) })
                }
                // B's records reference B's accounts and categories; A's records edited on B reference A's, so B carries those too.
                val bFull = b.copy(accounts = b.accounts + a.accounts, categories = b.categories + a.categories, recurring = b.recurring + a.recurring)
                val bText = BackupCodec.encode(bFull, META)
                for (mode in RestoreMode.entries) {
                    val target = db()
                    try {
                        SnapshotStore(target).save(a)
                        val r = BackupStore(target, pageSize = 37).restore(bText.lineSequence(), mode, NOW)
                        val same = r is RestoreResult.Restored && SnapshotStore(target).load() == Restore.apply(a, bFull, mode)
                        when (mode) {
                            RestoreMode.MERGE -> if (same) mergeSame++ else { mergeDiffers++; if (notes.size < 5) notes += "user $u: merge differs ($r)" }
                            RestoreMode.REPLACE -> if (same) replaceSame++ else { replaceDiffers++; if (notes.size < 5) notes += "user $u: replace differs ($r)" }
                        }
                    } finally { target.close() }
                }

                // Damaged files: refused leaves the database as it was; accepted gives exactly what the reader gives.
                val before = SnapshotStore(db).load()
                repeat(5) {
                    fuzz++
                    val i = random.nextInt(text.length)
                    val mutated = text.substring(0, i) + "0123456789abcdef\"{}:,"[random.nextInt(21)] + text.substring(i + 1)
                    try {
                        val target = db()
                        try {
                            SnapshotStore(target).save(a)
                            when (val r = BackupStore(target, pageSize = 25).restore(mutated.lineSequence(), RestoreMode.REPLACE, NOW)) {
                                is RestoreResult.Refused -> if (SnapshotStore(target).load() == before) refusedUnchanged++ else refusedChanged++
                                is RestoreResult.Restored -> {
                                    val reader = BackupCodec.decode(mutated, NOW)
                                    if (reader is DecodeResult.Success && SnapshotStore(target).load() == reader.snapshot) acceptedAsReader++ else acceptedOtherwise++
                                }
                            }
                        } finally { target.close() }
                    } catch (t: Throwable) {
                        crashed++
                        if (notes.size < 5) notes += "user $u: ${t::class.simpleName}: ${t.message?.take(100)}"
                    }
                }
            } finally { db.close() }
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        val report = buildString {
            appendLine("Database backup simulation: $users users (${"%.1f".format(seconds)} s)")
            appendLine("export from the database = codec's file: $exportSame same, $exportDiffers differ")
            appendLine("merge restore = Restore.apply: $mergeSame same, $mergeDiffers differ")
            appendLine("replace restore = Restore.apply: $replaceSame same, $replaceDiffers differ")
            appendLine("damaged files: $fuzz; refused with the database unchanged $refusedUnchanged, refused but changed $refusedChanged, " +
                "accepted as the reader does $acceptedAsReader, accepted otherwise $acceptedOtherwise, crashed $crashed")
            notes.forEach { appendLine("  $it") }
        }
        println(report)
        System.getProperty("sim.backup.report")?.let { File(it).resolveSibling("backup-db-report.txt").apply { parentFile.mkdirs() }.writeText(report) }
    }
}
