package com.homebudget.app.domain.report

import com.homebudget.app.domain.bill.CategorySuggester
import java.time.LocalDate

/** One approved bill line, e.g. "Sugar 1kg" ₹48 bought at Fresh Mart on 18 Nov. */
data class PurchasedLine(val description: String, val amount: Long, val date: LocalDate, val merchant: String)

data class PricePoint(val date: LocalDate, val amount: Long, val merchant: String, val description: String)

/** Price history of one item, oldest purchase first. */
data class ItemPriceHistory(val key: String, val label: String, val points: List<PricePoint>) {
    val latest: Long get() = points.last().amount
    val lowest: Long get() = points.minOf { it.amount }
    val highest: Long get() = points.maxOf { it.amount }

    /** Mean line amount, rounded down to whole minor units. */
    val average: Long get() = points.sumOf { it.amount } / points.size

    /** Change from the first to the latest purchase in whole percent, or null with fewer than 2 purchases. */
    val changePercent: Int?
        get() {
            if (points.size < 2) return null
            val first = points.first().amount
            if (first <= 0) return null
            return ((latest - first) * 100 / first).toInt()
        }
}

/**
 * Item price trends built from approved bill lines. Lines are matched by the same normalised name
 * the category suggester uses ("Sugar 1 kg" and "SUGAR" are the same item).
 *
 * Bills record the amount paid for a line, not a unit price; if the quantity changes between
 * purchases (1 kg vs 5 kg) the trend shows that too. The UI says so.
 */
object PriceHistory {

    fun build(lines: List<PurchasedLine>): List<ItemPriceHistory> = lines
        .mapNotNull { l -> CategorySuggester.key(l.description).takeIf { it.isNotEmpty() }?.let { it to l } }
        .groupBy({ it.first }, { it.second })
        .map { (key, group) ->
            val sorted = group.sortedBy { it.date }
            ItemPriceHistory(
                key = key,
                label = label(sorted.last().description, key),
                points = sorted.map { PricePoint(it.date, it.amount, it.merchant, it.description) },
            )
        }
        .sortedWith(compareByDescending<ItemPriceHistory> { it.points.size }.thenBy { it.label })

    /** Items whose name contains [query] (case-insensitive). */
    fun search(items: List<ItemPriceHistory>, query: String): List<ItemPriceHistory> {
        val q = CategorySuggester.key(query)
        if (q.isEmpty()) return items
        return items.filter { it.key.contains(q) }
    }

    private fun label(latestDescription: String, key: String): String {
        val words = key.split(' ')
        return words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifEmpty { latestDescription.trim() }
    }
}
