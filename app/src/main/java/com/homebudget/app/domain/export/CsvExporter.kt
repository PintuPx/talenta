package com.homebudget.app.domain.export

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money

/**
 * Transactions as CSV (RFC 4180) for spreadsheets. Amounts are plain decimals with "." and no
 * grouping or currency symbol, so spreadsheets read them as numbers. "Signed amount" is negative for
 * money leaving the account (expenses, transfers) and positive for income and refunds.
 *
 * Text cells that start with = + - @ (or a tab / carriage return) are prefixed with an apostrophe so a
 * spreadsheet never runs them as formulas (CSV injection).
 */
object CsvExporter {
    /** UTF-8 byte order mark (U+FEFF), built from its code so the source file has no invisible characters. */
    val BOM: Char = 0xFEFF.toChar()

    val HEADER = listOf(
        "Date", "Time", "Type", "Amount", "Signed amount", "Currency", "Account", "To account",
        "Main group", "Group", "Payee", "Note",
    )

    fun export(
        transactions: List<Transaction>,
        accounts: Map<Long, Account>,
        categories: Map<Long, Category>,
        currencyCode: String,
    ): String {
        val sb = StringBuilder()
        // UTF-8 byte order mark so Excel shows ₹ and other non-ASCII text correctly.
        sb.append(BOM)
        sb.appendRow(HEADER)
        transactions.sortedWith(compareBy<Transaction> { it.occurredAt }.thenBy { it.id }).forEach { t ->
            val category = t.categoryId?.let { categories[it] }
            val parent = category?.parentId?.let { categories[it] }
            val mainGroup = parent?.name ?: category?.name ?: ""
            val group = if (parent != null) category.name else ""
            val type = when {
                t.type == TransactionType.EXPENSE && t.isRefund -> "Refund"
                t.type == TransactionType.EXPENSE -> "Expense"
                t.type == TransactionType.INCOME -> "Income"
                else -> "Transfer"
            }
            val amount = Money.toBigDecimal(t.amount).toPlainString()
            val outflow = t.type == TransactionType.TRANSFER || (t.type == TransactionType.EXPENSE && !t.isRefund)
            sb.appendRow(
                listOf(
                    t.occurredAt.toLocalDate().toString(),
                    t.occurredAt.toLocalTime().withSecond(0).withNano(0).toString(),
                    type,
                    amount,
                    if (outflow) "-$amount" else amount,
                    currencyCode,
                    text(accounts[t.accountId]?.name ?: ""),
                    text(t.toAccountId?.let { accounts[it]?.name } ?: ""),
                    text(mainGroup),
                    text(group),
                    text(t.payee),
                    text(t.note),
                )
            )
        }
        return sb.toString()
    }

    /** Neutralises spreadsheet formulas in user-entered text. */
    fun text(value: String): String =
        if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(escape(c))
        }
        append("\r\n")
    }

    private fun escape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
}
