package com.homebudget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY archived ASC, sort_order ASC, id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

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

    @Query("DELETE FROM accounts")
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

    @Query("SELECT * FROM transactions ORDER BY id")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE occurred_at >= :start AND occurred_at < :end")
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

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

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

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

    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query("SELECT * FROM transactions WHERE bill_id = :billId ORDER BY id")
    suspend fun getForBill(billId: Long): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE bill_id = :billId")
    suspend fun deleteForBill(billId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE recurring_id = :ruleId")
    suspend fun countForRecurring(ruleId: Long): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface BillDao {
    @Query("SELECT * FROM bills ORDER BY bill_date DESC, id DESC")
    fun observeAll(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills ORDER BY id")
    suspend fun getAll(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): BillEntity?

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: Long): Flow<BillEntity?>

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

    @Query("SELECT * FROM bill_items WHERE bill_id = :billId ORDER BY position, id")
    suspend fun getItems(billId: Long): List<BillItemEntity>

    @Query("SELECT * FROM bill_items ORDER BY bill_id, position, id")
    suspend fun getAllItems(): List<BillItemEntity>

    @Insert
    suspend fun insertItems(items: List<BillItemEntity>)

    @Query("DELETE FROM bill_items WHERE bill_id = :billId")
    suspend fun deleteItems(billId: Long)

    @Query("DELETE FROM bill_items")
    suspend fun deleteAllItems()

    @Query("SELECT COUNT(*) FROM bills WHERE account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    /** Included lines of approved bills, for item price trends. */
    @Query(
        """
        SELECT i.description AS description, i.amount AS amount, b.bill_date AS bill_date, b.merchant AS merchant
        FROM bill_items i JOIN bills b ON b.id = i.bill_id
        WHERE b.status = 'POSTED' AND i.included = 1 AND i.amount > 0
        """
    )
    fun observePurchasedLines(): Flow<List<PurchasedLineRow>>
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

    @Insert
    suspend fun insertAll(entities: List<ItemRuleEntity>)

    @Query("DELETE FROM item_rules")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE month = :month")
    fun observeForMonth(month: Int): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE month = :month")
    suspend fun getForMonth(month: Int): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): BudgetEntity?

    /** The latest month before [month] that has any budget, or null. */
    @Query("SELECT MAX(month) FROM budgets WHERE month < :month")
    fun observeLatestMonthBefore(month: Int): Flow<Int?>

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
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_rules ORDER BY active DESC, id ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY id")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE active = 1 ORDER BY id")
    suspend fun getActive(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun getById(id: Long): RecurringRuleEntity?

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

    @Query("SELECT COUNT(*) FROM recurring_rules WHERE account_id = :accountId OR to_account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int
}
