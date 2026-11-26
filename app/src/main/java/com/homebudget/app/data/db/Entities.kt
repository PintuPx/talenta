package com.homebudget.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.inventory.RequestStatus
import com.homebudget.app.domain.inventory.StockUnit
import com.homebudget.app.domain.proposal.ProposalKind
import com.homebudget.app.domain.proposal.ProposalStatus
import java.util.UUID

/*
 * Room schema v5 (v2 added category groups, bills, bill items and item rules; v3 added recurring rules; v4 adds identity, private-space and history tables; v5 adds expense attribution, investment values and changing recurring amounts). Money columns are INTEGER minor units (hundredths). Enums are stored as TEXT names.
 * Dates are stored as "floating local" epoch seconds: LocalDateTime.toEpochSecond(ZoneOffset.UTC).
 * Exported schema JSON lives in app/schemas and must be committed; every schema change needs a
 * version bump plus a Migration (see docs/ARCHITECTURE.md -> "Schema migrations").
 */

private fun newEntityUid(): String = UUID.randomUUID().toString()

@Entity(tableName = "accounts", indices = [Index(value = ["uid"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    @ColumnInfo(name = "opening_balance") val openingBalance: Long,
    @ColumnInfo(name = "include_in_total") val includeInTotal: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        // A main group that still has groups cannot be deleted.
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parent_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["kind", "sort_order"]), Index("parent_id"), Index(value = ["uid"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    @ColumnInfo(name = "icon_key") val iconKey: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** v2: main group of this group; null for a main group. */
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        // RESTRICT: an account/category that is referenced cannot be deleted; it must be archived.
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
        // The repository deletes a bill's transactions together with the bill; SET NULL is a safety net.
        ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["bill_id"], onDelete = ForeignKey.SET_NULL),
        // Deleting a recurring rule keeps the transactions it already added.
        ForeignKey(entity = RecurringRuleEntity::class, parentColumns = ["id"], childColumns = ["recurring_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("account_id"),
        Index("to_account_id"),
        Index("category_id"),
        Index("occurred_at"),
        Index("bill_id"),
        Index("recurring_id"),
        Index(value = ["uid"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    /** Always > 0. Direction comes from [type] / [isRefund]. */
    val amount: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    /** Destination account; non-null only for TRANSFER. */
    @ColumnInfo(name = "to_account_id") val toAccountId: Long?,
    /** Null only for TRANSFER. */
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "is_refund") val isRefund: Boolean,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val note: String,
    val payee: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** v2: bill this transaction was created from, if any. */
    @ColumnInfo(name = "bill_id") val billId: Long? = null,
    /** v3: recurring rule that added this transaction, if any. */
    @ColumnInfo(name = "recurring_id") val recurringId: Long? = null,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
)

/** v2. A stored bill. [imageFile] is a file name in filesDir/bills (never a path or URI). */
@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("bill_date"), Index(value = ["uid"], unique = true)],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    @ColumnInfo(name = "bill_date") val billDate: Long,
    val total: Long?,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    @ColumnInfo(name = "image_file") val imageFile: String?,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    val status: BillStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
)

/** v2. One line of a bill. Lines are deleted with their bill. */
@Entity(
    tableName = "bill_items",
    foreignKeys = [
        ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["bill_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("bill_id"), Index("category_id"), Index(value = ["uid"], unique = true)],
)
data class BillItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bill_id") val billId: Long,
    val position: Int,
    val description: String,
    val amount: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    val included: Boolean,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

/** v2. Learned item-name -> category mapping (see CategorySuggester). */
@Entity(
    tableName = "item_rules",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("category_id"), Index(value = ["uid"], unique = true)],
)
data class ItemRuleEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["month", "category_id"]), Index("category_id"), Index(value = ["uid"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyyMM, e.g. 202611. */
    val month: Int,
    /** Null = overall monthly budget. */
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "limit_amount") val limitAmount: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
)

/** A repeating transaction template; amount_changes leaves each due expense pending user confirmation. */
@Entity(
    tableName = "recurring_rules",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("to_account_id"), Index("category_id"), Index(value = ["uid"], unique = true)],
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    val amount: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "to_account_id") val toAccountId: Long?,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "is_refund") val isRefund: Boolean,
    val note: String,
    val payee: String,
    val frequency: Frequency,
    @ColumnInfo(name = "repeat_interval") val interval: Int,
    /** Floating local epoch seconds of the first occurrence. */
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "posted_count") val postedCount: Int,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
    @ColumnInfo(name = "amount_changes", defaultValue = "0") val amountChanges: Boolean = false,
)

@Entity(
    tableName = "entity_versions",
    indices = [
        Index(value = ["entity_type", "entity_uid", "version"]),
        Index("status"),
        Index("created_at"),
        Index(value = ["event_uid"], unique = true),
    ],
)
data class EntityVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_uid") val entityUid: String,
    val version: Int,
    val status: String,
    @ColumnInfo(name = "base_version") val baseVersion: Int,
    val kind: String,
    val deleted: Boolean,
    val snapshot: String,
    @ColumnInfo(name = "snapshot_format") val snapshotFormat: Int,
    @ColumnInfo(name = "author_id") val authorId: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decided_by") val decidedBy: String? = null,
    @ColumnInfo(name = "decided_at") val decidedAt: Long? = null,
    @ColumnInfo(name = "decision_note") val decisionNote: String? = null,
    @ColumnInfo(name = "bulk_id") val bulkId: String? = null,
    @ColumnInfo(name = "is_private") val isPrivate: Boolean = false,
    @ColumnInfo(name = "event_uid") val eventUid: String,
    val archived: Boolean = false,
)

@Entity(tableName = "month_locks")
data class MonthLockEntity(
    @PrimaryKey val month: Int,
    @ColumnInfo(name = "closed_at") val closedAt: Long,
    @ColumnInfo(name = "closed_by") val closedBy: String,
)

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey @ColumnInfo(name = "member_id") val memberId: String,
    val email: String,
    val name: String,
    val role: String,
    val active: Boolean,
    @ColumnInfo(name = "app_version_code") val appVersionCode: Int,
    @ColumnInfo(name = "app_version_name") val appVersionName: String,
    @ColumnInfo(name = "sync_format") val syncFormat: Int,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "family_people", indices = [Index(value = ["uid"], unique = true)])
data class FamilyPersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

@Entity(tableName = "family_tags", indices = [Index(value = ["uid"], unique = true)])
data class FamilyTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

@Entity(
    tableName = "family_tag_members",
    foreignKeys = [
        ForeignKey(entity = FamilyTagEntity::class, parentColumns = ["uid"], childColumns = ["tag_uid"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FamilyPersonEntity::class, parentColumns = ["uid"], childColumns = ["person_uid"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["tag_uid", "person_uid"], unique = true),
        Index("person_uid"),
    ],
)
data class FamilyTagMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tag_uid") val tagUid: String,
    @ColumnInfo(name = "person_uid") val personUid: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

@Entity(
    tableName = "transaction_members",
    foreignKeys = [
        ForeignKey(entity = FamilyPersonEntity::class, parentColumns = ["uid"], childColumns = ["person_uid"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["transaction_uid", "person_uid"], unique = true),
        Index("person_uid"),
        Index("is_private"),
    ],
)
// No transaction FK so immediate Undo can restore a transaction with its allocations.
data class TransactionMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "transaction_uid") val transactionUid: String,
    @ColumnInfo(name = "person_uid") val personUid: String,
    @ColumnInfo(name = "share_amount") val shareAmount: Long,
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

@Entity(
    tableName = "investment_values",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["uid"], unique = true), Index("account_id"), Index("is_private")],
)
data class InvestmentValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "value_minor") val valueMinor: Long,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "is_private", defaultValue = "0") val isPrivate: Boolean = false,
    @ColumnInfo(defaultValue = "''") val uid: String = newEntityUid(),
)

/** Staged for Release B: household stock items. Quantities are Long thousandths of the unit. */
@Entity(tableName = "inventory_items", indices = [Index("name")])
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: StockUnit,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "low_stock_milli") val lowStockMilli: Long,
    val note: String,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Staged for Release B: purchase requests / shopping list. Deleting a stock item deletes its requests. */
@Entity(
    tableName = "purchase_requests",
    foreignKeys = [ForeignKey(entity = InventoryItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("item_id"), Index("status")],
)
data class PurchaseRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    val note: String,
    val status: RequestStatus,
    @ColumnInfo(name = "requested_by") val requestedBy: String,
    @ColumnInfo(name = "decision_note") val decisionNote: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Staged for Release A: pending changes are represented by entity versions instead. */
@Entity(tableName = "proposals", indices = [Index(value = ["uuid"], unique = true), Index("status")])
data class ProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val kind: ProposalKind,
    val status: ProposalStatus,
    @ColumnInfo(name = "author_id") val authorId: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    val payload: String,
    val summary: String,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "result_id") val resultId: Long?,
)
