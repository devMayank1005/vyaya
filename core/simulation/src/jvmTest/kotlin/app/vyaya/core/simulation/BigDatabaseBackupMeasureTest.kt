package app.vyaya.core.simulation

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.BackupStore
import app.vyaya.core.data.SnapshotStore
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Money
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test

/**
 * Backups of a large database through files, as the app does: export streams the tables page by page (PEN-003), restore
 * reads the file and writes it in one transaction. Run under a phone-like heap: -Psim.backup.big=true -Psim.heap=256m.
 * Reports only; never fails on quality.
 */
class BigDatabaseBackupMeasureTest {

    private fun database(file: File): VyayaDatabase =
        Room.databaseBuilder<VyayaDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()

    /** Fills [db] with about [n] entries, 10,000 at a time, so the test itself never holds them all. */
    private suspend fun fill(db: VyayaDatabase, n: Int) {
        val base = personaSnapshot(BackupPersona.SHOPKEEPER_10Y, Random(n.toLong()), entryCap = 1_000)
        val store = SnapshotStore(db)
        store.save(base)
        val random = Random(1)
        var made = base.entries.size
        while (made < n) {
            val chunk = List(minOf(10_000, n - made)) { i ->
                base.entries[i % 1_000].copy(id = EntityId.random(), generatedBy = null, amount = Money(random.nextLong(100, 50_000_00)))
            }
            store.save(base.copy(entries = chunk))
            made += chunk.size
        }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun measure_large_database_backups(): Unit = runBlocking {
        if (System.getProperty("sim.backup.big") != "true") return@runBlocking
        val rt = Runtime.getRuntime()
        val lines = mutableListOf("max heap ${rt.maxMemory() / 1024 / 1024} MB")
        val dir = File(System.getProperty("java.io.tmpdir"), "vyaya-db-measure-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (n in listOf(10_000, 100_000, 200_000)) {
                val a = database(File(dir, "a-$n.db")); val b = database(File(dir, "b-$n.db"))
                val file = File(dir, "backup-$n.txt"); val again = File(dir, "again-$n.txt")
                try {
                    fill(a, n)
                    System.gc()
                    var peak = 0L
                    fun sample() { peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) }
                    var t = System.nanoTime()
                    val exported = file.bufferedWriter().use { w -> BackupStore(a).export(META) { w.write(it); w.write("\n"); sample() } }
                    val exportMs = (System.nanoTime() - t) / 1_000_000
                    val exportPeak = peak / 1024 / 1024
                    System.gc(); peak = 0
                    t = System.nanoTime()
                    val restored = file.bufferedReader().useLines { BackupStore(b).restore(it.onEach { sample() }, RestoreMode.REPLACE, NOW) }
                    sample()
                    val restoreMs = (System.nanoTime() - t) / 1_000_000
                    val restorePeak = peak / 1024 / 1024
                    again.bufferedWriter().use { w -> BackupStore(b).export(META) { w.write(it); w.write("\n") } }
                    val same = sha(file) == sha(again)
                    val ok = exported is EncodeResult.Success && restored is RestoreResult.Restored && same
                    lines += "entries~$n file=${file.length() / 1024 / 1024}MB export=${exportMs}ms (heap peak~${exportPeak}MB) " +
                        "restore=${restoreMs}ms (heap peak~${restorePeak}MB) fidelity=${if (ok) "ok" else "BROKEN export=$exported restore=$restored same=$same"}"
                } catch (e: OutOfMemoryError) {
                    lines += "entries~$n OUT OF MEMORY"
                    break
                } finally {
                    a.close(); b.close()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        val report = lines.joinToString("\n")
        println(report)
        System.getProperty("sim.backup.report")?.let { File(it).resolveSibling("backup-db-big-report.txt").writeText(report) }
    }
}
