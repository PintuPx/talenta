package com.homebudget.app.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY archived ASC, sort_order ASC, id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE is_private = :privateSpace ORDER BY archived ASC, sort_order ASC, id ASC")
    fun observeInSpace(privateSpace: Boolean): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id AND is_private = :privateSpace")
    suspend fun getByIdInSpace(id: Long, privateSpace: Boolean): AccountEntity?

    @Insert
    suspend fun insert(entity: AccountEntity): Long

    @Insert
    suspend fun insertAll(entities: List<AccountEntity>)

    @Update
    suspend fun update(entity: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM accounts WHERE is_private = :privateSpace")
    suspend fun nextSortOrderInSpace(privateSpace: Boolean): Int

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("UPDATE accounts SET is_private = 1 WHERE is_private = 0")
    suspend fun makeSharedPrivate()
}

@Dao
interface InvestmentValueDao {
    @Query(
        """
        SELECT v.* FROM investment_values v
        JOIN accounts a ON a.id = v.account_id AND a.is_private = v.is_private
        WHERE v.is_private = :privateSpace
          AND NOT EXISTS (
              SELECT 1 FROM investment_values newer
              WHERE newer.account_id = v.account_id AND newer.is_private = v.is_private
                AND (newer.recorded_at > v.recorded_at OR (newer.recorded_at = v.recorded_at AND newer.id > v.id))
          )
        ORDER BY v.account_id
        """
    )
    fun observeLatestInSpace(privateSpace: Boolean): Flow<List<InvestmentValueEntity>>

    @Query(
        "SELECT * FROM investment_values WHERE account_id = :accountId AND is_private = :privateSpace " +
            "ORDER BY recorded_at DESC, id DESC LIMIT 1",
    )
    suspend fun getLatest(accountId: Long, privateSpace: Boolean): InvestmentValueEntity?

    @Query("SELECT * FROM investment_values")
    suspend fun getAll(): List<InvestmentValueEntity>

    @Query(
        "SELECT v.* FROM investment_values v JOIN accounts a ON a.id = v.account_id",
    )
    suspend fun getAllForBackup(): List<InvestmentValueEntity>

    @Insert
    suspend fun insert(value: InvestmentValueEntity): Long

    @Insert
    suspend fun insertAll(values: List<InvestmentValueEntity>)

    @Update
    suspend fun update(value: InvestmentValueEntity)

    @Query("DELETE FROM investment_values WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM investment_values")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind ASC, active DESC, sort_order ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insert(entity: CategoryEntity): Long

    @Insert
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM categories WHERE kind = :kind")
    suspend fun nextSortOrder(kind: String): Int

    @Query("SELECT COUNT(*) FROM categories WHERE parent_id = :id")
    suspend fun countChildren(id: Long): Int

    /** Groups first: deleting a main group that still has groups would violate the foreign key. */
    @Query("DELETE FROM categories WHERE parent_id IS NOT NULL")
    suspend fun deleteGroups()

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE is_private = :privateSpace")
    fun observeInSpace(privateSpace: Boolean): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT (
            SELECT TRIM(t2.payee)
            FROM transactions t2
            WHERE LOWER(TRIM(t2.payee)) = LOWER(TRIM(t.payee))
            ORDER BY t2.occurred_at DESC, t2.id DESC
            LIMIT 1
        )
        FROM transactions t
        WHERE TRIM(t.payee) != ''
        GROUP BY LOWER(TRIM(t.payee))
        ORDER BY COUNT(*) DESC, MAX(t.occurred_at) DESC, MIN(t.id) ASC
        """
    )
    fun observePayees(): Flow<List<String>>

    @Query(
        """
        SELECT (
            SELECT TRIM(t2.payee)
            FROM transactions t2
            WHERE LOWER(TRIM(t2.payee)) = LOWER(TRIM(t.payee))
              AND t2.is_private = :privateSpace
            ORDER BY t2.occurred_at DESC, t2.id DESC
            LIMIT 1
        )
        FROM transactions t
        WHERE TRIM(t.payee) != '' AND t.is_private = :privateSpace
        GROUP BY LOWER(TRIM(t.payee))
        ORDER BY COUNT(*) DESC, MAX(t.occurred_at) DESC, MIN(t.id) ASC
        """
    )
    fun observePayeesInSpace(privateSpace: Boolean): Flow<List<String>>

    @Query("SELECT * FROM transactions ORDER BY id")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE occurred_at >= :start AND occurred_at < :end")
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE is_private = :privateSpace AND occurred_at >= :start AND occurred_at < :end")
    fun observeBetweenInSpace(start: Long, end: Long, privateSpace: Boolean): Flow<List<TransactionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE is_private = :privateSpace AND occurred_at >= :start AND occurred_at < :end)")
    fun observeAnyBetweenInSpace(start: Long, end: Long, privateSpace: Boolean): Flow<Boolean>

    @Query("SELECT * FROM transactions ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE is_private = :privateSpace ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    fun observeRecentInSpace(limit: Int, privateSpace: Boolean): Flow<List<TransactionEntity>>

    /**
     * Filtered list. Null filter arguments mean "any". [query] matches note, payee or category name.
     * Sort keys: 0 = newest first, 1 = oldest first, 2 = largest amount, 3 = smallest amount.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        WHERE (:start IS NULL OR t.occurred_at >= :start)
          AND (:end IS NULL OR t.occurred_at < :end)
          AND (:type IS NULL OR t.type = :type)
          AND (:accountId IS NULL OR t.account_id = :accountId OR t.to_account_id = :accountId)
          AND (:categoryId IS NULL OR t.category_id = :categoryId OR c.parent_id = :categoryId)
          AND (:query = '' OR t.note LIKE '%' || :query || '%' OR t.payee LIKE '%' || :query || '%'
               OR c.name LIKE '%' || :query || '%')
        ORDER BY
          CASE WHEN :sort = 0 THEN t.occurred_at END DESC,
          CASE WHEN :sort = 1 THEN t.occurred_at END ASC,
          CASE WHEN :sort = 2 THEN t.amount END DESC,
          CASE WHEN :sort = 3 THEN t.amount END ASC,
          t.id DESC
        """
    )
    fun observeFiltered(
        start: Long?,
        end: Long?,
        type: String?,
        accountId: Long?,
        categoryId: Long?,
        query: String,
        sort: Int,
    ): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT t.* FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.is_private = :privateSpace
          AND (:start IS NULL OR t.occurred_at >= :start)
          AND (:end IS NULL OR t.occurred_at < :end)
          AND (:type IS NULL OR t.type = :type)
          AND (:accountId IS NULL OR t.account_id = :accountId OR t.to_account_id = :accountId)
          AND (:categoryId IS NULL OR t.category_id = :categoryId OR c.parent_id = :categoryId)
          AND (:query = '' OR t.note LIKE '%' || :query || '%' OR t.payee LIKE '%' || :query || '%'
               OR c.name LIKE '%' || :query || '%')
        ORDER BY
          CASE WHEN :sort = 0 THEN t.occurred_at END DESC,
          CASE WHEN :sort = 1 THEN t.occurred_at END ASC,
          CASE WHEN :sort = 2 THEN t.amount END DESC,
          CASE WHEN :sort = 3 THEN t.amount END ASC,
          t.id DESC
        """
    )
    fun observeFilteredInSpace(
        start: Long?,
        end: Long?,
        type: String?,
        accountId: Long?,
        categoryId: Long?,
        query: String,
        sort: Int,
        privateSpace: Boolean,
    ): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id AND is_private = :privateSpace")
    suspend fun getByIdInSpace(id: Long, privateSpace: Boolean): TransactionEntity?

    @Insert
    suspend fun insert(entity: TransactionEntity): Long

    @Insert
    suspend fun insertAll(entities: List<TransactionEntity>)

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE account_id = :accountId OR to_account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE is_private = :privateSpace AND (account_id = :accountId OR to_account_id = :accountId)")
    suspend fun countForAccountInSpace(accountId: Long, privateSpace: Boolean): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE account_id = :accountId AND is_private = :privateSpace AND type != 'TRANSFER'")
    suspend fun countNonTransferForAccountInSpace(accountId: Long, privateSpace: Boolean): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :categoryId AND is_private = :privateSpace")
    suspend fun countForCategoryInSpace(categoryId: Long, privateSpace: Boolean): Int

    @Query("SELECT * FROM transactions WHERE bill_id = :billId ORDER BY id")
    suspend fun getForBill(billId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE bill_id = :billId AND is_private = :privateSpace ORDER BY id")
    suspend fun getForBillInSpace(billId: Long, privateSpace: Boolean): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE bill_id = :billId")
    suspend fun deleteForBill(billId: Long): Int

    @Query("DELETE FROM transactions WHERE bill_id = :billId AND is_private = :privateSpace")
    suspend fun deleteForBillInSpace(billId: Long, privateSpace: Boolean): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE recurring_id = :ruleId")
    suspend fun countForRecurring(ruleId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE recurring_id = :ruleId AND is_private = :privateSpace")
    suspend fun countForRecurringInSpace(ruleId: Long, privateSpace: Boolean): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("UPDATE transactions SET is_private = 1 WHERE is_private = 0")
    suspend fun makeSharedPrivate()
}

@Dao
interface BillDao {
    @Query("SELECT * FROM bills ORDER BY bill_date DESC, id DESC")
    fun observeAll(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE is_private = :privateSpace ORDER BY bill_date DESC, id DESC")
    fun observeAllInSpace(privateSpace: Boolean): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills ORDER BY id")
    suspend fun getAll(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): BillEntity?

    @Query("SELECT * FROM bills WHERE id = :id AND is_private = :privateSpace")
    suspend fun getByIdInSpace(id: Long, privateSpace: Boolean): BillEntity?

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: Long): Flow<BillEntity?>

    @Query("SELECT * FROM bills WHERE id = :id AND is_private = :privateSpace")
    fun observeByIdInSpace(id: Long, privateSpace: Boolean): Flow<BillEntity?>

    @Insert
    suspend fun insert(entity: BillEntity): Long

    @Insert
    suspend fun insertAll(entities: List<BillEntity>)

    @Update
    suspend fun update(entity: BillEntity)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bills")
    suspend fun deleteAll()

    @Query("UPDATE bills SET is_private = 1 WHERE is_private = 0")
    suspend fun makeSharedPrivate()

    @Query("SELECT * FROM bill_items WHERE bill_id = :billId ORDER BY position, id")
    suspend fun getItems(billId: Long): List<BillItemEntity>

    @Query(
        "SELECT i.* FROM bill_items i INNER JOIN bills b ON b.id = i.bill_id " +
            "WHERE i.bill_id = :billId AND b.is_private = :privateSpace ORDER BY i.position, i.id"
    )
    suspend fun getItemsInSpace(billId: Long, privateSpace: Boolean): List<BillItemEntity>

    @Query("SELECT * FROM bill_items ORDER BY bill_id, position, id")
    suspend fun getAllItems(): List<BillItemEntity>

    @Insert
    suspend fun insertItems(items: List<BillItemEntity>)

    @Insert
    suspend fun insertItem(item: BillItemEntity): Long

    @Update
    suspend fun updateItem(item: BillItemEntity)

    @Query("DELETE FROM bill_items WHERE bill_id = :billId")
    suspend fun deleteItems(billId: Long)

    @Query("DELETE FROM bill_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query(
        "DELETE FROM bill_items WHERE bill_id = :billId AND bill_id IN " +
            "(SELECT id FROM bills WHERE is_private = :privateSpace)"
    )
    suspend fun deleteItemsInSpace(billId: Long, privateSpace: Boolean)

    @Query("DELETE FROM bill_items")
    suspend fun deleteAllItems()

    @Query("SELECT COUNT(*) FROM bills WHERE account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("SELECT COUNT(*) FROM bills WHERE account_id = :accountId AND is_private = :privateSpace")
    suspend fun countForAccountInSpace(accountId: Long, privateSpace: Boolean): Int

    /** Included lines of approved bills, for item price trends. */
    @Query(
        """
        SELECT i.description AS description, i.amount AS amount, b.bill_date AS bill_date, b.merchant AS merchant
        FROM bill_items i JOIN bills b ON b.id = i.bill_id
        WHERE b.status = 'POSTED' AND i.included = 1 AND i.amount > 0
        """
    )
    fun observePurchasedLines(): Flow<List<PurchasedLineRow>>

    @Query(
        """
        SELECT i.description AS description, i.amount AS amount, b.bill_date AS bill_date, b.merchant AS merchant
        FROM bill_items i JOIN bills b ON b.id = i.bill_id
        WHERE b.status = 'POSTED' AND i.included = 1 AND i.amount > 0 AND b.is_private = :privateSpace
        """
    )
    fun observePurchasedLinesInSpace(privateSpace: Boolean): Flow<List<PurchasedLineRow>>
}

data class PurchasedLineRow(
    val description: String,
    val amount: Long,
    @androidx.room.ColumnInfo(name = "bill_date") val billDate: Long,
    val merchant: String,
)

@Dao
interface ItemRuleDao {
    @Query("SELECT * FROM item_rules")
    suspend fun getAll(): List<ItemRuleEntity>

    @Query("SELECT * FROM item_rules WHERE `key` = :key")
    suspend fun get(key: String): ItemRuleEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemRuleEntity)

    @Query("DELETE FROM item_rules WHERE `key` = :key")
    suspend fun delete(key: String)

    @Insert
    suspend fun insertAll(entities: List<ItemRuleEntity>)

    @Query("DELETE FROM item_rules")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE month = :month")
    fun observeForMonth(month: Int): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE month = :month AND is_private = :privateSpace")
    fun observeForMonthInSpace(month: Int, privateSpace: Boolean): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE month = :month")
    suspend fun getForMonth(month: Int): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE month = :month AND is_private = :privateSpace")
    suspend fun getForMonthInSpace(month: Int, privateSpace: Boolean): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE id = :id AND is_private = :privateSpace")
    suspend fun getByIdInSpace(id: Long, privateSpace: Boolean): BudgetEntity?

    /** The latest month before [month] that has any budget, or null. */
    @Query("SELECT MAX(month) FROM budgets WHERE month < :month")
    fun observeLatestMonthBefore(month: Int): Flow<Int?>

    @Query("SELECT MAX(month) FROM budgets WHERE month < :month AND is_private = :privateSpace")
    fun observeLatestMonthBeforeInSpace(month: Int, privateSpace: Boolean): Flow<Int?>

    @Insert
    suspend fun insert(entity: BudgetEntity): Long

    @Update
    suspend fun update(entity: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM budgets ORDER BY id")
    suspend fun getAll(): List<BudgetEntity>

    @Insert
    suspend fun insertAll(entities: List<BudgetEntity>)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()

    @Query("UPDATE budgets SET is_private = 1 WHERE is_private = 0")
    suspend fun makeSharedPrivate()
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_rules ORDER BY active DESC, id ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE is_private = :privateSpace ORDER BY active DESC, id ASC")
    fun observeAllInSpace(privateSpace: Boolean): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY id")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE active = 1 ORDER BY id")
    suspend fun getActive(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE active = 1 AND is_private = :privateSpace ORDER BY id")
    suspend fun getActiveInSpace(privateSpace: Boolean): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun getById(id: Long): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_rules WHERE id = :id AND is_private = :privateSpace")
    suspend fun getByIdInSpace(id: Long, privateSpace: Boolean): RecurringRuleEntity?

    @Insert
    suspend fun insert(entity: RecurringRuleEntity): Long

    @Insert
    suspend fun insertAll(entities: List<RecurringRuleEntity>)

    @Update
    suspend fun update(entity: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAll()

    @Query("UPDATE recurring_rules SET is_private = 1 WHERE is_private = 0")
    suspend fun makeSharedPrivate()

    @Query("SELECT COUNT(*) FROM recurring_rules WHERE account_id = :accountId OR to_account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM recurring_rules WHERE is_private = :privateSpace " +
            "AND (account_id = :accountId OR to_account_id = :accountId)"
    )
    suspend fun countForAccountInSpace(accountId: Long, privateSpace: Boolean): Int
}

@Dao
interface EntityVersionDao {
    @Query("SELECT * FROM entity_versions ORDER BY id")
    suspend fun getAll(): List<EntityVersionEntity>

    @Query("SELECT * FROM entity_versions WHERE status = 'PENDING' AND is_private = 0 ORDER BY created_at ASC, id ASC")
    fun observePendingShared(): Flow<List<EntityVersionEntity>>

    @Query(
        "SELECT * FROM entity_versions WHERE entity_type = :entityType AND entity_uid = :entityUid " +
            "AND status = 'PENDING' AND is_private = 0 ORDER BY created_at ASC, id ASC"
    )
    suspend fun getPendingForEntity(entityType: String, entityUid: String): List<EntityVersionEntity>

    @Query(
        "SELECT * FROM entity_versions WHERE entity_type = :entityType AND entity_uid = :entityUid " +
            "AND kind != 'MERGED_REQUEST' AND is_private = :privateSpace " +
            "ORDER BY version DESC, id DESC"
    )
    fun observeForEntityInSpace(entityType: String, entityUid: String, privateSpace: Boolean): Flow<List<EntityVersionEntity>>

    @Query("SELECT * FROM entity_versions WHERE kind != 'MERGED_REQUEST' ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<EntityVersionEntity>>

    @Query("SELECT * FROM entity_versions WHERE is_private = 0 AND kind != 'MERGED_REQUEST' ORDER BY created_at DESC, id DESC")
    fun observeShared(): Flow<List<EntityVersionEntity>>

    @Query(
        "SELECT * FROM entity_versions WHERE entity_type = :entityType AND entity_uid = :entityUid " +
            "AND status = 'APPROVED' AND is_private = 0 AND kind != 'MERGED_REQUEST' " +
            "ORDER BY version DESC, id DESC LIMIT 1"
    )
    suspend fun latestApproved(entityType: String, entityUid: String): EntityVersionEntity?

    @Query(
        "SELECT * FROM entity_versions WHERE entity_type = :entityType AND entity_uid = :entityUid " +
            "AND status = 'APPROVED' AND is_private = :isPrivate AND kind != 'MERGED_REQUEST' " +
            "ORDER BY version DESC, id DESC LIMIT 1"
    )
    suspend fun latestApprovedForSpace(entityType: String, entityUid: String, isPrivate: Boolean): EntityVersionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM entity_versions WHERE event_uid = :eventUid)")
    suspend fun hasEventUid(eventUid: String): Boolean

    @Query("DELETE FROM entity_versions WHERE bulk_id = :bulkId AND status = 'PENDING'")
    suspend fun deletePendingBulk(bulkId: String): Int

    @Query("SELECT * FROM entity_versions WHERE bulk_id = :bulkId ORDER BY id")
    suspend fun getForBulk(bulkId: String): List<EntityVersionEntity>

    @Query(
        "SELECT * FROM entity_versions WHERE entity_type = :entityType AND entity_uid = :entityUid " +
            "AND version = :version AND status = 'APPROVED' AND is_private = 0 " +
            "AND kind != 'MERGED_REQUEST' ORDER BY id DESC LIMIT 1"
    )
    suspend fun approvedVersion(entityType: String, entityUid: String, version: Int): EntityVersionEntity?

    @Query(
        "SELECT COALESCE(MAX(version), 0) FROM entity_versions WHERE entity_type = :entityType " +
            "AND entity_uid = :entityUid AND status = 'APPROVED' AND is_private = 0 " +
            "AND kind != 'MERGED_REQUEST'"
    )
    suspend fun maxApprovedVersion(entityType: String, entityUid: String): Int

    @Insert
    suspend fun insert(entity: EntityVersionEntity): Long

    @Insert
    suspend fun insertAll(entities: List<EntityVersionEntity>)

    @Update
    suspend fun update(entity: EntityVersionEntity)

    @Query("DELETE FROM entity_versions")
    suspend fun deleteAll()
}

@Dao
interface MonthLockDao {
    @Query("SELECT * FROM month_locks ORDER BY month DESC")
    fun observeAll(): Flow<List<MonthLockEntity>>

    @Query("SELECT * FROM month_locks ORDER BY month DESC")
    suspend fun getAll(): List<MonthLockEntity>

    @Query("SELECT * FROM month_locks WHERE month = :month")
    suspend fun getByMonth(month: Int): MonthLockEntity?

    @Insert
    suspend fun insert(entity: MonthLockEntity)

    @Insert
    suspend fun insertAll(entities: List<MonthLockEntity>)

    @Update
    suspend fun update(entity: MonthLockEntity)

    @Query("DELETE FROM month_locks WHERE month = :month")
    suspend fun delete(month: Int)

    @Query("DELETE FROM month_locks")
    suspend fun deleteAll()
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY name COLLATE NOCASE, member_id")
    fun observeAll(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members ORDER BY name COLLATE NOCASE, member_id")
    suspend fun getAll(): List<MemberEntity>

    @Query("SELECT * FROM members WHERE member_id = :memberId")
    suspend fun getById(memberId: String): MemberEntity?

    @Insert
    suspend fun insert(entity: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemberEntity)

    @Insert
    suspend fun insertAll(entities: List<MemberEntity>)

    @Update
    suspend fun update(entity: MemberEntity)

    @Query("DELETE FROM members WHERE member_id = :memberId")
    suspend fun delete(memberId: String)

    @Query("DELETE FROM members")
    suspend fun deleteAll()
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state ORDER BY `key`")
    fun observeAll(): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncStateEntity)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SyncStateEntity>)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}

data class FamilyReportAllocationRow(
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "share_amount") val shareAmount: Long,
)

@Dao
interface FamilyAllocationDao {
    @Query("SELECT * FROM family_people WHERE active = 1 ORDER BY name COLLATE NOCASE, uid")
    fun observeActivePeople(): Flow<List<FamilyPersonEntity>>

    @Query("SELECT * FROM family_people ORDER BY name COLLATE NOCASE, uid")
    fun observeAllPeople(): Flow<List<FamilyPersonEntity>>

    @Query("SELECT * FROM family_people ORDER BY name COLLATE NOCASE, uid")
    suspend fun getPeople(): List<FamilyPersonEntity>

    @Query("SELECT * FROM family_people WHERE uid = :uid")
    suspend fun getPerson(uid: String): FamilyPersonEntity?

    @Insert
    suspend fun insertPerson(person: FamilyPersonEntity): Long

    @Insert
    suspend fun insertPeople(people: List<FamilyPersonEntity>)

    @Update
    suspend fun updatePerson(person: FamilyPersonEntity)

    @Query("SELECT * FROM family_tags WHERE active = 1 ORDER BY name COLLATE NOCASE, uid")
    fun observeActiveTags(): Flow<List<FamilyTagEntity>>

    @Query("SELECT * FROM family_tags ORDER BY name COLLATE NOCASE, uid")
    suspend fun getTags(): List<FamilyTagEntity>

    @Query("SELECT * FROM family_tags WHERE uid = :uid")
    suspend fun getTag(uid: String): FamilyTagEntity?

    @Insert
    suspend fun insertTag(tag: FamilyTagEntity): Long

    @Insert
    suspend fun insertTags(tags: List<FamilyTagEntity>)

    @Update
    suspend fun updateTag(tag: FamilyTagEntity)

    @Query("SELECT * FROM family_tag_members ORDER BY tag_uid, person_uid")
    suspend fun getTagMembers(): List<FamilyTagMemberEntity>

    @Query("SELECT * FROM family_tag_members ORDER BY tag_uid, person_uid")
    fun observeTagMembers(): Flow<List<FamilyTagMemberEntity>>

    @Query("SELECT * FROM family_tag_members WHERE tag_uid = :tagUid AND person_uid = :personUid LIMIT 1")
    suspend fun getTagMember(tagUid: String, personUid: String): FamilyTagMemberEntity?

    @Insert
    suspend fun insertTagMember(link: FamilyTagMemberEntity): Long

    @Insert
    suspend fun insertTagMembers(links: List<FamilyTagMemberEntity>)

    @Update
    suspend fun updateTagMember(link: FamilyTagMemberEntity)

    @Query("DELETE FROM family_tag_members WHERE id = :id")
    suspend fun deleteTagMember(id: Long)

    @Query("SELECT * FROM transaction_members WHERE transaction_uid = :transactionUid ORDER BY person_uid")
    suspend fun getTransactionMembers(transactionUid: String): List<TransactionMemberEntity>

    @Query(
        "SELECT tm.* FROM transaction_members tm " +
            "JOIN transactions t ON t.uid = tm.transaction_uid " +
            "WHERE tm.transaction_uid = :transactionUid AND tm.is_private = :privateSpace " +
            "AND t.is_private = :privateSpace ORDER BY tm.person_uid"
    )
    suspend fun getTransactionMembersInSpace(
        transactionUid: String,
        privateSpace: Boolean,
    ): List<TransactionMemberEntity>

    @Query("SELECT * FROM transaction_members WHERE transaction_uid = :transactionUid AND person_uid = :personUid LIMIT 1")
    suspend fun getTransactionMember(transactionUid: String, personUid: String): TransactionMemberEntity?

    @Insert
    suspend fun insertTransactionMember(share: TransactionMemberEntity): Long

    @Insert
    suspend fun insertTransactionMembers(shares: List<TransactionMemberEntity>)

    @Update
    suspend fun updateTransactionMember(share: TransactionMemberEntity)

    @Query("DELETE FROM transaction_members WHERE id = :id")
    suspend fun deleteTransactionMember(id: Long)

    @Query("SELECT * FROM family_people")
    suspend fun getAllPeopleForBackup(): List<FamilyPersonEntity>

    @Query("SELECT * FROM family_tags")
    suspend fun getAllTagsForBackup(): List<FamilyTagEntity>

    @Query("SELECT * FROM family_tag_members")
    suspend fun getAllTagMembersForBackup(): List<FamilyTagMemberEntity>

    @Query("SELECT tm.* FROM transaction_members tm JOIN transactions t ON t.uid = tm.transaction_uid")
    suspend fun getAllTransactionMembersForBackup(): List<TransactionMemberEntity>

    /** Direct lookup (no join): a share can exist briefly without its transaction row, e.g. during Undo. */
    @Query("SELECT * FROM transaction_members WHERE uid = :uid LIMIT 1")
    suspend fun getTransactionMember(uid: String): TransactionMemberEntity?

    @Query("SELECT * FROM family_tag_members WHERE uid = :uid LIMIT 1")
    suspend fun getTagMember(uid: String): FamilyTagMemberEntity?

    @Query(
        """
        SELECT t.id AS transaction_id, SUM(tm.share_amount) AS share_amount
        FROM transaction_members tm
        JOIN transactions t ON t.uid = tm.transaction_uid AND t.is_private = tm.is_private
        JOIN family_people p ON p.uid = tm.person_uid
        WHERE tm.is_private = :privateSpace AND p.uid = :personUid
        GROUP BY t.id
        """
    )
    fun observePersonReportAllocations(personUid: String, privateSpace: Boolean): Flow<List<FamilyReportAllocationRow>>

    @Query(
        """
        SELECT t.id AS transaction_id, SUM(tm.share_amount) AS share_amount
        FROM transaction_members tm
        JOIN transactions t ON t.uid = tm.transaction_uid AND t.is_private = tm.is_private
        JOIN family_tag_members link ON link.person_uid = tm.person_uid
        JOIN family_people p ON p.uid = link.person_uid AND p.active = 1
        JOIN family_tags tag ON tag.uid = link.tag_uid AND tag.active = 1
        WHERE tm.is_private = :privateSpace AND tag.uid = :tagUid
        GROUP BY t.id
        """
    )
    fun observeTagReportAllocations(tagUid: String, privateSpace: Boolean): Flow<List<FamilyReportAllocationRow>>

    @Query(
        """
        SELECT t.id AS transaction_id, t.amount AS share_amount
        FROM transactions t
        WHERE t.is_private = :privateSpace
          AND t.type != 'TRANSFER'
          AND NOT EXISTS (SELECT 1 FROM transaction_members tm WHERE tm.transaction_uid = t.uid)
        """
    )
    fun observeUnassignedReportAllocations(privateSpace: Boolean): Flow<List<FamilyReportAllocationRow>>

    @Query("DELETE FROM family_tag_members")
    suspend fun deleteAllTagMembers()

    @Query("DELETE FROM transaction_members")
    suspend fun deleteAllTransactionMembers()

    @Query("DELETE FROM family_tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM family_people")
    suspend fun deleteAllPeople()
}
