package com.homebudget.app.domain.validation

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionValidatorTest {
    private val cash = Account(1, "Cash", AccountType.CASH)
    private val bank = Account(2, "Bank", AccountType.SAVINGS)
    private val food = Category(10, "Food", CategoryKind.EXPENSE, "dining", 0xFF000000)
    private val salary = Category(20, "Salary", CategoryKind.INCOME, "salary", 0xFF000000)

    private fun fields(r: ValidationResult) = (r as ValidationResult.Invalid).errors.map { it.field }.toSet()

    @Test
    fun `valid expense returns minor units`() {
        val r = TransactionValidator.validate(TransactionDraft(TransactionType.EXPENSE, "99.99", cash, null, food))
        assertEquals(ValidationResult.Valid(9_999), r)
    }

    @Test
    fun `missing amount account and category are all reported`() {
        val r = TransactionValidator.validate(TransactionDraft(TransactionType.EXPENSE, "", null, null, null))
        assertEquals(setOf(TxField.AMOUNT, TxField.ACCOUNT, TxField.CATEGORY), fields(r))
    }

    @Test
    fun `invalid amounts are rejected`() {
        listOf("0", "-1", "abc", "1.999", "99999999999999").forEach { input ->
            val r = TransactionValidator.validate(TransactionDraft(TransactionType.EXPENSE, input, cash, null, food))
            assertTrue("'$input' should fail", r is ValidationResult.Invalid && TxField.AMOUNT in fields(r))
        }
    }

    @Test
    fun `category kind must match type`() {
        val r = TransactionValidator.validate(TransactionDraft(TransactionType.INCOME, "10", cash, null, food))
        assertEquals(setOf(TxField.CATEGORY), fields(r))
        val ok = TransactionValidator.validate(TransactionDraft(TransactionType.INCOME, "10", cash, null, salary))
        assertTrue(ok is ValidationResult.Valid)
    }

    @Test
    fun `transfer needs a different destination and no category`() {
        assertEquals(
            setOf(TxField.TO_ACCOUNT),
            fields(TransactionValidator.validate(TransactionDraft(TransactionType.TRANSFER, "10", cash, null, null))),
        )
        assertEquals(
            setOf(TxField.TO_ACCOUNT),
            fields(TransactionValidator.validate(TransactionDraft(TransactionType.TRANSFER, "10", cash, cash, null))),
        )
        assertTrue(TransactionValidator.validate(TransactionDraft(TransactionType.TRANSFER, "10", cash, bank, null)) is ValidationResult.Valid)
    }

    @Test
    fun `zero decimal currency message`() {
        val r = TransactionValidator.validate(TransactionDraft(TransactionType.EXPENSE, "1.5", cash, null, food), fractionDigits = 0)
        assertEquals("This currency has no decimal places", (r as ValidationResult.Invalid).errors.single().message)
    }
}
