package app.vyaya.core.dedup

import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.MerchantRule

/**
 * What the user said to always do for a payee ("Swiggy → Food"), as capture applies it (plan Phase 2, merchant memory).
 * - Payees match by [Capture.payeeKey], as de-dup does: "Swiggy", "SWIGGY" and "swiggy@axl" are one payee.
 * - A forgotten rule, or one whose category is deleted or unknown, does nothing (catalog report #7).
 * - The category must suit the payment: a Swiggy refund is income, so a spending category isn't put on it.
 * - Two rules for one payee (merged from two phones): the latest change wins.
 */
class MerchantMemory(rules: List<MerchantRule>, categories: List<Category>) {

    private val byKey: Map<String, Category> = run {
        val usable = categories.filter { !it.deleted }.associateBy { it.id }
        rules.filter { !it.deleted }
            .groupBy { Capture.payeeKey(it.payee) }
            .filterKeys { it.isNotEmpty() }
            .mapNotNull { (key, same) -> usable[same.maxBy { it.updatedAt }.categoryId]?.let { key to it } }
            .toMap()
    }

    /** The remembered category for [e]'s payee, if it suits [e]'s type. */
    fun categoryFor(e: Entry): EntityId? {
        val category = e.payee?.let { byKey[Capture.payeeKey(it)] } ?: return null
        val suits = when (e.type) {
            EntryType.EXPENSE -> category.kind == CategoryKind.EXPENSE
            EntryType.INCOME -> category.kind == CategoryKind.INCOME
            else -> false
        }
        return if (suits) category.id else null
    }

    companion object {
        val NONE = MerchantMemory(emptyList(), emptyList())
    }
}
