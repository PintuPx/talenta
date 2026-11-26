package com.homebudget.app.domain.finance

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Half-open period [start, endExclusive) in floating local time. */
data class DateRange(val start: LocalDateTime, val endExclusive: LocalDateTime) {
    init {
        require(endExclusive > start) { "Empty date range" }
    }

    operator fun contains(t: LocalDateTime): Boolean = t >= start && t < endExclusive

    companion object {
        /** Every representable date; used for "all time" totals. */
        val ALL = DateRange(LocalDateTime.of(1, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59, 59))

        fun month(ym: YearMonth): DateRange =
            DateRange(ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay())

        fun days(fromInclusive: LocalDate, toInclusive: LocalDate): DateRange =
            DateRange(fromInclusive.atStartOfDay(), toInclusive.plusDays(1).atStartOfDay())
    }
}

/**
 * Totals for a period. [income], [grossExpense] and [refunds] are non-negative minor units;
 * [netExpense] and [netSavings] may be negative.
 */
data class PeriodSummary(
    val income: Long,
    /** Sum of non-refund expenses. */
    val grossExpense: Long,
    /** Sum of refunds (they reduce spending). */
    val refunds: Long,
) {
    /** What the user actually spent: gross expenses minus refunds. */
    val netExpense: Long get() = grossExpense - refunds
    val netSavings: Long get() = income - netExpense

    companion object {
        val ZERO = PeriodSummary(0, 0, 0)
    }
}

data class CategorySpend(val categoryId: Long?, val amount: Long)

data class BudgetUsage(val limit: Long, val spent: Long) {
    val remaining: Long get() = limit - spent
    val isOverspent: Boolean get() = spent > limit

    /** Fraction for progress indicators only (UI); never used for money arithmetic. */
    val fractionUsed: Float
        get() = if (limit <= 0) (if (spent > 0) 1f else 0f) else spent.toFloat() / limit.toFloat()

    /** Integer comparison: spent >= limit * percent / 100. */
    fun reachedThreshold(percent: Int): Boolean = limit > 0 && spent * 100 >= limit * percent
}

/**
 * The single source of truth for every financial number shown in the app.
 * The dashboard, transaction list, account list and (future) reports all call these functions.
 * Rules are documented in docs/ACCOUNTING_RULES.md.
 */
object FinanceRules {

    /** Signed effect of [t] on the balance of account [accountId]. */
    fun balanceEffect(t: Transaction, accountId: Long): Long = when (t.type) {
        TransactionType.INCOME -> if (t.accountId == accountId) t.amount else 0
        TransactionType.EXPENSE ->
            if (t.accountId == accountId) (if (t.isRefund) t.amount else -t.amount) else 0
        TransactionType.TRANSFER -> {
            var e = 0L
            if (t.accountId == accountId) e -= t.amount
            if (t.toAccountId == accountId) e += t.amount
            e
        }
    }

    /** balance = openingBalance + Σ balanceEffect over all transactions (all dates). */
    fun accountBalances(accounts: List<Account>, transactions: List<Transaction>): Map<Long, Long> {
        val result = HashMap<Long, Long>(accounts.size * 2)
        accounts.forEach { result[it.id] = it.openingBalance }
        for (t in transactions) {
            when (t.type) {
                TransactionType.INCOME -> result.add(t.accountId, t.amount)
                TransactionType.EXPENSE -> result.add(t.accountId, if (t.isRefund) t.amount else -t.amount)
                TransactionType.TRANSFER -> {
                    val to = t.toAccountId ?: continue
                    if (to == t.accountId) continue
                    result.add(t.accountId, -t.amount)
                    result.add(to, t.amount)
                }
            }
        }
        return result
    }

    private fun HashMap<Long, Long>.add(key: Long, delta: Long) {
        val current = this[key] ?: return // ignore transactions of unknown accounts
        this[key] = current + delta
    }

    /** Sum of balances of non-archived accounts flagged includeInTotal. Credit-card debt reduces it. */
    fun totalBalance(accounts: List<Account>, balances: Map<Long, Long>): Long =
        accounts.filter { it.includeInTotal && !it.archived }.sumOf { balances[it.id] ?: 0L }

    /** Income / expense / refund totals for [range]. Transfers are always excluded. */
    fun summarize(transactions: List<Transaction>, range: DateRange): PeriodSummary {
        var income = 0L
        var expense = 0L
        var refunds = 0L
        for (t in transactions) {
            if (t.occurredAt !in range) continue
            when (t.type) {
                TransactionType.INCOME -> income += t.amount
                TransactionType.EXPENSE -> if (t.isRefund) refunds += t.amount else expense += t.amount
                TransactionType.TRANSFER -> Unit
            }
        }
        return PeriodSummary(income, expense, refunds)
    }

    /** Deposits from non-investment accounts into investments; transfers between investments are ignored. */
    fun investedThisPeriod(
        transactions: List<Transaction>,
        investmentAccountIds: Set<Long>,
        range: DateRange,
    ): Long = transactions.sumOf { transaction ->
        if (transaction.type == TransactionType.TRANSFER &&
            transaction.occurredAt in range &&
            transaction.toAccountId?.let { it in investmentAccountIds } == true &&
            transaction.accountId !in investmentAccountIds
        ) {
            transaction.amount
        } else {
            0L
        }
    }

    /**
     * Net spending per expense category in [range] (expenses minus refunds), largest first.
     * Categories whose refunds equal or exceed their expenses are omitted.
     */
    fun spendingByCategory(
        transactions: List<Transaction>,
        range: DateRange,
        /** Maps a category to the one it is reported under, e.g. a group to its main group. */
        rollUp: (Long?) -> Long? = { it },
    ): List<CategorySpend> {
        val map = LinkedHashMap<Long?, Long>()
        for (t in transactions) {
            if (t.type != TransactionType.EXPENSE || t.occurredAt !in range) continue
            val delta = if (t.isRefund) -t.amount else t.amount
            val key = rollUp(t.categoryId)
            map[key] = (map[key] ?: 0L) + delta
        }
        return map.filterValues { it > 0 }
            .map { CategorySpend(it.key, it.value) }
            .sortedByDescending { it.amount }
    }

    /**
     * Budget usage for [budget] in its month. Spending = net expense (refunds subtract),
     * restricted to [BudgetLimit.categoryId] when set. Never negative.
     */
    fun budgetUsage(
        budget: BudgetLimit,
        transactions: List<Transaction>,
        /** Maps a group to its main group, so a main-group budget includes its groups' spending. */
        rollUp: (Long?) -> Long? = { it },
    ): BudgetUsage {
        val range = DateRange.month(budget.month)
        var spent = 0L
        for (t in transactions) {
            if (t.type != TransactionType.EXPENSE || t.occurredAt !in range) continue
            if (budget.categoryId != null && t.categoryId != budget.categoryId && rollUp(t.categoryId) != budget.categoryId) continue
            spent += if (t.isRefund) -t.amount else t.amount
        }
        return BudgetUsage(budget.limit, spent.coerceAtLeast(0))
    }

    /** Effect on overall net worth: income +, expense −, refund +, transfer 0. */
    fun netWorthEffect(t: Transaction): Long = when (t.type) {
        TransactionType.INCOME -> t.amount
        TransactionType.EXPENSE -> if (t.isRefund) t.amount else -t.amount
        TransactionType.TRANSFER -> 0
    }

    /**
     * Investment gain (positive) or loss (negative): the latest recorded current value minus the
     * money put in, where [contributions] is the account's ledger balance (opening balance plus
     * transfers in minus transfers out). Both are bounded by Money.MAX_AMOUNT, so this cannot overflow.
     */
    fun investmentGain(currentValue: Long, contributions: Long): Long = currentValue - contributions
}
