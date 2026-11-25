package com.homebudget.app.domain.report

import com.homebudget.app.domain.finance.CategorySpend
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

class ReportsTest {

    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)

    // 10 = main group; 11 and 12 are groups of 10; 20 is another main group.
    private val rollUp: (Long?) -> Long? = { if (it == 11L || it == 12L) 10L else it }

    private var nextId = 1L
    private fun at(month: Int, day: Int = 10) = LocalDateTime.of(2026, month, day, 12, 0)
    private fun income(amount: Long, t: LocalDateTime) =
        Transaction(nextId++, TransactionType.INCOME, amount, 1, categoryId = 100, occurredAt = t)
    private fun expense(amount: Long, cat: Long, t: LocalDateTime, refund: Boolean = false) =
        Transaction(nextId++, TransactionType.EXPENSE, amount, 1, categoryId = cat, isRefund = refund, occurredAt = t)
    private fun transfer(amount: Long, t: LocalDateTime) =
        Transaction(nextId++, TransactionType.TRANSFER, amount, 1, toAccountId = 2, occurredAt = t)

    @Test
    fun `monthlyTotals excludes transfers and nets refunds`() {
        val txns = listOf(
            income(500_000, at(11)),
            expense(100_000, 10, at(11)),
            expense(30_000, 10, at(11), refund = true),
            transfer(999_999, at(11)),
            expense(7_000, 10, at(10)),
        )
        val result = Reports.monthlyTotals(txns, listOf(oct, nov))
        assertEquals(listOf(MonthTotals(oct, 0, 7_000), MonthTotals(nov, 500_000, 70_000)), result)
        assertEquals(430_000L, result[1].savings)
    }

    @Test
    fun `monthlyTotals gives zeros for months without data and keeps requested order`() {
        val result = Reports.monthlyTotals(emptyList(), listOf(nov, oct))
        assertEquals(listOf(nov, oct), result.map { it.month })
        assertEquals(0L, result[0].income)
        assertEquals(0L, result[0].expense)
    }

    @Test
    fun `monthsEnding returns consecutive months oldest first`() {
        assertEquals(
            listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)),
            Reports.monthsEnding(YearMonth.of(2026, 3), 3),
        )
    }

    @Test
    fun `monthsEnding crosses a year boundary`() {
        assertEquals(
            listOf(YearMonth.of(2025, 11), YearMonth.of(2025, 12), YearMonth.of(2026, 1)),
            Reports.monthsEnding(YearMonth.of(2026, 1), 3),
        )
    }

    @Test
    fun `monthsOf returns the twelve months of the year`() {
        val months = Reports.monthsOf(2026)
        assertEquals(12, months.size)
        assertEquals(YearMonth.of(2026, 1), months.first())
        assertEquals(YearMonth.of(2026, 12), months.last())
    }

    @Test
    fun `spendingWithin returns only the main group and its groups`() {
        val txns = listOf(
            expense(1_000, 10, at(11)), // main group itself
            expense(3_000, 11, at(11)), // group of 10
            expense(2_000, 12, at(11)), // group of 10
            expense(5_000, 20, at(11)), // other main group
            expense(9_999, 11, at(10)), // other month
        )
        val result = Reports.spendingWithin(txns, DateRange.month(nov), 10L, rollUp)
        assertEquals(
            listOf(CategorySpend(11, 3_000), CategorySpend(12, 2_000), CategorySpend(10, 1_000)),
            result,
        )
    }

    @Test
    fun `savingsRatePercent is null without income`() {
        assertNull(Reports.savingsRatePercent(0, 5_000))
        assertNull(Reports.savingsRatePercent(-1, 0))
    }

    @Test
    fun `savingsRatePercent can be negative`() {
        assertEquals(-50, Reports.savingsRatePercent(10_000, 15_000))
    }

    @Test
    fun `savingsRatePercent truncates toward zero`() {
        assertEquals(33, Reports.savingsRatePercent(300, 200)) // 33.33
        assertEquals(66, Reports.savingsRatePercent(300, 101)) // 66.33
        assertEquals(-33, Reports.savingsRatePercent(300, 400)) // -33.33 -> -33
        assertEquals(100, Reports.savingsRatePercent(1_000, 0))
        assertEquals(0, Reports.savingsRatePercent(1_000, 1_000))
    }
}
