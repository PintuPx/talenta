package com.homebudget.app.domain.model

import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Domain models. These are plain Kotlin types with no Android or Room dependencies so that
 * financial rules can be unit-tested on the JVM. All monetary values are [Long] minor units
 * (see [com.homebudget.app.domain.money.Money]).
 */
enum class TransactionType { INCOME, EXPENSE, TRANSFER }

enum class AccountType { CASH, SAVINGS, CURRENT, CREDIT_CARD, OTHER }

enum class CategoryKind { INCOME, EXPENSE }

data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** Balance on the day tracking started. Negative for money owed (e.g. credit-card debt). */
    val openingBalance: Long = 0,
    /** Whether this account contributes to the dashboard "total balance". */
    val includeInTotal: Boolean = true,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * A category is either a top-level "main group" (e.g. Groceries, [parentId] == null) or a "group"
 * inside one (e.g. Vegetables, [parentId] == Groceries.id). Only two levels exist. A transaction
 * may use either; reports roll a group's spending up into its main group.
 */
data class Category(
    val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val iconKey: String,
    val colorArgb: Long,
    val sortOrder: Int = 0,
    val active: Boolean = true,
    val parentId: Long? = null,
) {
    val isGroup: Boolean get() = parentId != null
}

/** id of the main group [categoryId] belongs to (itself if it is already a main group). */
fun Map<Long, Category>.mainGroupId(categoryId: Long?): Long? =
    categoryId?.let { this[it]?.parentId ?: it }

/** "Vegetables · Groceries" for a group, "Groceries" for a main group. */
fun Map<Long, Category>.pathLabel(categoryId: Long?): String? {
    val c = categoryId?.let { this[it] } ?: return null
    val parent = c.parentId?.let { this[it] } ?: return c.name
    return "${c.name} · ${parent.name}"
}

/**
 * One logical financial operation.
 *
 * - INCOME:   money enters [accountId]. Requires an INCOME category.
 * - EXPENSE:  money leaves [accountId]. Requires an EXPENSE category.
 *             If [isRefund] is true the money instead ENTERS [accountId] and reduces spending
 *             in the category (a purchase being reversed).
 * - TRANSFER: money moves from [accountId] to [toAccountId]. No category. Never income/expense.
 *             A credit-card bill payment is a TRANSFER from a bank account to the card account.
 *
 * [amount] is always strictly positive; direction is determined by the type, never by sign.
 * [occurredAt] is a "floating" local date-time: it is not shifted when the device time zone changes,
 * so a purchase made on 31 March always stays in March.
 */
data class Transaction(
    val id: Long = 0,
    val type: TransactionType,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val isRefund: Boolean = false,
    val occurredAt: LocalDateTime,
    val note: String = "",
    val payee: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Set when the transaction was created by approving a bill. */
    val billId: Long? = null,
    /** Set when the transaction was added automatically by a [RecurringRule]. */
    val recurringId: Long? = null,
)

enum class BillStatus {
    /** Being reviewed; no transactions exist yet. */
    DRAFT,

    /** Approved; its line items have been added as expense transactions. */
    POSTED,
}

/**
 * A stored bill / receipt. [imageFile] is a file name inside the app's private bills folder
 * (null for a bill entered without a photo). [total] is the total printed on the bill, if known.
 */
data class Bill(
    val id: Long = 0,
    val merchant: String = "",
    val billDate: LocalDateTime,
    val total: Long? = null,
    val accountId: Long? = null,
    val imageFile: String? = null,
    val ocrText: String? = null,
    val status: BillStatus = BillStatus.DRAFT,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** One line on a bill, e.g. "Veggie" ₹40.00 → group Vegetables (main group Groceries). */
data class BillItem(
    val id: Long = 0,
    val billId: Long = 0,
    val position: Int,
    val description: String,
    val amount: Long,
    val categoryId: Long?,
    /** False when the user excluded the line (e.g. an OCR misread); it is kept but not posted. */
    val included: Boolean = true,
)

/** A learned "item name → category" mapping, recorded each time the user approves a bill. */
data class ItemRule(val key: String, val categoryId: Long, val useCount: Int)

/** A monthly spending limit. [categoryId] == null means the overall monthly budget. */
data class BudgetLimit(
    val id: Long = 0,
    val month: YearMonth,
    val categoryId: Long?,
    val limit: Long,
)

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A transaction that repeats, e.g. rent every month. It works like a template: each due occurrence
 * is added as an ordinary [Transaction] (with [Transaction.recurringId] set).
 *
 * Occurrence n (0-based) happens at [startAt] + n × [interval] × [frequency], always counted from
 * [startAt], so a rule starting on 31 January posts on 28/29 February and then on 31 March again.
 * [postedCount] occurrences have been added so far; the next one is occurrence [postedCount].
 */
data class RecurringRule(
    val id: Long = 0,
    val type: TransactionType,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val isRefund: Boolean = false,
    val note: String = "",
    val payee: String = "",
    val frequency: Frequency,
    val interval: Int = 1,
    val startAt: LocalDateTime,
    val postedCount: Int = 0,
    val active: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
