package com.homebudget.app.domain.finance

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

class FinanceRulesTest {

    private val cash = Account(1, "Cash", AccountType.CASH, openingBalance = 50_000)
    private val bank = Account(2, "Bank", AccountType.SAVINGS, openingBalance = 1_000_000)
    private val card = Account(3, "Card", AccountType.CREDIT_CARD, openingBalance = -20_000)
    private val accounts = listOf(cash, bank, card)

    private val nov = YearMonth.of(2026, 11)
    private val novRange = DateRange.month(nov)
    private fun at(day: Int, month: Int = 11, year: Int = 2026, h: Int = 12, m: Int = 0) = LocalDateTime.of(year, month, day, h, m)

    private var nextId = 1L
    private fun income(amount: Long, account: Long = 2, cat: Long = 100, t: LocalDateTime = at(5)) =
        Transaction(nextId++, TransactionType.INCOME, amount, account, categoryId = cat, occurredAt = t)
    private fun expense(amount: Long, account: Long = 1, cat: Long = 10, t: LocalDateTime = at(6), refund: Boolean = false) =
        Transaction(nextId++, TransactionType.EXPENSE, amount, account, categoryId = cat, isRefund = refund, occurredAt = t)
    private fun transfer(amount: Long, from: Long, to: Long, t: LocalDateTime = at(7)) =
        Transaction(nextId++, TransactionType.TRANSFER, amount, from, toAccountId = to, occurredAt = t)

    @Test
    fun `income and expense totals for a month`() {
        val txns = listOf(income(5_000_000), income(250_000), expense(120_050), expense(30_000, cat = 11))
        val s = FinanceRules.summarize(txns, novRange)
        assertEquals(5_250_000, s.income)
        assertEquals(150_050, s.grossExpense)
        assertEquals(0, s.refunds)
        assertEquals(150_050, s.netExpense)
        assertEquals(5_099_950, s.netSavings)
    }

    @Test
    fun `empty data gives zero everywhere`() {
        assertEquals(PeriodSummary.ZERO, FinanceRules.summarize(emptyList(), novRange))
        val balances = FinanceRules.accountBalances(accounts, emptyList())
        assertEquals(50_000L, balances[1])
        assertEquals(1_000_000L, balances[2])
        assertEquals(-20_000L, balances[3])
        assertTrue(FinanceRules.spendingByCategory(emptyList(), novRange).isEmpty())
    }

    @Test
    fun `account balance is opening plus effects of all transactions`() {
        val txns = listOf(income(100_000, account = 1), expense(30_000, account = 1), expense(5_000, account = 2))
        val b = FinanceRules.accountBalances(accounts, txns)
        assertEquals(50_000 + 100_000 - 30_000L, b[1])
        assertEquals(1_000_000 - 5_000L, b[2])
    }

    @Test
    fun `opening balance is not income`() {
        val s = FinanceRules.summarize(emptyList(), DateRange.ALL)
        assertEquals(0, s.income)
        assertEquals(1_030_000L, FinanceRules.totalBalance(accounts, FinanceRules.accountBalances(accounts, emptyList())))
    }

    @Test
    fun `transfer moves money between accounts without changing totals or income or expense`() {
        val before = FinanceRules.totalBalance(accounts, FinanceRules.accountBalances(accounts, emptyList()))
        val txns = listOf(transfer(40_000, from = 2, to = 1))
        val b = FinanceRules.accountBalances(accounts, txns)
        assertEquals(90_000L, b[1])
        assertEquals(960_000L, b[2])
        assertEquals(before, FinanceRules.totalBalance(accounts, b))
        val s = FinanceRules.summarize(txns, novRange)
        assertEquals(PeriodSummary.ZERO, s)
        assertTrue(FinanceRules.spendingByCategory(txns, novRange).isEmpty())
    }

    @Test
    fun `invested total counts deposits from non-investment accounts only`() {
        val investments = setOf(10L, 11L)
        val transactions = listOf(
            transfer(5_000, from = 2, to = 10),
            transfer(2_000, from = 10, to = 11),
            transfer(1_000, from = 10, to = 2),
        )

        assertEquals(5_000L, FinanceRules.investedThisPeriod(transactions, investments, novRange))
    }

    @Test
    fun `credit card purchase is expense and card payment is not counted again`() {
        val purchase = expense(300_000, account = 3, cat = 10) // bought on card
        val payment = transfer(300_000, from = 2, to = 3, t = at(20)) // paid the bill from bank
        val txns = listOf(purchase, payment)
        val s = FinanceRules.summarize(txns, novRange)
        assertEquals("expense counted once, at purchase", 300_000, s.netExpense)
        val b = FinanceRules.accountBalances(accounts, txns)
        assertEquals("card debt back to opening", -20_000L, b[3])
        assertEquals(700_000L, b[2])
        // net worth drops by exactly the purchase, not twice
        val start = FinanceRules.totalBalance(accounts, FinanceRules.accountBalances(accounts, emptyList()))
        assertEquals(start - 300_000, FinanceRules.totalBalance(accounts, b))
    }

    @Test
    fun `credit card balance becomes more negative with purchases`() {
        val b = FinanceRules.accountBalances(accounts, listOf(expense(10_000, account = 3)))
        assertEquals(-30_000L, b[3])
    }

    @Test
    fun `refund increases balance and reduces net spending in its category`() {
        val txns = listOf(expense(200_000, cat = 10), expense(50_000, cat = 10, refund = true, t = at(9)))
        val s = FinanceRules.summarize(txns, novRange)
        assertEquals(200_000, s.grossExpense)
        assertEquals(50_000, s.refunds)
        assertEquals(150_000, s.netExpense)
        assertEquals(0, s.income)
        assertEquals(-150_000L, s.netSavings)
        assertEquals(50_000L - 200_000 + 50_000, FinanceRules.accountBalances(accounts, txns)[1])
        assertEquals(listOf(CategorySpend(10, 150_000)), FinanceRules.spendingByCategory(txns, novRange))
    }

    @Test
    fun `refund larger than spending removes category from breakdown and budget never negative`() {
        val txns = listOf(expense(10_000, cat = 10, t = at(2)), expense(30_000, cat = 10, refund = true))
        assertTrue(FinanceRules.spendingByCategory(txns, novRange).isEmpty())
        val usage = FinanceRules.budgetUsage(BudgetLimit(1, nov, 10, 50_000), txns)
        assertEquals(0, usage.spent)
        assertEquals(50_000, usage.remaining)
    }

    @Test
    fun `month boundaries are half-open in local time`() {
        val txns = listOf(
            expense(1, t = at(31, month = 10, h = 23, m = 59)), // Oct 31 23:59 -> October
            expense(10, t = at(1, h = 0, m = 0)), // Nov 1 00:00 -> November
            expense(100, t = at(30, h = 23, m = 59)), // Nov 30 23:59 -> November
            expense(1_000, t = at(1, month = 12, h = 0, m = 0)), // Dec 1 00:00 -> December
        )
        assertEquals(110, FinanceRules.summarize(txns, novRange).netExpense)
        assertEquals(1, FinanceRules.summarize(txns, DateRange.month(YearMonth.of(2026, 10))).netExpense)
        assertEquals(1_000, FinanceRules.summarize(txns, DateRange.month(YearMonth.of(2026, 12))).netExpense)
    }

    @Test
    fun `leap year February includes the 29th`() {
        val feb2028 = DateRange.month(YearMonth.of(2028, 2))
        val txns = listOf(expense(500, t = LocalDateTime.of(2028, 2, 29, 18, 0)), expense(7, t = LocalDateTime.of(2028, 3, 1, 0, 0)))
        assertEquals(500, FinanceRules.summarize(txns, feb2028).netExpense)
        assertEquals(LocalDateTime.of(2028, 3, 1, 0, 0), feb2028.endExclusive)
    }

    @Test
    fun `year boundary December to January`() {
        val txns = listOf(expense(3, t = LocalDateTime.of(2026, 12, 31, 23, 59, 59)), expense(4, t = LocalDateTime.of(2027, 1, 1, 0, 0)))
        assertEquals(3, FinanceRules.summarize(txns, DateRange.month(YearMonth.of(2026, 12))).netExpense)
        assertEquals(4, FinanceRules.summarize(txns, DateRange.month(YearMonth.of(2027, 1))).netExpense)
    }

    @Test
    fun `historical transactions affect balance but not current month totals`() {
        val old = expense(9_999, t = LocalDateTime.of(2020, 1, 15, 10, 0))
        assertEquals(0, FinanceRules.summarize(listOf(old), novRange).netExpense)
        assertEquals(50_000L - 9_999, FinanceRules.accountBalances(accounts, listOf(old))[1])
    }

    @Test
    fun `total balance excludes archived and excluded accounts`() {
        val accs = listOf(cash, bank.copy(includeInTotal = false), card.copy(archived = true))
        val b = FinanceRules.accountBalances(accs, emptyList())
        assertEquals(50_000L, FinanceRules.totalBalance(accs, b))
    }

    @Test
    fun `transfers to an excluded account reduce the included total`() {
        val accs = listOf(cash, bank.copy(includeInTotal = false))
        val b = FinanceRules.accountBalances(accs, listOf(transfer(20_000, from = 1, to = 2)))
        assertEquals(30_000L, FinanceRules.totalBalance(accs, b))
    }

    @Test
    fun `spending breakdown is sorted and only includes expenses`() {
        val txns = listOf(expense(100, cat = 10), expense(300, cat = 11), expense(200, cat = 12), income(10_000))
        assertEquals(listOf(11L, 12L, 10L), FinanceRules.spendingByCategory(txns, novRange).map { it.categoryId })
    }

    @Test
    fun `budget usage overall and per category with threshold and overspending`() {
        val txns = listOf(expense(60_000, cat = 10), expense(30_000, cat = 11), transfer(99_999, 2, 1), income(1_000_000))
        val overall = FinanceRules.budgetUsage(BudgetLimit(1, nov, null, 100_000), txns)
        assertEquals(90_000, overall.spent)
        assertEquals(10_000, overall.remaining)
        assertTrue(overall.reachedThreshold(80))
        assertFalse(overall.reachedThreshold(95))
        assertFalse(overall.isOverspent)

        val groceries = FinanceRules.budgetUsage(BudgetLimit(2, nov, 10, 50_000), txns)
        assertEquals(60_000, groceries.spent)
        assertEquals(-10_000, groceries.remaining)
        assertTrue(groceries.isOverspent)
    }

    @Test
    fun `budget ignores other months`() {
        val txns = listOf(expense(5_000, t = at(15, month = 10)))
        assertEquals(0, FinanceRules.budgetUsage(BudgetLimit(1, nov, null, 1_000), txns).spent)
    }

    @Test
    fun `large amounts sum without overflow`() {
        val txns = List(1_000) { income(Money.MAX_AMOUNT, account = 2) }
        val s = FinanceRules.summarize(txns, novRange)
        assertEquals(Money.MAX_AMOUNT * 1_000, s.income)
    }

    @Test
    fun `transactions for unknown accounts are ignored in balances`() {
        val b = FinanceRules.accountBalances(listOf(cash), listOf(expense(10, account = 99), transfer(5, from = 99, to = 1)))
        assertEquals(50_005L, b[1])
        assertEquals(1, b.size)
    }

    @Test
    fun investmentGainIsCurrentValueMinusContributions() {
        assertEquals(500L, FinanceRules.investmentGain(currentValue = 3_500, contributions = 3_000))
        assertEquals(-500L, FinanceRules.investmentGain(currentValue = 2_500, contributions = 3_000))
        assertEquals(0L, FinanceRules.investmentGain(currentValue = 3_000, contributions = 3_000))
        // Contributions can be negative after withdrawing more than was put in.
        assertEquals(1_200L, FinanceRules.investmentGain(currentValue = 200, contributions = -1_000))
        // Extremes stay exact in Long minor units.
        assertEquals(Money.MAX_AMOUNT, FinanceRules.investmentGain(Money.MAX_AMOUNT, 0))
        assertEquals(-Money.MAX_AMOUNT, FinanceRules.investmentGain(0, Money.MAX_AMOUNT))
    }

    @Test
    fun investmentGainMatchesLedgerContributionsFromTransfers() {
        val bank = Account(id = 1, name = "Bank", type = AccountType.SAVINGS, openingBalance = 10_000, includeInTotal = true, archived = false, sortOrder = 0)
        val fund = Account(id = 2, name = "Fund", type = AccountType.INVESTMENT, openingBalance = 0, includeInTotal = true, archived = false, sortOrder = 1)
        val contributions = FinanceRules.accountBalances(
            listOf(bank, fund),
            listOf(transfer(5_000, from = 1, to = 2), transfer(2_000, from = 2, to = 1)),
        ).getValue(2)
        assertEquals(3_000L, contributions)
        assertEquals(500L, FinanceRules.investmentGain(3_500, contributions))
    }
}
