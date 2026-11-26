package com.homebudget.app.data.repository

import com.homebudget.app.data.db.AccountEntity
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.BillEntity
import com.homebudget.app.data.db.BillItemEntity
import com.homebudget.app.data.db.BudgetEntity
import com.homebudget.app.data.db.CategoryEntity
import com.homebudget.app.data.db.FamilyPersonEntity
import com.homebudget.app.data.db.FamilyTagEntity
import com.homebudget.app.data.db.FamilyTagMemberEntity
import com.homebudget.app.data.db.ItemRuleEntity
import com.homebudget.app.data.db.InvestmentValueEntity
import com.homebudget.app.data.db.MemberEntity
import com.homebudget.app.data.db.MonthLockEntity
import com.homebudget.app.data.db.RecurringRuleEntity
import com.homebudget.app.data.db.SnapshotReferences
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.db.TransactionMemberEntity
import com.homebudget.app.data.db.toSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotJsonCodec @Inject constructor(
    private val db: AppDatabase,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun encode(entity: Any): String {
        val references = references()
        return when (entity) {
            is AccountEntity -> json.encodeToString(entity.toSnapshot())
            is CategoryEntity -> json.encodeToString(entity.toSnapshot(references))
            is TransactionEntity -> json.encodeToString(entity.toSnapshot(references))
            is BillEntity -> json.encodeToString(entity.toSnapshot(references))
            is BillItemEntity -> json.encodeToString(entity.toSnapshot(references))
            is BudgetEntity -> json.encodeToString(entity.toSnapshot(references))
            is RecurringRuleEntity -> json.encodeToString(entity.toSnapshot(references))
            is ItemRuleEntity -> json.encodeToString(entity.toSnapshot(references))
            is MonthLockEntity -> json.encodeToString(entity.toSnapshot())
            is MemberEntity -> json.encodeToString(entity.toSnapshot())
            is FamilyPersonEntity -> json.encodeToString(entity.toSnapshot())
            is FamilyTagEntity -> json.encodeToString(entity.toSnapshot())
            is FamilyTagMemberEntity -> json.encodeToString(entity.toSnapshot())
            is TransactionMemberEntity -> json.encodeToString(entity.toSnapshot())
            is InvestmentValueEntity -> json.encodeToString(entity.toSnapshot(references))
            else -> throw IllegalArgumentException("Unsupported versioned entity")
        }
    }

    /**
     * Returns the stored snapshot of the row identified by [entityType]/[uid], or null when the row is
     * not visible to these lookups. A missing row can be a legitimate state: for example a
     * transaction_member whose transaction is not stored yet (no transaction FK, so allocations can be
     * written before an approved or restored transaction). Callers decide how to handle null; this
     * function never throws for a missing row.
     */
    suspend fun current(entityType: String, uid: String): String? {
        val references = references()
        return when (entityType) {
            "account" -> db.accountDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot()) }
            "category" -> db.categoryDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "transaction" -> db.transactionDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "bill" -> db.billDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "bill_item" -> db.billDao().getAllItems().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "budget" -> db.budgetDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "recurring_rule" -> db.recurringDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "item_rule" -> db.itemRuleDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            "month_lock" -> uid.toIntOrNull()?.let { db.monthLockDao().getByMonth(it) }
                ?.let { json.encodeToString(it.toSnapshot()) }
            "member" -> db.memberDao().getById(uid)
                ?.let { json.encodeToString(it.toSnapshot()) }
            "family_person" -> db.familyAllocationDao().getPerson(uid)
                ?.let { json.encodeToString(it.toSnapshot()) }
            "family_tag" -> db.familyAllocationDao().getTag(uid)
                ?.let { json.encodeToString(it.toSnapshot()) }
            "family_tag_member" -> db.familyAllocationDao().getTagMember(uid)
                ?.let { json.encodeToString(it.toSnapshot()) }
            "transaction_member" -> db.familyAllocationDao().getTransactionMember(uid)
                ?.let { json.encodeToString(it.toSnapshot()) }
            "investment_value" -> db.investmentValueDao().getAll().firstOrNull { it.uid == uid }
                ?.let { json.encodeToString(it.toSnapshot(references)) }
            else -> throw IllegalArgumentException("Unsupported versioned entity type")
        }
    }

    private suspend fun references(): SnapshotReferences {
        val accounts = db.accountDao().getAll()
        val categories = db.categoryDao().getAll()
        val bills = db.billDao().getAll()
        val recurring = db.recurringDao().getAll()
        return SnapshotReferences(
            accountUidsById = accounts.associate { it.id to it.uid },
            categoryUidsById = categories.associate { it.id to it.uid },
            billUidsById = bills.associate { it.id to it.uid },
            recurringRuleUidsById = recurring.associate { it.id to it.uid },
        )
    }
}
