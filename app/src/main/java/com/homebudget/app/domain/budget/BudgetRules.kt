package com.homebudget.app.domain.budget

import com.homebudget.app.domain.finance.BudgetUsage
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth

enum class BudgetLevel { OK, WARNING, OVER }

data class BudgetStatus(val budget: BudgetLimit, val usage: BudgetUsage, val level: BudgetLevel)

object BudgetRules {
    /** A budget shows a warning once this share of it is used. */
    const val WARNING_PERCENT = 80

    fun level(usage: BudgetUsage): BudgetLevel = when {
        usage.isOverspent -> BudgetLevel.OVER
        usage.reachedThreshold(WARNING_PERCENT) -> BudgetLevel.WARNING
        else -> BudgetLevel.OK
    }

    /** Usage of every budget. The overall budget (no category) comes first, then the most-used first. */
    fun statuses(
        budgets: List<BudgetLimit>,
        transactions: List<Transaction>,
        rollUp: (Long?) -> Long?,
    ): List<BudgetStatus> = budgets
        .map { b -> FinanceRules.budgetUsage(b, transactions, rollUp).let { BudgetStatus(b, it, level(it)) } }
        .sortedWith(compareBy<BudgetStatus> { it.budget.categoryId != null }.thenByDescending { it.usage.fractionUsed })

    /**
     * How much can still be spent per day for the rest of [month], counting [today] as a remaining
     * day, in whole minor units (rounded down). Null when the month is over, has not started, or nothing is left.
     */
    fun dailyAllowance(remaining: Long, month: YearMonth, today: LocalDate): Long? {
        if (remaining <= 0 || YearMonth.from(today) != month) return null
        val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
        return remaining / daysLeft
    }

    /** Copies [source] budgets into [target], skipping categories that already have a budget there. */
    fun copyToMonth(source: List<BudgetLimit>, existing: List<BudgetLimit>, target: YearMonth): List<BudgetLimit> {
        val taken = existing.map { it.categoryId }.toSet()
        return source.filter { it.categoryId !in taken }
            .distinctBy { it.categoryId }
            .map { BudgetLimit(month = target, categoryId = it.categoryId, limit = it.limit) }
    }
}
