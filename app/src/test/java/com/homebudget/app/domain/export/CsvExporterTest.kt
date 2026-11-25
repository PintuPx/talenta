package com.homebudget.app.domain.export

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CsvExporterTest {

    private val accounts = mapOf(
        1L to Account(1, "Cash", AccountType.CASH),
        2L to Account(2, "Bank", AccountType.SAVINGS),
    )
    private val categories = mapOf(
        10L to Category(10, "Groceries", CategoryKind.EXPENSE, "cart", 0xFF00FF00),
        11L to Category(11, "Vegetables", CategoryKind.EXPENSE, "veg", 0xFF00FF00, parentId = 10),
        100L to Category(100, "Salary", CategoryKind.INCOME, "cash", 0xFF0000FF),
    )

    private val time = LocalDateTime.of(2026, 11, 5, 14, 30, 45)
    private val bom = CsvExporter.BOM.toString()

    private fun export(vararg txns: Transaction) = CsvExporter.export(txns.toList(), accounts, categories, "INR")

    /** Rows without the BOM, split on CRLF, without the trailing empty element. */
    private fun rows(csv: String): List<String> = csv.removePrefix(bom).split("\r\n").dropLast(1)

    private fun expense(
        amount: Long = 12_345,
        cat: Long? = 10,
        refund: Boolean = false,
        t: LocalDateTime = time,
        id: Long = 0,
    ) = Transaction(id, TransactionType.EXPENSE, amount, 1, categoryId = cat, isRefund = refund, occurredAt = t)

    @Test
    fun `starts with a BOM and the header row`() {
        val csv = export()
        assertTrue(csv.startsWith(bom))
        assertEquals(
            "Date,Time,Type,Amount,Signed amount,Currency,Account,To account,Main group,Group,Payee,Note",
            rows(csv).single(),
        )
        assertEquals(CsvExporter.HEADER.joinToString(","), rows(csv).single())
    }

    @Test
    fun `lines end with CRLF and never a bare LF`() {
        val csv = export(expense())
        assertTrue(csv.endsWith("\r\n"))
        assertEquals(2, csv.split("\r\n").size - 1)
        assertFalse(csv.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun `expense row has plain decimal amount and negative signed amount`() {
        val row = rows(export(expense()))[1]
        assertEquals("2026-11-05,14:30,Expense,123.45,-123.45,INR,Cash,,Groceries,,,", row)
    }

    @Test
    fun `income row is positive`() {
        val t = Transaction(0, TransactionType.INCOME, 5_000_000, 2, categoryId = 100, occurredAt = time)
        assertEquals("2026-11-05,14:30,Income,50000.00,50000.00,INR,Bank,,Salary,,,", rows(export(t))[1])
    }

    @Test
    fun `refund is labelled Refund and positive`() {
        val row = rows(export(expense(amount = 1_200, refund = true)))[1]
        assertEquals("2026-11-05,14:30,Refund,12.00,12.00,INR,Cash,,Groceries,,,", row)
    }

    @Test
    fun `transfer is negative with a destination account and no category`() {
        val t = Transaction(0, TransactionType.TRANSFER, 50, 1, toAccountId = 2, occurredAt = time)
        assertEquals("2026-11-05,14:30,Transfer,0.50,-0.50,INR,Cash,Bank,,,,", rows(export(t))[1])
    }

    @Test
    fun `group category fills main group and group columns`() {
        val row = rows(export(expense(cat = 11)))[1]
        assertEquals("2026-11-05,14:30,Expense,123.45,-123.45,INR,Cash,,Groceries,Vegetables,,", row)
    }

    @Test
    fun `cells with commas quotes or newlines are quoted`() {
        val t = expense().copy(payee = "Smith, John", note = "He said \"hi\"\nsecond line")
        val csv = export(t)
        assertTrue(csv.contains("\"Smith, John\""))
        assertTrue(csv.contains("\"He said \"\"hi\"\"\nsecond line\""))
    }

    @Test
    fun `formula-like text is prefixed with an apostrophe`() {
        val csv = export(expense().copy(note = "=SUM(A1)", payee = "@evil"))
        val row = rows(csv)[1]
        assertTrue(row.endsWith(",'@evil,'=SUM(A1)"))
        assertEquals("'=SUM(A1)", CsvExporter.text("=SUM(A1)"))
        assertEquals("'+1", CsvExporter.text("+1"))
        assertEquals("'-1", CsvExporter.text("-1"))
        assertEquals("'\tx", CsvExporter.text("\tx"))
        assertEquals("plain", CsvExporter.text("plain"))
        assertEquals("", CsvExporter.text(""))
    }

    @Test
    fun `signed amount is not mangled by the formula guard`() {
        val row = rows(export(expense()))[1]
        assertTrue(row.contains(",-123.45,"))
        assertFalse(row.contains("'-123.45"))
    }

    @Test
    fun `rows are sorted by date then id`() {
        val late = expense(amount = 300, t = LocalDateTime.of(2026, 11, 20, 8, 0), id = 1)
        val early = expense(amount = 100, t = LocalDateTime.of(2026, 11, 2, 8, 0), id = 2)
        val sameTimeLowId = expense(amount = 200, t = LocalDateTime.of(2026, 11, 2, 8, 0), id = 1)
        val dataRows = rows(export(late, early, sameTimeLowId)).drop(1)
        assertEquals(listOf("2026-11-02", "2026-11-02", "2026-11-20"), dataRows.map { it.substringBefore(',') })
        assertEquals(listOf("2.00", "1.00", "3.00"), dataRows.map { it.split(',')[3] })
    }

    @Test
    fun `unknown account and category give empty cells`() {
        val t = Transaction(0, TransactionType.EXPENSE, 100, 99, categoryId = 55, occurredAt = time)
        assertEquals("2026-11-05,14:30,Expense,1.00,-1.00,INR,,,,,,", rows(export(t))[1])
    }
}
