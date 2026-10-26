package app.vyaya.core.simulation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the user simulation and writes its report. Never fails on quality: findings go into the break-phase
 * report for discussion. Size: -Psim.users=N (default 200 so CI stays fast).
 */
class SimulationRunTest {

    @Test
    fun run_simulation_and_write_report() {
        val users = System.getProperty("sim.users")?.toIntOrNull() ?: 200
        val result = Simulation.run(users)
        val report = result.render()
        println(report)
        System.getProperty("sim.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(report) }
        assertTrue(result.messages > 0, "the simulation produced no messages")
    }
}
