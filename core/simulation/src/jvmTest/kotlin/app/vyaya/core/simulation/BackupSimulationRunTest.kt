package app.vyaya.core.simulation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs the backup simulation and writes its report. Never fails on quality. Size: -Psim.backup.users=N */
class BackupSimulationRunTest {

    @Test
    fun run_backup_simulation_and_write_report() {
        val users = System.getProperty("sim.backup.users")?.toIntOrNull() ?: 100
        val result = BackupSimulation.run(users)
        val report = result.render()
        println(report)
        System.getProperty("sim.backup.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(report) }
        assertTrue(result.users > 0)
    }
}
