package com.homebudget.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {

    private fun validFile() = BackupFile(
        exportedAtEpochMillis = 1L,
        currencyCode = "INR",
        accounts = listOf(
            BackupAccount(1, "Cash", "CASH", 0, true, false, 0, 1, 1),
            BackupAccount(2, "Card", "CREDIT_CARD", -500, true, false, 1, 1, 1),
        ),
        categories = listOf(
            BackupCategory(10, "Food", "EXPENSE", "dining", 0xFF000000, 0, true, 1, 1),
            BackupCategory(20, "Salary", "INCOME", "salary", 0xFF000000, 0, true, 1, 1),
        ),
        transactions = listOf(
            BackupTransaction(1, "EXPENSE", 1_000, 1, categoryId = 10, occurredAtIso = "2026-11-01T10:00:00", createdAt = 1, updatedAt = 1),
            BackupTransaction(2, "INCOME", 5_000, 1, categoryId = 20, occurredAtIso = "2026-11-01T10:00", createdAt = 1, updatedAt = 1),
            BackupTransaction(3, "TRANSFER", 500, 1, toAccountId = 2, occurredAtIso = "2026-11-02T10:00:00", createdAt = 1, updatedAt = 1),
            BackupTransaction(4, "EXPENSE", 200, 2, categoryId = 10, isRefund = true, occurredAtIso = "2026-11-03T10:00:00", createdAt = 1, updatedAt = 1),
        ),
        budgets = listOf(BackupBudget(1, 202611, 10, 10_000), BackupBudget(2, 202611, null, 50_000)),
    )

    @Test
    fun `valid file passes and round trips through the codec`() {
        val f = validFile()
        assertNull(BackupValidator.validate(f))
        val decoded = BackupCodec.decode(BackupCodec.encode(f), BackupValidator::validate)
        assertEquals(BackupDecodeResult.Valid(f), decoded)
    }

    private fun invalid(f: BackupFile): String {
        val reason = BackupValidator.validate(f)
        assertTrue("expected invalid", reason != null)
        return reason!!
    }

    @Test
    fun `rejects broken references and financial rules`() {
        val base = validFile()
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "EXPENSE", 1, 99, categoryId = 10, occurredAtIso = "2026-11-01T00:00", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "EXPENSE", 0, 1, categoryId = 10, occurredAtIso = "2026-11-01T00:00", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "EXPENSE", 5, 1, categoryId = 20, occurredAtIso = "2026-11-01T00:00", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "TRANSFER", 5, 1, toAccountId = 1, occurredAtIso = "2026-11-01T00:00", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "TRANSFER", 5, 1, toAccountId = 2, categoryId = 10, occurredAtIso = "2026-11-01T00:00", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "INCOME", 5, 1, categoryId = 20, isRefund = true, occurredAtIso = "2026-11-01T00:00", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + BackupTransaction(9, "EXPENSE", 5, 1, categoryId = 10, occurredAtIso = "not a date", createdAt = 0, updatedAt = 0)))
        invalid(base.copy(transactions = base.transactions + base.transactions.first()))
        invalid(base.copy(accounts = base.accounts + base.accounts.first()))
        invalid(base.copy(accounts = base.accounts + BackupAccount(5, "X", "PIGGY", 0, true, false, 0, 0, 0)))
        invalid(base.copy(currencyCode = "KWD"))
        invalid(base.copy(budgets = listOf(BackupBudget(1, 202613, null, 1))))
        invalid(base.copy(budgets = listOf(BackupBudget(1, 202611, 20, 1))))
    }

    @Test
    fun `codec rejects garbage, foreign and future files`() {
        val v = BackupValidator::validate
        assertTrue(BackupCodec.decode(ByteArray(0), v) is BackupDecodeResult.Invalid)
        assertTrue(BackupCodec.decode("hello".toByteArray(), v) is BackupDecodeResult.Invalid)
        assertTrue(BackupCodec.decode("{\"a\":1}".toByteArray(), v) is BackupDecodeResult.Invalid)
        val foreign = BackupCodec.encode(validFile().copy(format = "other.app"))
        assertTrue(BackupCodec.decode(foreign, v) is BackupDecodeResult.Invalid)
        val future = BackupCodec.encode(validFile().copy(formatVersion = BackupFile.CURRENT_FORMAT_VERSION + 1))
        val r = BackupCodec.decode(future, v)
        assertTrue(r is BackupDecodeResult.Invalid && r.reason.contains("newer version"))
    }
}
