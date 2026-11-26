package com.homebudget.app.data.db

import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Bill
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.investment.InvestmentValue
import kotlinx.serialization.Serializable
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
    createdAt = createdAt, updatedAt = updatedAt, amountChanges = amountChanges,
)

fun RecurringRule.toEntity() = RecurringRuleEntity(
    id = id, type = type, amount = amount, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    isRefund = isRefund, note = note, payee = payee, frequency = frequency, interval = interval,
    startAt = FloatingTime.toStored(startAt), postedCount = postedCount, active = active,
    createdAt = createdAt, updatedAt = updatedAt, amountChanges = amountChanges,
)

@Serializable
data class AccountSnapshot(
    val id: Long,
    val uid: String,
    val name: String,
    val type: String,
    val openingBalance: Long,
    val includeInTotal: Boolean,
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val isPrivate: Boolean,
)

@Serializable
data class CategorySnapshot(
    val id: Long,
    val uid: String,
    val name: String,
    val kind: String,
    val iconKey: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val parentUid: String?,
)

@Serializable
data class TransactionSnapshot(
    val id: Long,
    val uid: String,
    val type: String,
    val amount: Long,
    val accountUid: String,
    val toAccountUid: String?,
    val categoryUid: String?,
    val isRefund: Boolean,
    val occurredAt: Long,
    val note: String,
    val payee: String,
    val createdAt: Long,
    val updatedAt: Long,
    val billUid: String?,
    val recurringUid: String?,
    val isPrivate: Boolean,
)

@Serializable
data class BillSnapshot(
    val id: Long,
    val uid: String,
    val merchant: String,
    val billDate: Long,
    val total: Long?,
    val accountUid: String?,
    val imageFile: String?,
    val ocrText: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPrivate: Boolean,
)

@Serializable
data class BillItemSnapshot(
    val id: Long,
    val uid: String,
    val billUid: String,
    val position: Int,
    val description: String,
    val amount: Long,
    val categoryUid: String?,
    val included: Boolean,
)

@Serializable
data class BudgetSnapshot(
    val id: Long,
    val uid: String,
    val month: Int,
    val categoryUid: String?,
    val limitAmount: Long,
    val isPrivate: Boolean,
)

@Serializable
data class RecurringRuleSnapshot(
    val id: Long,
    val uid: String,
    val type: String,
    val amount: Long,
    val accountUid: String,
    val toAccountUid: String?,
    val categoryUid: String?,
    val isRefund: Boolean,
    val note: String,
    val payee: String,
    val frequency: String,
    val interval: Int,
    val startAt: Long,
    val postedCount: Int,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val isPrivate: Boolean,
    val amountChanges: Boolean = false,
)

@Serializable
data class ItemRuleSnapshot(
    val key: String,
    val uid: String,
    val categoryUid: String,
    val useCount: Int,
    val updatedAt: Long,
)

@Serializable
data class MonthLockSnapshot(
    val month: Int,
    val closedAt: Long,
    val closedBy: String,
) {
    val entityUid: String get() = month.toString()
}

@Serializable
data class MemberSnapshot(
    val memberId: String,
    val email: String,
    val name: String,
    val role: String,
    val active: Boolean,
    val appVersionCode: Int,
    val appVersionName: String,
    val syncFormat: Int,
    val lastSeenAt: Long?,
) {
    val entityUid: String get() = memberId
}

@Serializable
data class FamilyPersonSnapshot(
    val uid: String,
    val name: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class FamilyTagSnapshot(
    val uid: String,
    val name: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class FamilyTagMemberSnapshot(
    val uid: String,
    val tagUid: String,
    val personUid: String,
    val createdAt: Long,
)

@Serializable
data class TransactionMemberSnapshot(
    val uid: String,
    val transactionUid: String,
    val personUid: String,
    val shareAmount: Long,
    val isPrivate: Boolean,
) {
    val entityUid: String get() = uid
}

@Serializable
data class InvestmentValueSnapshot(
    val id: Long,
    val uid: String,
    val accountUid: String,
    val valueMinor: Long,
    val recordedAt: Long,
    val isPrivate: Boolean,
)

data class SnapshotReferences(
    val accountUidsById: Map<Long, String> = emptyMap(),
    val categoryUidsById: Map<Long, String> = emptyMap(),
    val billUidsById: Map<Long, String> = emptyMap(),
    val recurringRuleUidsById: Map<Long, String> = emptyMap(),
)

data class LocalEntityReferences(
    val accountIdsByUid: Map<String, Long> = emptyMap(),
    val categoryIdsByUid: Map<String, Long> = emptyMap(),
    val billIdsByUid: Map<String, Long> = emptyMap(),
    val recurringRuleIdsByUid: Map<String, Long> = emptyMap(),
)

fun AccountEntity.toSnapshot() = AccountSnapshot(
    id = id,
    uid = uid,
    name = name,
    type = type.name,
    openingBalance = openingBalance,
    includeInTotal = includeInTotal,
    archived = archived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPrivate = isPrivate,
)

fun AccountSnapshot.toEntity(id: Long = this.id) = AccountEntity(
    id = id,
    name = name,
    type = com.homebudget.app.domain.model.AccountType.valueOf(type),
    openingBalance = openingBalance,
    includeInTotal = includeInTotal,
    archived = archived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    uid = uid,
    isPrivate = isPrivate,
)

fun CategoryEntity.toSnapshot(references: SnapshotReferences) = CategorySnapshot(
    id = id,
    uid = uid,
    name = name,
    kind = kind.name,
    iconKey = iconKey,
    colorArgb = colorArgb,
    sortOrder = sortOrder,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
    parentUid = parentId?.let { references.categoryUidsById.required(it, "category") },
)

fun CategorySnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = CategoryEntity(
    id = id,
    name = name,
    kind = com.homebudget.app.domain.model.CategoryKind.valueOf(kind),
    iconKey = iconKey,
    colorArgb = colorArgb,
    sortOrder = sortOrder,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
    parentId = parentUid?.let { references.categoryIdsByUid.required(it, "parent category") },
    uid = uid,
)

fun TransactionEntity.toSnapshot(references: SnapshotReferences) = TransactionSnapshot(
    id = id,
    uid = uid,
    type = type.name,
    amount = amount,
    accountUid = references.accountUidsById.required(accountId, "account"),
    toAccountUid = toAccountId?.let { references.accountUidsById.required(it, "destination account") },
    categoryUid = categoryId?.let { references.categoryUidsById.required(it, "category") },
    isRefund = isRefund,
    occurredAt = occurredAt,
    note = note,
    payee = payee,
    createdAt = createdAt,
    updatedAt = updatedAt,
    billUid = billId?.let { references.billUidsById.required(it, "bill") },
    recurringUid = recurringId?.let { references.recurringRuleUidsById.required(it, "recurring rule") },
    isPrivate = isPrivate,
)

fun TransactionSnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = TransactionEntity(
    id = id,
    type = com.homebudget.app.domain.model.TransactionType.valueOf(type),
    amount = amount,
    accountId = references.accountIdsByUid.required(accountUid, "account"),
    toAccountId = toAccountUid?.let { references.accountIdsByUid.required(it, "destination account") },
    categoryId = categoryUid?.let { references.categoryIdsByUid.required(it, "category") },
    isRefund = isRefund,
    occurredAt = occurredAt,
    note = note,
    payee = payee,
    createdAt = createdAt,
    updatedAt = updatedAt,
    billId = billUid?.let { references.billIdsByUid.required(it, "bill") },
    recurringId = recurringUid?.let { references.recurringRuleIdsByUid.required(it, "recurring rule") },
    uid = uid,
    isPrivate = isPrivate,
)

fun BillEntity.toSnapshot(references: SnapshotReferences) = BillSnapshot(
    id = id,
    uid = uid,
    merchant = merchant,
    billDate = billDate,
    total = total,
    accountUid = accountId?.let { references.accountUidsById.required(it, "account") },
    imageFile = imageFile,
    ocrText = ocrText,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPrivate = isPrivate,
)

fun BillSnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = BillEntity(
    id = id,
    merchant = merchant,
    billDate = billDate,
    total = total,
    accountId = accountUid?.let { references.accountIdsByUid.required(it, "account") },
    imageFile = imageFile,
    ocrText = ocrText,
    status = com.homebudget.app.domain.model.BillStatus.valueOf(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    uid = uid,
    isPrivate = isPrivate,
)

fun BillItemEntity.toSnapshot(references: SnapshotReferences) = BillItemSnapshot(
    id = id,
    uid = uid,
    billUid = references.billUidsById.required(billId, "bill"),
    position = position,
    description = description,
    amount = amount,
    categoryUid = categoryId?.let { references.categoryUidsById.required(it, "category") },
    included = included,
)

fun BillItemSnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = BillItemEntity(
    id = id,
    billId = references.billIdsByUid.required(billUid, "bill"),
    position = position,
    description = description,
    amount = amount,
    categoryId = categoryUid?.let { references.categoryIdsByUid.required(it, "category") },
    included = included,
    uid = uid,
)

fun BudgetEntity.toSnapshot(references: SnapshotReferences) = BudgetSnapshot(
    id = id,
    uid = uid,
    month = month,
    categoryUid = categoryId?.let { references.categoryUidsById.required(it, "category") },
    limitAmount = limitAmount,
    isPrivate = isPrivate,
)

fun BudgetSnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = BudgetEntity(
    id = id,
    month = month,
    categoryId = categoryUid?.let { references.categoryIdsByUid.required(it, "category") },
    limitAmount = limitAmount,
    uid = uid,
    isPrivate = isPrivate,
)

fun RecurringRuleEntity.toSnapshot(references: SnapshotReferences) = RecurringRuleSnapshot(
    id = id,
    uid = uid,
    type = type.name,
    amount = amount,
    accountUid = references.accountUidsById.required(accountId, "account"),
    toAccountUid = toAccountId?.let { references.accountUidsById.required(it, "destination account") },
    categoryUid = categoryId?.let { references.categoryUidsById.required(it, "category") },
    isRefund = isRefund,
    note = note,
    payee = payee,
    frequency = frequency.name,
    interval = interval,
    startAt = startAt,
    postedCount = postedCount,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPrivate = isPrivate,
    amountChanges = amountChanges,
)

fun RecurringRuleSnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = RecurringRuleEntity(
    id = id,
    type = com.homebudget.app.domain.model.TransactionType.valueOf(type),
    amount = amount,
    accountId = references.accountIdsByUid.required(accountUid, "account"),
    toAccountId = toAccountUid?.let { references.accountIdsByUid.required(it, "destination account") },
    categoryId = categoryUid?.let { references.categoryIdsByUid.required(it, "category") },
    isRefund = isRefund,
    note = note,
    payee = payee,
    frequency = com.homebudget.app.domain.model.Frequency.valueOf(frequency),
    interval = interval,
    startAt = startAt,
    postedCount = postedCount,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
    uid = uid,
    isPrivate = isPrivate,
    amountChanges = amountChanges,
)

fun ItemRuleEntity.toSnapshot(references: SnapshotReferences) = ItemRuleSnapshot(
    key = key,
    uid = uid,
    categoryUid = references.categoryUidsById.required(categoryId, "category"),
    useCount = useCount,
    updatedAt = updatedAt,
)

fun ItemRuleSnapshot.toEntity(references: LocalEntityReferences) = ItemRuleEntity(
    key = key,
    categoryId = references.categoryIdsByUid.required(categoryUid, "category"),
    useCount = useCount,
    updatedAt = updatedAt,
    uid = uid,
)

fun MonthLockEntity.toSnapshot() = MonthLockSnapshot(
    month = month,
    closedAt = closedAt,
    closedBy = closedBy,
)

fun MonthLockSnapshot.toEntity() = MonthLockEntity(
    month = month,
    closedAt = closedAt,
    closedBy = closedBy,
)

fun MemberEntity.toSnapshot() = MemberSnapshot(
    memberId = memberId,
    email = email,
    name = name,
    role = role,
    active = active,
    appVersionCode = appVersionCode,
    appVersionName = appVersionName,
    syncFormat = syncFormat,
    lastSeenAt = lastSeenAt,
)

fun MemberSnapshot.toEntity() = MemberEntity(
    memberId = memberId,
    email = email,
    name = name,
    role = role,
    active = active,
    appVersionCode = appVersionCode,
    appVersionName = appVersionName,
    syncFormat = syncFormat,
    lastSeenAt = lastSeenAt,
)

fun FamilyPersonEntity.toSnapshot() = FamilyPersonSnapshot(uid, name, active, createdAt, updatedAt)

fun FamilyPersonSnapshot.toEntity(id: Long = 0) = FamilyPersonEntity(id, name, active, createdAt, updatedAt, uid)

fun FamilyTagEntity.toSnapshot() = FamilyTagSnapshot(uid, name, active, createdAt, updatedAt)

fun FamilyTagSnapshot.toEntity(id: Long = 0) = FamilyTagEntity(id, name, active, createdAt, updatedAt, uid)

fun FamilyTagMemberEntity.toSnapshot() = FamilyTagMemberSnapshot(uid, tagUid, personUid, createdAt)

fun FamilyTagMemberSnapshot.toEntity(id: Long = 0) = FamilyTagMemberEntity(id, tagUid, personUid, createdAt, uid)

fun TransactionMemberEntity.toSnapshot() =
    TransactionMemberSnapshot(uid, transactionUid, personUid, shareAmount, isPrivate)

fun TransactionMemberSnapshot.toEntity(id: Long = 0) =
    TransactionMemberEntity(id, transactionUid, personUid, shareAmount, isPrivate, uid)

fun InvestmentValueEntity.toDomain() = InvestmentValue(accountId, valueMinor, recordedAt)

fun InvestmentValueEntity.toSnapshot(references: SnapshotReferences) = InvestmentValueSnapshot(
    id = id,
    uid = uid,
    accountUid = references.accountUidsById.required(accountId, "account"),
    valueMinor = valueMinor,
    recordedAt = recordedAt,
    isPrivate = isPrivate,
)

fun InvestmentValueSnapshot.toEntity(
    references: LocalEntityReferences,
    id: Long = this.id,
) = InvestmentValueEntity(
    id = id,
    accountId = references.accountIdsByUid.required(accountUid, "account"),
    valueMinor = valueMinor,
    recordedAt = recordedAt,
    isPrivate = isPrivate,
    uid = uid,
)

private fun <K, V : Any> Map<K, V>.required(key: K, type: String): V =
    get(key) ?: error("Missing $type reference for snapshot")
