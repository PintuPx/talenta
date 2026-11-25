package com.homebudget.app.domain.budget

import com.homebudget.app.domain.finance.BudgetUsage
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class BudgetRulesTest {

    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)

    // Category 10 = Groceries (main group), 11 = Vegetables (group of 10), 20 = Transport.
    private val rollUp: (Long?) -> Long? = { if (it == 11L) 10L else it }

    private var nextId = 1L
    private fun expense(amount: Long, cat: Long?, refund: Boolean = false) = Transaction(
        id = nextId++,
        type = TransactionType.EXPENSE,
        amount = amount,
        accountId = 1,
        categoryId = cat,
        isRefund = refund,
        occurredAt = LocalDateTime.of(2026, 11, 10, 12, 0),
    )

    @Test
    fun `level is OK below 80 percent`() {
        assertEquals(BudgetLevel.OK, BudgetRules.level(BudgetUsage(limit = 10_000, spent = 7_999)))
    }

    @Test
    fun `level is WARNING at exactly 80 percent`() {
        assertEquals(BudgetLevel.WARNING, BudgetRules.level(BudgetUsage(limit = 10_000, spent = 8_000)))
    }

    @Test
    fun `level stays WARNING when spent equals limit and OVER when above`() {
        assertEquals(BudgetLevel.WARNING, BudgetRules.level(BudgetUsage(limit = 10_000, spent = 10_000)))
        assertEquals(BudgetLevel.OVER, BudgetRules.level(BudgetUsage(limit = 10_000, spent = 10_001)))
    }

    @Test
    fun `statuses puts overall budget first then most used first`() {
        val budgets = listOf(
            BudgetLimit(1, nov, 20, 10_000),
            BudgetLimit(2, nov, 10, 10_000),
            BudgetLimit(3, nov, null, 100_000),
        )
        val txns = listOf(expense(2_000, 20), expense(9_000, 10), expense(1_000, null))
        val result = BudgetRules.statuses(budgets, txns, rollUp)
        assertEquals(listOf<Long?>(null, 10L, 20L), result.map { it.budget.categoryId })
        assertEquals(BudgetLevel.OK, result[0].level)
        assertEquals(BudgetLevel.WARNING, result[1].level)
        assertEquals(BudgetLevel.OK, result[2].level)
    }

    @Test
    fun `group spending counts toward the main group budget`() {
        val budgets = listOf(BudgetLimit(1, nov, 10, 10_000))
        val txns = listOf(expense(3_000, 10), expense(4_000, 11), expense(5_000, 20))
        val status = BudgetRules.statuses(budgets, txns, rollUp).single()
        assertEquals(7_000L, status.usage.spent)
        assertEquals(3_000L, status.usage.remaining)
    }

    @Test
    fun `refunds reduce spent`() {
        val budgets = listOf(BudgetLimit(1, nov, 10, 10_000))
        val txns = listOf(expense(6_000, 10), expense(2_000, 11, refund = true))
        val status = BudgetRules.statuses(budgets, txns, rollUp).single()
        assertEquals(4_000L, status.usage.spent)
        assertEquals(BudgetLevel.OK, status.level)
    }

    @Test
    fun `overspent budget is OVER`() {
        val budgets = listOf(BudgetLimit(1, nov, 10, 1_000))
        val status = BudgetRules.statuses(budgets, listOf(expense(1_500, 10)), rollUp).single()
        assertEquals(BudgetLevel.OVER, status.level)
    }

    @Test
    fun `dailyAllowance divides remaining by days left including today`() {
        // Nov has 30 days; on Nov 21 there are 10 days left including today.
        assertEquals(1_000L, BudgetRules.dailyAllowance(10_000, nov, LocalDate.of(2026, 11, 21)))
        // First day: 30 days left.
        assertEquals(100L, BudgetRules.dailyAllowance(3_000, nov, LocalDate.of(2026, 11, 1)))
        // Last day: all remaining money.
        assertEquals(500L, BudgetRules.dailyAllowance(500, nov, LocalDate.of(2026, 11, 30)))
    }

    @Test
    fun `dailyAllowance rounds down`() {
        assertEquals(33L, BudgetRules.dailyAllowance(100, nov, LocalDate.of(2026, 11, 28))) // 3 days left
    }

    @Test
    fun `dailyAllowance is null for other months`() {
        assertNull(BudgetRules.dailyAllowance(10_000, dec, LocalDate.of(2026, 11, 21)))
        assertNull(BudgetRules.dailyAllowance(10_000, YearMonth.of(2026, 10), LocalDate.of(2026, 11, 21)))
    }

    @Test
    fun `dailyAllowance is null when nothing remains`() {
        assertNull(BudgetRules.dailyAllowance(0, nov, LocalDate.of(2026, 11, 21)))
        assertNull(BudgetRules.dailyAllowance(-500, nov, LocalDate.of(2026, 11, 21)))
    }

    @Test
    fun `copyToMonth skips categories already present`() {
        val source = listOf(
            BudgetLimit(1, nov, null, 100_000),
            BudgetLimit(2, nov, 10, 20_000),
            BudgetLimit(3, nov, 20, 5_000),
        )
        val existing = listOf(BudgetLimit(9, dec, 10, 1))
        val copied = BudgetRules.copyToMonth(source, existing, dec)
        assertEquals(listOf<Long?>(null, 20L), copied.map { it.categoryId })
        assertTrue(copied.all { it.month == dec && it.id == 0L })
        assertEquals(listOf(100_000L, 5_000L), copied.map { it.limit })
    }

    @Test
    fun `copyToMonth skips an existing overall budget`() {
        val source = listOf(BudgetLimit(1, nov, null, 100_000))
        val existing = listOf(BudgetLimit(2, dec, null, 50_000))
        assertTrue(BudgetRules.copyToMonth(source, existing, dec).isEmpty())
    }

    @Test
    fun `copyToMonth dedupes duplicate categories in the source`() {
        val source = listOf(BudgetLimit(1, nov, 10, 1_000), BudgetLimit(2, nov, 10, 2_000))
        val copied = BudgetRules.copyToMonth(source, emptyList(), dec)
        assertEquals(1, copied.size)
        assertEquals(1_000L, copied.single().limit)
    }
}
