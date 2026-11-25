package com.homebudget.app.domain.budget

import com.homebudget.app.domain.finance.BudgetUsage
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.report.Reports
import java.time.YearMonth

/**
 * Suggests a monthly budget from past spending. Integer math only.
 *
 * Rule: take the net expense of up to the last [MAX_MONTHS] complete months that had spending,
 * use the average for 1-2 months and the median for 3 or more (so one unusual month does not
 * move it), then round up to the next 100 currency units.
 */
object BudgetSuggestion {
    const val MAX_MONTHS = 6

    /** Suggestions are rounded up to a multiple of this (100 major units at a scale of 100). */
    const val ROUND_TO = 10_000L

    /**
     * Net expense per month (transfers excluded, refunds subtracted, via [Reports.monthlyTotals]) for the
     * [MAX_MONTHS] months before [currentMonth], most recent first. Months without spending are kept as 0
     * here and ignored by [suggest].
     */
    fun monthlyExpenses(transactions: List<Transaction>, currentMonth: YearMonth): List<Long> {
        val months = Reports.monthsEnding(currentMonth.minusMonths(1), MAX_MONTHS).reversed()
        return Reports.monthlyTotals(transactions, months).map { it.expense }
    }

    /** How many of [monthlyExpenses] count (months with spending, at most [MAX_MONTHS]). */
    fun monthsUsed(monthlyExpenses: List<Long>): Int = monthlyExpenses.count { it > 0 }.coerceAtMost(MAX_MONTHS)

    /** Suggested monthly budget in minor units, or null when there is no history. [monthlyExpenses] is most recent first. */
    fun suggest(monthlyExpenses: List<Long>): Long? {
        val used = monthlyExpenses.filter { it > 0 }.take(MAX_MONTHS)
        if (used.isEmpty()) return null
        val base = if (used.size <= 2) {
            roundedAverage(used)
        } else {
            val sorted = used.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid] + 1) / 2
        }
        return roundUp(base)
    }

    /** Suggestion for [currentMonth] straight from transactions (which should cover the previous months). */
    fun suggestFor(transactions: List<Transaction>, currentMonth: YearMonth): Long? =
        suggest(monthlyExpenses(transactions, currentMonth))

    private fun roundedAverage(values: List<Long>): Long {
        val n = values.size.toLong()
        return (values.sum() + n / 2) / n
    }

    private fun roundUp(value: Long): Long = (value + ROUND_TO - 1) / ROUND_TO * ROUND_TO
}

/** How full the wallet looks for a budget. */
enum class WalletLevel(val spoken: String) {
    FULL("Wallet full"),
    THREE_QUARTERS("About three quarters left"),
    HALF("About half left"),
    LOW("Running low"),
    EMPTY("Wallet empty"),
    OVER("Over budget"),
}

/**
 * Wallet level for [spent] against [limit] (minor units, each at most Money.MAX_AMOUNT, so the
 * x100 products stay far below Long.MAX_VALUE). Overspent -> OVER; nothing left -> EMPTY;
 * more than 75% left -> FULL; more than 50% -> THREE_QUARTERS; more than 25% -> HALF; otherwise LOW.
 * Without a limit (limit <= 0) the wallet is FULL when nothing was spent, else OVER.
 */
fun walletLevel(limit: Long, spent: Long): WalletLevel {
    val s = spent.coerceAtLeast(0)
    if (s > limit) return WalletLevel.OVER
    if (limit <= 0) return WalletLevel.FULL
    val remaining = limit - s
    return when {
        remaining == 0L -> WalletLevel.EMPTY
        remaining * 100 > limit * 75 -> WalletLevel.FULL
        remaining * 100 > limit * 50 -> WalletLevel.THREE_QUARTERS
        remaining * 100 > limit * 25 -> WalletLevel.HALF
        else -> WalletLevel.LOW
    }
}

fun walletLevel(usage: BudgetUsage): WalletLevel = walletLevel(usage.limit, usage.spent)

/** The budget the app works with for a month: the saved overall budget, else an estimate. */
data class EffectiveBudget(val limit: Long, val estimated: Boolean) {
    companion object {
        /** [overallLimit] wins when set; otherwise [suggestion] (estimated); otherwise null. */
        fun resolve(overallLimit: Long?, suggestion: Long?): EffectiveBudget? = when {
            overallLimit != null -> EffectiveBudget(overallLimit, estimated = false)
            suggestion != null -> EffectiveBudget(suggestion, estimated = true)
            else -> null
        }
    }
}
