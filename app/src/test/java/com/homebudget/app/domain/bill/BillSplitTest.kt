package com.homebudget.app.domain.bill

import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.model.mainGroupId
import com.homebudget.app.domain.model.pathLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

class BillSplitTest {

    private fun c(id: Long, name: String, parent: Long? = null, kind: CategoryKind = CategoryKind.EXPENSE) =
        Category(id, name, kind, "misc", 0, parentId = parent)

    private val cats = listOf(
        c(1, "Groceries"), c(2, "Shopping"), c(10, "Vegetables", 1), c(11, "Dairy & Eggs", 1), c(20, "Salary", kind = CategoryKind.INCOME),
    ).associateBy { it.id }

    private fun item(desc: String, amount: Long, cat: Long?, included: Boolean = true, pos: Int = 0) =
        BillItem(position = pos, description = desc, amount = amount, categoryId = cat, included = included)

    private val items = listOf(
        item("Veggie", 4_000, 10),
        item("Tomato", 3_000, 10),
        item("Milk", 5_600, 11),
        item("Bag", 1_000, 1), // main group without a group
        item("T-shirt", 49_900, 2),
        item("Misread", 99_999, null, included = false),
    )

    @Test
    fun postingLinesAreOnePerCategoryWithSums() {
        val lines = BillSplit.postingLines(items)
        assertEquals(listOf(10L to 7_000L, 11L to 5_600L, 1L to 1_000L, 2L to 49_900L), lines.map { it.categoryId to it.amount })
        assertEquals(listOf("Veggie", "Tomato"), lines.first().descriptions)
        assertEquals(63_500L, lines.sumOf { it.amount })
        assertEquals(63_500L, BillSplit.includedTotal(items))
    }

    @Test
    fun summaryGroupsByMainGroupLargestFirst() {
        val s = BillSplit.summary(items, cats)
        assertEquals(listOf(2L to 49_900L, 1L to 13_600L), s.map { it.mainGroupId to it.amount })
        assertEquals(listOf(10L, 11L, 1L), s[1].lines.map { it.categoryId })
    }

    @Test
    fun problemsListEverythingThatBlocksApproval() {
        assertTrue(BillSplit.problems(items, accountId = 1, categories = cats).isEmpty())
        val p = BillSplit.problems(
            listOf(item("Veggie", 0, 10), item("Thing", 500, null), item("Pay", 500, 20)),
            accountId = null,
            categories = cats,
        )
        assertEquals(4, p.size)
        assertTrue(p[0].contains("account"))
        assertTrue(BillSplit.problems(emptyList(), 1, cats).single().contains("at least one"))
        // Excluded lines are not checked.
        assertTrue(BillSplit.problems(listOf(item("Ok", 1, 10), item("Bad", 0, null, included = false)), 1, cats).isEmpty())
    }

    @Test
    fun noteIsCappedAt500Characters() {
        val line = SplitLine(10, 1, 200, List(200) { "Item number $it" })
        val note = BillSplit.note(line)
        assertEquals(500, note.length)
        assertTrue(note.startsWith("Bill: Item number 0, "))
    }

    @Test
    fun groupSpendingRollsUpToMainGroupInReportsAndBudgets() {
        val t = LocalDateTime.of(2026, 11, 5, 10, 0)
        val txns = listOf(
            Transaction(type = TransactionType.EXPENSE, amount = 7_000, accountId = 1, categoryId = 10, occurredAt = t),
            Transaction(type = TransactionType.EXPENSE, amount = 5_600, accountId = 1, categoryId = 11, occurredAt = t),
            Transaction(type = TransactionType.EXPENSE, amount = 1_000, accountId = 1, categoryId = 1, occurredAt = t),
            Transaction(type = TransactionType.EXPENSE, amount = 600, accountId = 1, categoryId = 11, isRefund = true, occurredAt = t),
            Transaction(type = TransactionType.EXPENSE, amount = 2_000, accountId = 1, categoryId = 2, occurredAt = t),
        )
        val nov = DateRange.month(YearMonth.of(2026, 11))
        val rolled = FinanceRules.spendingByCategory(txns, nov) { cats.mainGroupId(it) }
        assertEquals(listOf(1L to 13_000L, 2L to 2_000L), rolled.map { it.categoryId to it.amount })
        // Without roll-up each group is separate (unchanged Phase 1 behaviour).
        assertEquals(4, FinanceRules.spendingByCategory(txns, nov).size)

        val budget = BudgetLimit(month = YearMonth.of(2026, 11), categoryId = 1, limit = 20_000)
        assertEquals(13_000L, FinanceRules.budgetUsage(budget, txns) { cats.mainGroupId(it) }.spent)
        assertEquals(5_000L, FinanceRules.budgetUsage(budget.copy(categoryId = 11), txns) { cats.mainGroupId(it) }.spent)
    }

    @Test
    fun pathLabelShowsGroupThenMainGroup() {
        assertEquals("Vegetables · Groceries", cats.pathLabel(10))
        assertEquals("Groceries", cats.pathLabel(1))
        assertEquals(null, cats.pathLabel(null))
    }
}
