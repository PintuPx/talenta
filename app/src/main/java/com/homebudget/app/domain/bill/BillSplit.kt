package com.homebudget.app.domain.bill

import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.mainGroupId
import com.homebudget.app.domain.money.Money

/** Amount for one category (a group or a main group) on a bill. */
data class SplitLine(val categoryId: Long, val amount: Long, val itemCount: Int, val descriptions: List<String>)

/** A main group with the groups (or the main group itself) that make up its share of the bill. */
data class SplitGroup(val mainGroupId: Long, val amount: Long, val lines: List<SplitLine>)

/**
 * How a bill is split across categories, and the expense transactions approving it creates:
 * one transaction per distinct category, for the sum of its included lines. Example: Veggie 40 +
 * Tomato 30 (Vegetables) and Milk 56 (Dairy & Eggs) gives two transactions, 70 and 56, both under
 * the main group Groceries in reports.
 */
object BillSplit {

    /** Included, categorised lines grouped by category, in first-appearance order. */
    fun postingLines(items: List<BillItem>): List<SplitLine> =
        items.filter { it.included && it.categoryId != null && it.amount > 0 }
            .groupBy { it.categoryId!! }
            .map { (catId, lines) -> SplitLine(catId, lines.sumOf { it.amount }, lines.size, lines.map { it.description }) }

    /** Posting lines grouped under their main group, largest first. */
    fun summary(items: List<BillItem>, categories: Map<Long, Category>): List<SplitGroup> =
        postingLines(items)
            .groupBy { categories.mainGroupId(it.categoryId) ?: it.categoryId }
            .map { (main, lines) -> SplitGroup(main, lines.sumOf { it.amount }, lines.sortedByDescending { it.amount }) }
            .sortedByDescending { it.amount }

    fun includedTotal(items: List<BillItem>): Long = items.filter { it.included }.sumOf { it.amount }

    /** Problems that block approval, as user-readable messages. Empty means the bill can be posted. */
    fun problems(items: List<BillItem>, accountId: Long?, categories: Map<Long, Category>): List<String> {
        val result = ArrayList<String>()
        if (accountId == null) result += "Choose the account the bill was paid from"
        val included = items.filter { it.included }
        if (included.isEmpty()) result += "Add at least one line item"
        included.forEachIndexed { _, item ->
            val label = item.description.ifBlank { "Line ${item.position + 1}" }
            when {
                item.amount <= 0 -> result += "$label: enter an amount greater than zero"
                item.amount > Money.MAX_AMOUNT -> result += "$label: amount is too large"
                item.categoryId == null -> result += "$label: choose a group or main group"
                categories[item.categoryId]?.kind != CategoryKind.EXPENSE -> result += "$label: choose an expense category"
            }
        }
        if (included.sumOf { it.amount } > Money.MAX_AMOUNT) result += "The bill total is too large"
        return result
    }

    /** Transaction note for a split line, e.g. "Bill: Veggie, Tomato". Kept within the 500-char note limit. */
    fun note(line: SplitLine): String {
        val text = "Bill: " + line.descriptions.joinToString(", ")
        return if (text.length <= 500) text else text.take(497) + "..."
    }
}
