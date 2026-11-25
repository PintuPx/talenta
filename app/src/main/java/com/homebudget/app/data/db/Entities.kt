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

/*
 * Room schema v4 (v2 added category groups, bills, bill items and item rules; v3 added recurring rules; v4 added inventory items, purchase requests and proposals). Money columns are INTEGER minor units (hundredths). Enums are stored as TEXT names.
 * Dates are stored as "floating local" epoch seconds: LocalDateTime.toEpochSecond(ZoneOffset.UTC).
 * Exported schema JSON lives in app/schemas and must be committed; every schema change needs a
 * version bump plus a Migration (see docs/ARCHITECTURE.md -> "Schema migrations").
 */

@Entity(tableName = "accounts")
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
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        // A main group that still has groups cannot be deleted.
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parent_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["kind", "sort_order"]), Index("parent_id")],
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
)

/** v2. A stored bill. [imageFile] is a file name in filesDir/bills (never a path or URI). */
@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("bill_date")],
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
)

/** v2. One line of a bill. Lines are deleted with their bill. */
@Entity(
    tableName = "bill_items",
    foreignKeys = [
        ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["bill_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("bill_id"), Index("category_id")],
)
data class BillItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bill_id") val billId: Long,
    val position: Int,
    val description: String,
    val amount: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    val included: Boolean,
)

/** v2. Learned item-name -> category mapping (see CategorySuggester). */
@Entity(
    tableName = "item_rules",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("category_id")],
)
data class ItemRuleEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["month", "category_id"]), Index("category_id")],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyyMM, e.g. 202611. */
    val month: Int,
    /** Null = overall monthly budget. */
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "limit_amount") val limitAmount: Long,
)

/** v3. A repeating transaction template (see domain RecurringRule). */
@Entity(
    tableName = "recurring_rules",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("to_account_id"), Index("category_id")],
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
)

/** v4: household stock items. Quantities are Long thousandths of the unit. */
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

/** v4: purchase requests / shopping list. Deleting a stock item deletes its requests. */
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

/** v4: changes proposed by a Reviewer, waiting for (or decided by) the Admin. */
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
