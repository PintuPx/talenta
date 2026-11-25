package com.homebudget.app.data.db

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Bill
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.Transaction
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** Floating local time <-> stored epoch seconds. UTC is used only as a fixed reference, never converted. */
object FloatingTime {
    fun toStored(t: LocalDateTime): Long = t.toEpochSecond(ZoneOffset.UTC)
    fun fromStored(s: Long): LocalDateTime = LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC)
}

fun YearMonth.toStoredMonth(): Int = year * 100 + monthValue
fun Int.toYearMonth(): YearMonth = YearMonth.of(this / 100, this % 100)

fun AccountEntity.toDomain() = Account(id, name, type, openingBalance, includeInTotal, archived, sortOrder)

fun CategoryEntity.toDomain() = Category(id, name, kind, iconKey, colorArgb, sortOrder, active, parentId)

fun TransactionEntity.toDomain() = Transaction(
    id = id,
    type = type,
    amount = amount,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    isRefund = isRefund,
    occurredAt = FloatingTime.fromStored(occurredAt),
    note = note,
    payee = payee,
    createdAt = createdAt,
    updatedAt = updatedAt,
    billId = billId,
    recurringId = recurringId,
)

fun Transaction.toEntity() = TransactionEntity(
    id = id,
    type = type,
    amount = amount,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    isRefund = isRefund,
    occurredAt = FloatingTime.toStored(occurredAt),
    note = note,
    payee = payee,
    createdAt = createdAt,
    updatedAt = updatedAt,
    billId = billId,
    recurringId = recurringId,
)

fun BudgetEntity.toDomain() = BudgetLimit(id, month.toYearMonth(), categoryId, limitAmount)

fun BillEntity.toDomain() = Bill(
    id = id, merchant = merchant, billDate = FloatingTime.fromStored(billDate), total = total, accountId = accountId,
    imageFile = imageFile, ocrText = ocrText, status = status, createdAt = createdAt, updatedAt = updatedAt,
)

fun Bill.toEntity() = BillEntity(
    id = id, merchant = merchant, billDate = FloatingTime.toStored(billDate), total = total, accountId = accountId,
    imageFile = imageFile, ocrText = ocrText, status = status, createdAt = createdAt, updatedAt = updatedAt,
)

fun BillItemEntity.toDomain() = BillItem(id, billId, position, description, amount, categoryId, included)

fun BillItem.toEntity() = BillItemEntity(id, billId, position, description, amount, categoryId, included)

fun BudgetLimit.toEntity() = BudgetEntity(id, month.toStoredMonth(), categoryId, limit)

fun RecurringRuleEntity.toDomain() = RecurringRule(
    id = id, type = type, amount = amount, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    isRefund = isRefund, note = note, payee = payee, frequency = frequency, interval = interval,
    startAt = FloatingTime.fromStored(startAt), postedCount = postedCount, active = active,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun RecurringRule.toEntity() = RecurringRuleEntity(
    id = id, type = type, amount = amount, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    isRefund = isRefund, note = note, payee = payee, frequency = frequency, interval = interval,
    startAt = FloatingTime.toStored(startAt), postedCount = postedCount, active = active,
    createdAt = createdAt, updatedAt = updatedAt,
)
