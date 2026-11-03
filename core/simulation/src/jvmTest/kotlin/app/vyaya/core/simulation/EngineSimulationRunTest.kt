package app.vyaya.core.simulation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the engine simulation and writes its report. Size: -Psim.engine.users=N */
class EngineSimulationRunTest {

    @Test
    fun run_engine_simulation_and_write_report() {
        val users = System.getProperty("sim.engine.users")?.toIntOrNull() ?: 100
        val result = EngineSimulation.run(users)
        val report = result.render()
        println(report)
        System.getProperty("sim.engine.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(report) }
        assertEquals(users, result.users)
    }
}
