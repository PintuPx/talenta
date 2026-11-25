package com.homebudget.app.domain.recurring

import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RecurrenceTest {

    private fun rule(
        start: LocalDateTime,
        frequency: Frequency = Frequency.MONTHLY,
        interval: Int = 1,
        posted: Int = 0,
        active: Boolean = true,
        type: TransactionType = TransactionType.EXPENSE,
    ) = RecurringRule(
        id = 7,
        type = type,
        amount = 150_000,
        accountId = 1,
        toAccountId = 2,
        categoryId = 10,
        note = "Rent",
        payee = "Landlord",
        frequency = frequency,
        interval = interval,
        startAt = start,
        postedCount = posted,
        active = active,
    )

    private fun dt(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 30) = LocalDateTime.of(y, m, d, h, min)

    @Test
    fun `monthly rule from Jan 31 does not drift`() {
        val start = dt(2027, 1, 31)
        assertEquals(dt(2027, 1, 31), Recurrence.occurrence(start, Frequency.MONTHLY, 1, 0))
        assertEquals(dt(2027, 2, 28), Recurrence.occurrence(start, Frequency.MONTHLY, 1, 1))
        assertEquals(dt(2027, 3, 31), Recurrence.occurrence(start, Frequency.MONTHLY, 1, 2))
        assertEquals(dt(2027, 4, 30), Recurrence.occurrence(start, Frequency.MONTHLY, 1, 3))
    }

    @Test
    fun `monthly rule from Jan 31 in a leap year gives Feb 29`() {
        val start = dt(2028, 1, 31)
        assertEquals(dt(2028, 2, 29), Recurrence.occurrence(start, Frequency.MONTHLY, 1, 1))
        assertEquals(dt(2028, 3, 31), Recurrence.occurrence(start, Frequency.MONTHLY, 1, 2))
    }

    @Test
    fun `weekly interval 2 steps two weeks`() {
        val start = dt(2026, 11, 2)
        assertEquals(dt(2026, 11, 16), Recurrence.occurrence(start, Frequency.WEEKLY, 2, 1))
        assertEquals(dt(2026, 11, 30), Recurrence.occurrence(start, Frequency.WEEKLY, 2, 2))
    }

    @Test
    fun `daily and quarterly occurrences`() {
        assertEquals(dt(2026, 12, 1), Recurrence.occurrence(dt(2026, 11, 28), Frequency.DAILY, 1, 3))
        assertEquals(dt(2027, 2, 15), Recurrence.occurrence(dt(2026, 11, 15), Frequency.MONTHLY, 3, 1))
    }

    @Test
    fun `yearly rule on Feb 29 falls back to Feb 28 then returns to Feb 29`() {
        val start = dt(2028, 2, 29)
        assertEquals(dt(2029, 2, 28), Recurrence.occurrence(start, Frequency.YEARLY, 1, 1))
        assertEquals(dt(2032, 2, 29), Recurrence.occurrence(start, Frequency.YEARLY, 1, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `interval below one is rejected`() {
        Recurrence.occurrence(dt(2026, 1, 1), Frequency.DAILY, 0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative occurrence index is rejected`() {
        Recurrence.occurrence(dt(2026, 1, 1), Frequency.DAILY, 1, -1)
    }

    @Test
    fun `nextDue is the occurrence at postedCount`() {
        val r = rule(dt(2026, 1, 31), posted = 1)
        assertEquals(dt(2026, 2, 28), Recurrence.nextDue(r))
    }

    @Test
    fun `dueOccurrences returns only occurrences up to now inclusive`() {
        val r = rule(dt(2026, 1, 15))
        val due = Recurrence.dueOccurrences(r, now = dt(2026, 3, 15))
        assertEquals(listOf(dt(2026, 1, 15), dt(2026, 2, 15), dt(2026, 3, 15)), due)
        val before = Recurrence.dueOccurrences(r, now = dt(2026, 3, 15, 9, 29))
        assertEquals(listOf(dt(2026, 1, 15), dt(2026, 2, 15)), before)
    }

    @Test
    fun `dueOccurrences is empty when start is in the future`() {
        assertTrue(Recurrence.dueOccurrences(rule(dt(2026, 12, 1)), now = dt(2026, 11, 23)).isEmpty())
    }

    @Test
    fun `dueOccurrences starts at postedCount`() {
        val r = rule(dt(2026, 1, 15), posted = 2)
        val due = Recurrence.dueOccurrences(r, now = dt(2026, 4, 20))
        assertEquals(listOf(dt(2026, 3, 15), dt(2026, 4, 15)), due)
    }

    @Test
    fun `dueOccurrences respects the max cap`() {
        val r = rule(dt(2026, 1, 1), frequency = Frequency.DAILY)
        val due = Recurrence.dueOccurrences(r, now = dt(2026, 12, 31), max = 5)
        assertEquals(5, due.size)
        assertEquals(dt(2026, 1, 1), due.first())
        assertEquals(dt(2026, 1, 5), due.last())
    }

    @Test
    fun `dueOccurrences default cap is MAX_CATCH_UP`() {
        val r = rule(dt(2000, 1, 1), frequency = Frequency.DAILY)
        assertEquals(Recurrence.MAX_CATCH_UP, Recurrence.dueOccurrences(r, now = dt(2026, 1, 1)).size)
    }

    @Test
    fun `dueOccurrences is empty for an inactive rule`() {
        val r = rule(dt(2026, 1, 1), active = false)
        assertTrue(Recurrence.dueOccurrences(r, now = dt(2026, 11, 23)).isEmpty())
    }

    @Test
    fun `toTransaction copies fields and sets recurringId`() {
        val r = rule(dt(2026, 1, 1))
        val at = dt(2026, 2, 1)
        val t = Recurrence.toTransaction(r, at)
        assertEquals(TransactionType.EXPENSE, t.type)
        assertEquals(150_000L, t.amount)
        assertEquals(1L, t.accountId)
        assertEquals(10L, t.categoryId)
        assertEquals(at, t.occurredAt)
        assertEquals("Rent", t.note)
        assertEquals("Landlord", t.payee)
        assertEquals(7L, t.recurringId)
        assertFalse(t.isRefund)
    }

    @Test
    fun `toTransaction clears category for a transfer and keeps the destination account`() {
        val t = Recurrence.toTransaction(rule(dt(2026, 1, 1), type = TransactionType.TRANSFER), dt(2026, 2, 1))
        assertNull(t.categoryId)
        assertEquals(2L, t.toAccountId)
    }

    @Test
    fun `toTransaction clears destination account for an expense`() {
        val t = Recurrence.toTransaction(rule(dt(2026, 1, 1), type = TransactionType.EXPENSE), dt(2026, 2, 1))
        assertNull(t.toAccountId)
        assertEquals(10L, t.categoryId)
    }

    @Test
    fun `toTransaction only keeps refund flag for expenses`() {
        val refundExpense = rule(dt(2026, 1, 1)).copy(isRefund = true)
        assertTrue(Recurrence.toTransaction(refundExpense, dt(2026, 2, 1)).isRefund)
        val income = rule(dt(2026, 1, 1), type = TransactionType.INCOME).copy(isRefund = true)
        assertFalse(Recurrence.toTransaction(income, dt(2026, 2, 1)).isRefund)
    }

    @Test
    fun `RepeatOption of finds known options`() {
        assertEquals(RepeatOption.BIWEEKLY, RepeatOption.of(Frequency.WEEKLY, 2))
        assertEquals(RepeatOption.QUARTERLY, RepeatOption.of(Frequency.MONTHLY, 3))
        assertNull(RepeatOption.of(Frequency.DAILY, 5))
    }

    @Test
    fun `label uses the option label or a generic one`() {
        assertEquals("Every month", RepeatOption.label(Frequency.MONTHLY, 1))
        assertEquals("Every 2 weeks", RepeatOption.label(Frequency.WEEKLY, 2))
        assertEquals("Every 5 days", RepeatOption.label(Frequency.DAILY, 5))
        assertEquals("Every 4 weeks", RepeatOption.label(Frequency.WEEKLY, 4))
        assertEquals("Every 6 months", RepeatOption.label(Frequency.MONTHLY, 6))
        assertEquals("Every 2 years", RepeatOption.label(Frequency.YEARLY, 2))
    }
}
