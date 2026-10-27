package app.vyaya.core.simulation

import app.vyaya.core.backup.BackupCodec
import app.vyaya.core.backup.CsvExport
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Large backups streamed through a real file, as the app will do, under the test JVM's heap (512 MB by
 * default, similar to an Android app's limit). Opt-in because it is slow: -Psim.backup.big=true.
 * Reports only; never fails on quality.
 */
class BigBackupMeasureTest {

    @Test
    fun measure_large_backups() {
        if (System.getProperty("sim.backup.big") != "true") return
        val rt = Runtime.getRuntime()
        val lines = mutableListOf("max heap ${rt.maxMemory() / 1024 / 1024} MB")
        for (n in listOf(10_000, 100_000, 200_000, 400_000)) {
            System.gc()
            val file = File.createTempFile("vyaya-backup", ".txt").apply { deleteOnExit() }
            try {
                val snapshot = personaSnapshot(BackupPersona.SHOPKEEPER_10Y, Random(n.toLong()), entryCap = n)
                var t = System.nanoTime()
                val encoded = file.bufferedWriter().use { w -> BackupCodec.write(snapshot, META) { w.write(it); w.write("\n") } }
                val encodeMs = (System.nanoTime() - t) / 1_000_000
                t = System.nanoTime()
                val decoded = file.bufferedReader().useLines { BackupCodec.read(it, NOW) }
                val decodeMs = (System.nanoTime() - t) / 1_000_000
                val ok = encoded is EncodeResult.Success && decoded is DecodeResult.Success && decoded.snapshot == snapshot.normalized()
                t = System.nanoTime()
                val csvChars = CsvExport.export(snapshot).length
                val csvMs = (System.nanoTime() - t) / 1_000_000
                val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                lines += "entries=$n file=${file.length() / 1024 / 1024}MB write=${encodeMs}ms read=${decodeMs}ms fidelity=${if (ok) "ok" else "BROKEN $decoded"} csv=${csvChars / 1_000_000}M chars ${csvMs}ms heapUsed~${used}MB"
            } catch (e: OutOfMemoryError) {
                lines += "entries=$n OUT OF MEMORY"
                break
            } finally {
                file.delete()
            }
        }
        val report = lines.joinToString("\n")
        println(report)
        System.getProperty("sim.backup.report")?.let { File(it).resolveSibling("backup-big-report.txt").writeText(report) }
    }
}
