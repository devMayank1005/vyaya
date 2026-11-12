package app.vyaya.core.data

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.backup.Snapshot
import app.vyaya.core.data.CatalogResult.Refused
import app.vyaya.core.data.CatalogResult.Saved
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import kotlin.time.Instant

/** What a change to a category or account did: saved (as [Saved.item]), or refused with a message for the form. */
sealed interface CatalogResult<out T> {
    data class Saved<T>(val item: T) : CatalogResult<T>
    data class Refused(val reason: String) : CatalogResult<Nothing>
}

/**
 * The user's categories and accounts (records; soft delete, rule 8.2): a deleted one is no longer offered, but entries
 * that used it keep its name. A category's type (spending or income) is set when it's made. Every check is made here,
 * and its message is what the form shows (catalog report #9).
 */
class CatalogStore(private val db: VyayaDatabase, private val limits: BackupLimits = BackupLimits()) {

    private val snapshots = SnapshotStore(db)

    /** How many entries (not deleted) and recurring rules (not deleted) use a category or account. */
    data class Uses(val entries: Int, val rules: Int)

    /** Categories offered: not deleted, spending first, by name. */
    suspend fun categories(): List<Category> = allCategories().filter { !it.deleted }

    /** Every category, deleted ones too (to name old entries). */
    suspend fun allCategories(): List<Category> =
        snapshots.loadRecords().categories.sortedWith(compareBy<Category> { it.kind }.thenBy { it.name.lowercase() })

    /**
     * A new category; or, when a deleted one of that type had the name, that one back (catalog report #3), so old and
     * new entries stay in one category.
     */
    suspend fun addCategory(name: String, kind: CategoryKind, now: Instant): CatalogResult<Category> {
        val n = name.trim()
        nameProblem(n)?.let { return Refused(it) }
        val all = allCategories()
        val same = all.filter { it.kind == kind && it.name.equals(n, ignoreCase = true) }
        if (same.any { !it.deleted }) return Refused(CATEGORY_TAKEN)
        same.firstOrNull()?.let { return saveCategory(it.copy(name = n, deleted = false, updatedAt = now)) }
        if (all.size >= limits.maxRecordsPerType) return Refused(overLimit("categories")) // catalog report #2
        return saveCategory(Category(EntityId.random(), n, kind, now, false))
    }

    // Each change applies to the record as it is now (by id), so an older copy can't undo an earlier change.
    suspend fun renameCategory(c: Category, name: String, now: Instant): CatalogResult<Category> {
        val current = categories().firstOrNull { it.id == c.id } ?: return Refused(GONE)
        val n = name.trim()
        nameProblem(n)?.let { return Refused(it) }
        if (categories().any { it.id != c.id && it.kind == current.kind && it.name.equals(n, ignoreCase = true) }) return Refused(CATEGORY_TAKEN)
        return saveCategory(current.copy(name = n, updatedAt = now))
    }

    suspend fun deleteCategory(c: Category, now: Instant) {
        allCategories().firstOrNull { it.id == c.id }?.let { saveCategory(it.copy(deleted = true, updatedAt = now)) }
    }

    /** Accounts offered: not deleted, by name. */
    suspend fun accounts(): List<Account> = allAccounts().filter { !it.deleted }

    suspend fun allAccounts(): List<Account> = snapshots.loadRecords().accounts.sortedBy { it.name.lowercase() }

    /** A new account; or a deleted one with the name back, with this type and these last digits ([mask], 3–6). */
    suspend fun addAccount(name: String, kind: AccountKind, mask: String?, now: Instant): CatalogResult<Account> {
        val n = name.trim()
        val m = mask?.trim()?.ifEmpty { null }
        (nameProblem(n) ?: maskProblem(m))?.let { return Refused(it) }
        val all = allAccounts()
        val same = all.filter { it.name.equals(n, ignoreCase = true) }
        if (same.any { !it.deleted }) return Refused(ACCOUNT_TAKEN)
        same.firstOrNull()?.let { return saveAccount(it.copy(name = n, kind = kind, mask = m, deleted = false, updatedAt = now)) }
        if (all.size >= limits.maxRecordsPerType) return Refused(overLimit("accounts"))
        return saveAccount(Account(EntityId.random(), n, kind, m, now, false))
    }

    /** Changes an account's name, type and last digits together (catalog report #1). */
    suspend fun updateAccount(a: Account, name: String, kind: AccountKind, mask: String?, now: Instant): CatalogResult<Account> {
        val current = accounts().firstOrNull { it.id == a.id } ?: return Refused(GONE)
        val n = name.trim()
        val m = mask?.trim()?.ifEmpty { null }
        (nameProblem(n) ?: maskProblem(m))?.let { return Refused(it) }
        if (accounts().any { it.id != a.id && it.name.equals(n, ignoreCase = true) }) return Refused(ACCOUNT_TAKEN)
        return saveAccount(current.copy(name = n, kind = kind, mask = m, updatedAt = now))
    }

    suspend fun deleteAccount(a: Account, now: Instant) {
        allAccounts().firstOrNull { it.id == a.id }?.let { saveAccount(it.copy(deleted = true, updatedAt = now)) }
    }

    /** What deleting [c] would leave pointing at it (catalog report #5, #7). A split's own category isn't counted. */
    suspend fun uses(c: Category): Uses =
        Uses(db.entries().countWithCategory(c.id.value), RulesStore(db).active().count { it.categoryId == c.id })

    /** As [uses] for a category; an entry counts when money left or reached [a]. */
    suspend fun uses(a: Account): Uses =
        Uses(db.entries().countWithAccount(a.id.value), RulesStore(db).active().count { it.accountId == a.id })

    private fun nameProblem(n: String): String? = when {
        n.isBlank() -> "Enter a name"
        n.length > MAX_NAME -> "A name can be up to $MAX_NAME characters"
        else -> null
    }

    private fun maskProblem(m: String?): String? =
        if (m != null && (m.length !in 3..6 || !m.all { it in '0'..'9' })) "Last digits: 3 to 6 digits" else null

    private fun overLimit(what: String) = "Vyaya can keep up to ${limits.maxRecordsPerType} $what, deleted ones included"

    // The checks above are the model's own (name 1–100 characters, last digits 3–6), made first so a bad value is a
    // message for the form rather than an exception (report #8).
    private suspend fun saveCategory(c: Category): CatalogResult<Category> =
        Saved(c).also { snapshots.saveRecords(Snapshot.empty().copy(categories = listOf(c))) }

    private suspend fun saveAccount(a: Account): CatalogResult<Account> =
        Saved(a).also { snapshots.saveRecords(Snapshot.empty().copy(accounts = listOf(a))) }

    private companion object {
        const val MAX_NAME = 100
        const val CATEGORY_TAKEN = "A category of this type already has that name"
        const val ACCOUNT_TAKEN = "An account already has that name"
        const val GONE = "This was deleted meanwhile"
    }
}
