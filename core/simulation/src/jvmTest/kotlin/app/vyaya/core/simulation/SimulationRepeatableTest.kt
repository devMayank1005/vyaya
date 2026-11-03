package app.vyaya.core.simulation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** SIM-007: the same seed gives the same synthetic users and the same report, so runs can be compared. */
class SimulationRepeatableTest {

    @Test
    fun the_same_seed_gives_the_same_users() {
        for (persona in BackupPersona.entries.filter { it != BackupPersona.SHOPKEEPER_10Y }) {
            assertEquals(personaSnapshot(persona, Random(7), entryCap = 300), personaSnapshot(persona, Random(7), entryCap = 300), persona.name)
        }
    }

    @Test
    fun the_same_seed_gives_the_same_report() {
        assertEquals(BackupSimulation.run(40).render(), BackupSimulation.run(40).render())
    }
}
