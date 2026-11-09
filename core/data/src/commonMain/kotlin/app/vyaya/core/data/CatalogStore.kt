package app.vyaya.core.data

import app.vyaya.core.backup.Snapshot
import app.vyaya.core.model.Account
import app.vyaya.core.model.AccountKind
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import kotlin.time.Instant

/**
 * The user's categories and accounts (records; soft delete, rule 8.2): a deleted one is no longer offered, but entries
 * that used it keep its name. A category's type (spending or income) is set when it's made.
 */
class CatalogStore(private val db: VyayaDatabase) {

    private val snapshots = SnapshotStore(db)

    /** Categories offered: not deleted, spending first, by name. */
    suspend fun categories(): List<Category> = allCategories().filter { !it.deleted }

    /** Every category, deleted ones too (to name old entries). */
    suspend fun allCategories(): List<Category> =
        snapshots.loadRecords().categories.sortedWith(compareBy<Category> { it.kind }.thenBy { it.name.lowercase() })

    /** Null when a category of that type already has the name (any case) or the name isn't valid. */
    suspend fun addCategory(name: String, kind: CategoryKind, now: Instant): Category? {
        val n = name.trim()
        if (categories().any { it.kind == kind && it.name.equals(n, ignoreCase = true) }) return null
        return runCatching { Category(EntityId.random(), n, kind, now, false) }.getOrNull()?.also { save(categories = listOf(it)) }
    }

    // Each change applies to the record as it is now (by id), so an older copy can't undo an earlier change.
    suspend fun renameCategory(c: Category, name: String, now: Instant) = currentCategory(c)?.let { save(categories = listOf(it.copy(name = name.trim(), updatedAt = now))) }

    suspend fun deleteCategory(c: Category, now: Instant) = currentCategory(c)?.let { save(categories = listOf(it.copy(deleted = true, updatedAt = now))) }

    private suspend fun currentCategory(c: Category) = allCategories().firstOrNull { it.id == c.id }

    /** Accounts offered: not deleted, by name. */
    suspend fun accounts(): List<Account> = allAccounts().filter { !it.deleted }

    suspend fun allAccounts(): List<Account> = snapshots.loadRecords().accounts.sortedBy { it.name.lowercase() }

    /** Null when an account already has the name (any case), or the name or last digits ([mask], 3–6) aren't valid. */
    suspend fun addAccount(name: String, kind: AccountKind, mask: String?, now: Instant): Account? {
        val n = name.trim()
        if (accounts().any { it.name.equals(n, ignoreCase = true) }) return null
        return runCatching { Account(EntityId.random(), n, kind, mask?.trim()?.ifEmpty { null }, now, false) }.getOrNull()?.also { save(accounts = listOf(it)) }
    }

    suspend fun renameAccount(a: Account, name: String, now: Instant) = currentAccount(a)?.let { save(accounts = listOf(it.copy(name = name.trim(), updatedAt = now))) }

    suspend fun deleteAccount(a: Account, now: Instant) = currentAccount(a)?.let { save(accounts = listOf(it.copy(deleted = true, updatedAt = now))) }

    private suspend fun currentAccount(a: Account) = allAccounts().firstOrNull { it.id == a.id }

    private suspend fun save(categories: List<Category> = emptyList(), accounts: List<Account> = emptyList()) =
        snapshots.saveRecords(Snapshot.empty().copy(categories = categories, accounts = accounts))
}
