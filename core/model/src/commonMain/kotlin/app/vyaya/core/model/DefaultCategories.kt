package app.vyaya.core.model

import kotlin.time.Instant

/**
 * The categories a new install starts with (the user renames, deletes or adds their own). Each id is derived from a
 * fixed key, so two phones set up separately and later merged by a backup restore don't get two "Food & dining".
 */
object DefaultCategories {

    private val SPENDING = listOf(
        "food" to "Food & dining", "groceries" to "Groceries", "transport" to "Transport", "shopping" to "Shopping",
        "bills" to "Bills & utilities", "rent" to "Rent", "health" to "Health", "entertainment" to "Entertainment",
        "education" to "Education", "travel" to "Travel", "other-spending" to "Other",
    )
    private val INCOME = listOf("salary" to "Salary", "other-income" to "Other income")

    fun create(at: Instant): List<Category> =
        SPENDING.map { (key, name) -> Category(id(key), name, CategoryKind.EXPENSE, at, false) } +
            INCOME.map { (key, name) -> Category(id(key), name, CategoryKind.INCOME, at, false) }

    private fun id(key: String) = EntityId.derived("vyaya.category.v1|$key")
}
