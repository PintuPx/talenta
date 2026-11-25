package com.homebudget.app.domain.budget

import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

class BudgetSuggestionTest {

    private val step = BudgetSuggestion.ROUND_TO

    // ---------------------------------------------------------------- suggest

    @Test
    fun `no history gives no suggestion`() {
        assertNull(BudgetSuggestion.suggest(emptyList()))
    }

    @Test
    fun `months without spending are ignored`() {
        assertNull(BudgetSuggestion.suggest(listOf(0, 0, 0)))
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(0, 25_000, 0)))
    }

    @Test
    fun `one month uses that month rounded up`() {
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(20_001)))
    }

    @Test
    fun `two months use the average rounded half up before rounding up`() {
        // (20_000 + 30_001) / 2 = 25_000.5 -> 25_001 -> 30_000
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(20_000, 30_001)))
        // exact average on a multiple stays
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(20_000, 40_000)))
        // half rounds up: (10_000 + 10_001) / 2 = 10_000.5 -> 10_001 -> 20_000
        assertEquals(20_000L, BudgetSuggestion.suggest(listOf(10_000, 10_001)))
    }

    @Test
    fun `three months use the median`() {
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(50_000, 10_000, 30_000)))
    }

    @Test
    fun `four months use the average of the two middle values`() {
        // sorted 10_000, 20_000, 40_000, 90_000 -> (20_000 + 40_000) / 2 = 30_000
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(90_000, 20_000, 10_000, 40_000)))
        // middle values 20_000 and 20_001 -> 20_000.5 -> 20_001 -> 30_000
        assertEquals(30_000L, BudgetSuggestion.suggest(listOf(20_000, 20_001, 1_000, 99_000)))
    }

    @Test
    fun `an outlier month does not move the median`() {
        val normal = listOf(50_000L, 52_000L, 51_000L, 49_000L)
        assertEquals(BudgetSuggestion.suggest(normal), BudgetSuggestion.suggest(normal + 5_000_000L))
    }

    @Test
    fun `only the six most recent months count`() {
        val recent = listOf(30_000L, 30_000, 30_000, 30_000, 30_000, 30_000)
        val withOldHuge = recent + listOf(9_000_000L, 9_000_000, 9_000_000, 9_000_000)
        assertEquals(30_000L, BudgetSuggestion.suggest(withOldHuge))
        assertEquals(6, BudgetSuggestion.monthsUsed(withOldHuge))
    }

    @Test
    fun `result rounds up to the next 10000 and an exact multiple stays`() {
        assertEquals(step, BudgetSuggestion.suggest(listOf(1)))
        assertEquals(step, BudgetSuggestion.suggest(listOf(step)))
        assertEquals(2 * step, BudgetSuggestion.suggest(listOf(step + 1)))
        assertEquals(2 * step, BudgetSuggestion.suggest(listOf(2 * step - 1)))
        assertEquals(2 * step, BudgetSuggestion.suggest(listOf(2 * step)))
    }

    @Test
    fun `large values do not overflow`() {
        val max = Money.MAX_AMOUNT
        assertEquals(max, BudgetSuggestion.suggest(listOf(max, max)))
        assertEquals(max, BudgetSuggestion.suggest(List(6) { max }))
        assertEquals(max, BudgetSuggestion.suggest(listOf(max - 1, max - 1, max - 1, max - 1)))
    }

    @Test
    fun `monthsUsed counts only months with spending`() {
        assertEquals(0, BudgetSuggestion.monthsUsed(emptyList()))
        assertEquals(2, BudgetSuggestion.monthsUsed(listOf(0, 5, 0, 7)))
    }

    // ---------------------------------------------------------------- history from transactions

    private fun tx(type: TransactionType, amount: Long, month: YearMonth, refund: Boolean = false) = Transaction(
        type = type,
        amount = amount,
        accountId = 1,
        toAccountId = if (type == TransactionType.TRANSFER) 2 else null,
        isRefund = refund,
        occurredAt = LocalDateTime.of(month.year, month.month, 10, 12, 0),
    )

    @Test
    fun `monthly expenses follow the finance rules and skip the current month`() {
        val now = YearMonth.of(2026, 11)
        val oct = now.minusMonths(1)
        val sep = now.minusMonths(2)
        val txns = listOf(
            tx(TransactionType.EXPENSE, 50_000, oct),
            tx(TransactionType.EXPENSE, 5_000, oct, refund = true), // refunds reduce spending
            tx(TransactionType.TRANSFER, 99_000, oct), // transfers are not expenses
            tx(TransactionType.INCOME, 99_000, oct), // income is not spending
            tx(TransactionType.EXPENSE, 20_000, sep),
            tx(TransactionType.EXPENSE, 77_000, now), // current month is not history
        )
        val monthly = BudgetSuggestion.monthlyExpenses(txns, now)
        assertEquals(BudgetSuggestion.MAX_MONTHS, monthly.size)
        assertEquals(45_000L, monthly[0])
        assertEquals(20_000L, monthly[1])
        assertEquals(0L, monthly[2])
        // average of 45_000 and 20_000 = 32_500 -> 40_000
        assertEquals(40_000L, BudgetSuggestion.suggestFor(txns, now))
    }

    @Test
    fun `suggestFor is null without past spending`() {
        val now = YearMonth.of(2026, 11)
        assertNull(BudgetSuggestion.suggestFor(listOf(tx(TransactionType.EXPENSE, 1_000, now)), now))
    }

    // ---------------------------------------------------------------- wallet level

    @Test
    fun `wallet level boundaries`() {
        val limit = 10_000L
        assertEquals(WalletLevel.FULL, walletLevel(limit, 0))
        assertEquals(WalletLevel.FULL, walletLevel(limit, 2_499)) // 75.01% left
        assertEquals(WalletLevel.THREE_QUARTERS, walletLevel(limit, 2_500)) // exactly 75% left
        assertEquals(WalletLevel.THREE_QUARTERS, walletLevel(limit, 4_999))
        assertEquals(WalletLevel.HALF, walletLevel(limit, 5_000)) // exactly 50% left
        assertEquals(WalletLevel.HALF, walletLevel(limit, 7_499))
        assertEquals(WalletLevel.LOW, walletLevel(limit, 7_500)) // exactly 25% left
        assertEquals(WalletLevel.LOW, walletLevel(limit, 9_999))
        assertEquals(WalletLevel.EMPTY, walletLevel(limit, 10_000))
        assertEquals(WalletLevel.OVER, walletLevel(limit, 10_001))
    }

    @Test
    fun `wallet level without a limit`() {
        assertEquals(WalletLevel.FULL, walletLevel(0, 0))
        assertEquals(WalletLevel.OVER, walletLevel(0, 1))
    }

    @Test
    fun `wallet level with the largest amounts does not overflow`() {
        val max = Money.MAX_AMOUNT
        assertEquals(WalletLevel.FULL, walletLevel(max, 0))
        assertEquals(WalletLevel.EMPTY, walletLevel(max, max))
        assertEquals(WalletLevel.HALF, walletLevel(max, max / 2 + 1))
    }

    // ---------------------------------------------------------------- effective budget

    @Test
    fun `a saved budget wins over a suggestion`() {
        assertEquals(EffectiveBudget(5_000, estimated = false), EffectiveBudget.resolve(5_000, 90_000))
        assertEquals(EffectiveBudget(5_000, estimated = false), EffectiveBudget.resolve(5_000, null))
    }

    @Test
    fun `the suggestion is used as an estimate and nothing gives null`() {
        assertEquals(EffectiveBudget(90_000, estimated = true), EffectiveBudget.resolve(null, 90_000))
        assertNull(EffectiveBudget.resolve(null, null))
    }
}
