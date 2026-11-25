package com.homebudget.app.domain.report

import com.homebudget.app.domain.finance.CategorySpend
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Transaction
import java.time.YearMonth

data class MonthTotals(val month: YearMonth, val income: Long, val expense: Long) {
    val savings: Long get() = income - expense
}

/** Report calculations. All amounts come from [FinanceRules], so reports match the dashboard. */
object Reports {

    /** Income and net expense for each month in [months] (transfers excluded, refunds subtracted). */
    fun monthlyTotals(transactions: List<Transaction>, months: List<YearMonth>): List<MonthTotals> {
        val byMonth = transactions.groupBy { YearMonth.from(it.occurredAt) }
        return months.map { m ->
            val s = FinanceRules.summarize(byMonth[m].orEmpty(), DateRange.month(m))
            MonthTotals(m, s.income, s.netExpense)
        }
    }

    /** [count] consecutive months ending with [last]. */
    fun monthsEnding(last: YearMonth, count: Int): List<YearMonth> = (count - 1 downTo 0).map { last.minusMonths(it.toLong()) }

    /** The 12 months of [year]. */
    fun monthsOf(year: Int): List<YearMonth> = (1..12).map { YearMonth.of(year, it) }

    /**
     * Spending inside one main group, split by its groups. Expenses booked on the main group itself
     * are reported under the main group's id.
     */
    fun spendingWithin(
        transactions: List<Transaction>,
        range: DateRange,
        mainGroupId: Long,
        rollUp: (Long?) -> Long?,
    ): List<CategorySpend> = FinanceRules.spendingByCategory(transactions.filter { rollUp(it.categoryId) == mainGroupId }, range)

    /**
     * Savings rate in whole percent (savings ÷ income), or null when there is no income.
     * Integer arithmetic, rounded toward zero.
     */
    fun savingsRatePercent(income: Long, netExpense: Long): Int? =
        if (income <= 0) null else ((income - netExpense) * 100 / income).toInt()
}
