package app.vyaya.core.backup

import app.vyaya.core.model.Person
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RestoreTest {

    private val earlier = Instant.parse("2026-01-01T00:00:00Z")
    private val later = Instant.parse("2026-06-01T00:00:00Z")

    @Test
    fun replace_takes_the_backup_entirely() {
        val current = randomSnapshot(Random(1))
        assertEquals(GOLDEN_SNAPSHOT.normalized(), Restore.apply(current, GOLDEN_SNAPSHOT, RestoreMode.REPLACE))
    }

    @Test
    fun merge_keeps_the_newer_version_of_each_record_and_current_settings() {
        val mine = Person(RAHUL, "Rahul (mine)", later, false)
        val theirs = Person(RAHUL, "Rahul (backup)", earlier, false)
        val extra = Person(id("00000000-0000-4000-8000-0000000000aa"), "Priya", earlier, false)
        val current = Snapshot.empty().copy(people = listOf(mine))
        val backup = GOLDEN_SNAPSHOT.copy(people = listOf(theirs, extra), ledger = emptyList())
        val merged = Restore.apply(current, backup, RestoreMode.MERGE)
        assertEquals(listOf(mine, extra).sortedBy { it.id.value }, merged.people)
        assertEquals(current.settings, merged.settings)
        assertEquals(GOLDEN_SNAPSHOT.entries, merged.entries)
    }

    @Test
    fun a_newer_record_in_the_backup_replaces_the_current_one() {
        val mine = Person(RAHUL, "old name", earlier, false)
        val theirs = Person(RAHUL, "new name", later, false)
        val merged = Restore.apply(Snapshot.empty().copy(people = listOf(mine)), Snapshot.empty().copy(people = listOf(theirs)), RestoreMode.MERGE)
        assertEquals(listOf(theirs), merged.people)
    }

    @Test
    fun on_equal_timestamps_the_current_record_wins() {
        val mine = Person(RAHUL, "mine", earlier, false)
        val theirs = Person(RAHUL, "theirs", earlier, false)
        val merged = Restore.apply(Snapshot.empty().copy(people = listOf(mine)), Snapshot.empty().copy(people = listOf(theirs)), RestoreMode.MERGE)
        assertEquals(listOf(mine), merged.people)
    }

    @Test
    fun merge_is_idempotent_and_loses_nothing() = runTest {
        checkAll(100, Arb.long(), Arb.long()) { a, b ->
            val current = randomSnapshot(Random(a))
            val backup = randomSnapshot(Random(b))
            val merged = Restore.apply(current, backup, RestoreMode.MERGE)
            assertEquals(merged, Restore.apply(merged, backup, RestoreMode.MERGE), "merging the same backup twice")
            assertEquals(current.normalized(), Restore.apply(current, current, RestoreMode.MERGE), "merging with itself")
            val ids = (current.entries + backup.entries).map { it.id }.toSet()
            assertEquals(ids, merged.entries.map { it.id }.toSet(), "no entry lost")
            merged.entries.forEach { e ->
                val newest = (current.entries + backup.entries).filter { it.id == e.id }.maxOf { it.updatedAt }
                assertEquals(newest, e.updatedAt)
            }
        }
    }
}
