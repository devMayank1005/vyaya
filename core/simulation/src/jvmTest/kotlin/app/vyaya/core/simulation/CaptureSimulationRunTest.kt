package app.vyaya.core.simulation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the capture simulation and writes its report. Size: -Psim.capture.users=N */
class CaptureSimulationRunTest {

    @Test
    fun run_capture_simulation_and_write_report() {
        val users = System.getProperty("sim.capture.users")?.toIntOrNull() ?: 100
        val result = CaptureSimulation.run(users)
        val report = result.render()
        println(report)
        System.getProperty("sim.capture.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(report) }
        assertEquals(users, result.users)
    }
}
