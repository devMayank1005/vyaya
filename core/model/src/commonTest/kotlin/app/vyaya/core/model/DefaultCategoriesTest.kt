package app.vyaya.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The categories a new install starts with; the same ids on every phone, so merging two phones' backups can't double them. */
class DefaultCategoriesTest {

    private val t = Instant.parse("2026-11-06T10:00:00Z")

    @Test
    fun the_same_ids_on_every_phone_and_none_is_a_system_id() {
        val a = DefaultCategories.create(t)
        val b = DefaultCategories.create(Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.size, a.map { it.id }.toSet().size)
        assertFalse(a.any { it.id.value.startsWith("sys.") })
        assertTrue(a.all { it.updatedAt == t && !it.deleted })
    }

    @Test
    fun spending_and_income_categories() {
        val kinds = DefaultCategories.create(t).groupBy({ it.kind }, { it.name })
        assertTrue("Food & dining" in kinds.getValue(CategoryKind.EXPENSE))
        assertTrue("Salary" in kinds.getValue(CategoryKind.INCOME))
        assertEquals(setOf(CategoryKind.EXPENSE, CategoryKind.INCOME), kinds.keys)
    }
}
