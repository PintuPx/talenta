package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.db.AccountEntity
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.CategoryEntity
import com.homebudget.app.data.db.DefaultData
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.db.toEntity
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a write would break a financial-integrity rule. Message is user-presentable. */
class IntegrityException(message: String) : IllegalArgumentException(message)

@Singleton
class AccountRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.accountDao()

    fun observeAccounts(): Flow<List<Account>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Current balance of every account, computed by [FinanceRules.accountBalances]. */
    fun observeBalances(): Flow<Map<Long, Long>> =
        combine(observeAccounts(), db.transactionDao().observeAll()) { accounts, txns ->
            FinanceRules.accountBalances(accounts, txns.map { it.toDomain() })
        }

    suspend fun save(account: Account): Long {
        val name = account.name.trim()
        if (name.isEmpty()) throw IntegrityException("Account name is required")
        if (kotlin.math.abs(account.openingBalance) > Money.MAX_AMOUNT) throw IntegrityException("Opening balance is too large")
        val now = clock.millis()
        return if (account.id == 0L) {
            dao.insert(
                AccountEntity(
                    name = name, type = account.type, openingBalance = account.openingBalance,
                    includeInTotal = account.includeInTotal, archived = account.archived,
                    sortOrder = dao.nextSortOrder(), createdAt = now, updatedAt = now,
                )
            )
        } else {
            val existing = dao.getById(account.id) ?: throw IntegrityException("Account no longer exists")
            dao.update(
                existing.copy(
                    name = name, type = account.type, openingBalance = account.openingBalance,
                    includeInTotal = account.includeInTotal, archived = account.archived, updatedAt = now,
                )
            )
            account.id
        }
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val e = dao.getById(id) ?: return
        dao.update(e.copy(archived = archived, updatedAt = clock.millis()))
    }

    /** Deletes an account only if no transaction references it. Returns false if it is in use. */
    suspend fun deleteIfUnused(id: Long): Boolean = db.withTransaction {
        if (db.transactionDao().countForAccount(id) > 0 || db.billDao().countForAccount(id) > 0 ||
            db.recurringDao().countForAccount(id) > 0
        ) return@withTransaction false
        dao.delete(id)
        true
    }
}

@Singleton
class CategoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.categoryDao()

    fun observeCategories(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Inserts or updates a category. A group ([Category.parentId] set) must belong to an existing
     * main group of the same kind; groups cannot contain groups (two levels only).
     */
    suspend fun save(category: Category): Long = db.withTransaction {
        val name = category.name.trim()
        if (name.isEmpty()) throw IntegrityException("Category name is required")
        category.parentId?.let { parentId ->
            if (parentId == category.id) throw IntegrityException("A category cannot be its own main group")
            val parent = dao.getById(parentId) ?: throw IntegrityException("Main group not found")
            if (parent.parentId != null) throw IntegrityException("Groups can only be added to a main group")
            if (parent.kind != category.kind) throw IntegrityException("The main group is of a different type")
            if (category.id != 0L && dao.countChildren(category.id) > 0) {
                throw IntegrityException("This category has its own groups, so it cannot be moved into another main group")
            }
        }
        val now = clock.millis()
        if (category.id == 0L) {
            dao.insert(
                CategoryEntity(
                    name = name, kind = category.kind, iconKey = category.iconKey, colorArgb = category.colorArgb,
                    sortOrder = dao.nextSortOrder(category.kind.name), active = true, createdAt = now, updatedAt = now,
                    parentId = category.parentId,
                )
            )
        } else {
            val existing = dao.getById(category.id) ?: throw IntegrityException("Category no longer exists")
            if (existing.kind != category.kind) {
                if (db.transactionDao().countForCategory(category.id) > 0) {
                    throw IntegrityException("A category that has transactions cannot change between income and expense")
                }
                if (dao.countChildren(category.id) > 0) {
                    throw IntegrityException("A main group with groups cannot change between income and expense")
                }
            }
            dao.update(
                existing.copy(
                    name = name, kind = category.kind, iconKey = category.iconKey,
                    colorArgb = category.colorArgb, active = category.active, updatedAt = now,
                    parentId = category.parentId,
                )
            )
            category.id
        }
    }

    /** Categories are deactivated rather than deleted so history keeps its labels. */
    suspend fun setActive(id: Long, active: Boolean) {
        val e = dao.getById(id) ?: return
        dao.update(e.copy(active = active, updatedAt = clock.millis()))
    }
}

/** Filter for the transaction list. Null fields mean "any". */
data class TransactionFilter(
    val range: DateRange? = null,
    val type: TransactionType? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val query: String = "",
    val sort: TransactionSort = TransactionSort.NEWEST,
)

enum class TransactionSort(val key: Int, val label: String) {
    NEWEST(0, "Newest first"),
    OLDEST(1, "Oldest first"),
    LARGEST(2, "Largest amount"),
    SMALLEST(3, "Smallest amount"),
}

@Singleton
class TransactionRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.transactionDao()

    fun observeAll(): Flow<List<Transaction>> = dao.observeAll().map { l -> l.map { it.toDomain() } }

    fun observeRange(range: DateRange): Flow<List<Transaction>> =
        dao.observeBetween(FloatingTime.toStored(range.start), FloatingTime.toStored(range.endExclusive))
            .map { l -> l.map { it.toDomain() } }

    fun observeRecent(limit: Int): Flow<List<Transaction>> = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }

    fun observeFiltered(f: TransactionFilter): Flow<List<Transaction>> = dao.observeFiltered(
        start = f.range?.let { FloatingTime.toStored(it.start) },
        end = f.range?.let { FloatingTime.toStored(it.endExclusive) },
        type = f.type?.name,
        accountId = f.accountId,
        categoryId = f.categoryId,
        query = f.query.trim(),
        sort = f.sort.key,
    ).map { l -> l.map { it.toDomain() } }

    suspend fun get(id: Long): Transaction? = dao.getById(id)?.toDomain()

    suspend fun getForBill(billId: Long): List<Transaction> = dao.getForBill(billId).map { it.toDomain() }

    /**
     * Inserts or updates a transaction after enforcing integrity rules against the current database
     * state (accounts and categories exist, category kind matches the type, transfers are well-formed).
     * Runs in a single database transaction.
     */
    suspend fun save(tx: Transaction): Long = db.withTransaction {
        val normalized = normalize(tx)
        val now = clock.millis()
        if (normalized.id == 0L) {
            dao.insert(normalized.copy(createdAt = now, updatedAt = now).toEntity())
        } else {
            val existing = dao.getById(normalized.id) ?: throw IntegrityException("Transaction no longer exists")
            dao.update(normalized.copy(createdAt = existing.createdAt, updatedAt = now).toEntity())
            normalized.id
        }
    }

    /**
     * Validates [tx] against the database (without saving) and returns it normalised (trimmed text,
     * irrelevant fields cleared). Throws [IntegrityException] with a user-presentable message.
     */
    suspend fun check(tx: Transaction): Transaction = normalize(tx)

    private suspend fun normalize(tx: Transaction): Transaction {
        if (tx.amount <= 0) throw IntegrityException("Amount must be greater than zero")
        if (tx.amount > Money.MAX_AMOUNT) throw IntegrityException("Amount is too large")
        db.accountDao().getById(tx.accountId) ?: throw IntegrityException("Account not found")
        if (tx.billId != null && db.billDao().getById(tx.billId) == null) throw IntegrityException("Bill not found")
        if (tx.recurringId != null && db.recurringDao().getById(tx.recurringId) == null) throw IntegrityException("Repeating transaction not found")
        if (tx.note.length > 500 || tx.payee.length > 100) throw IntegrityException("Note or payee is too long")
        return when (tx.type) {
            TransactionType.TRANSFER -> {
                val to = tx.toAccountId ?: throw IntegrityException("Destination account is required")
                if (to == tx.accountId) throw IntegrityException("Source and destination must be different")
                db.accountDao().getById(to) ?: throw IntegrityException("Destination account not found")
                tx.copy(categoryId = null, isRefund = false, note = tx.note.trim(), payee = tx.payee.trim())
            }
            TransactionType.INCOME, TransactionType.EXPENSE -> {
                val catId = tx.categoryId ?: throw IntegrityException("Category is required")
                val cat = db.categoryDao().getById(catId) ?: throw IntegrityException("Category not found")
                val expected = if (tx.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                if (cat.kind != expected) throw IntegrityException("Category does not match the transaction type")
                tx.copy(
                    toAccountId = null,
                    isRefund = tx.type == TransactionType.EXPENSE && tx.isRefund,
                    note = tx.note.trim(),
                    payee = tx.payee.trim(),
                )
            }
        }
    }

    /** Deletes and returns the removed transaction so the UI can offer Undo via [restore]. */
    suspend fun delete(id: Long): Transaction? = db.withTransaction {
        val existing = dao.getById(id) ?: return@withTransaction null
        dao.delete(id)
        existing.toDomain()
    }

    /** Re-inserts a previously deleted transaction with its original id and timestamps. */
    suspend fun restore(tx: Transaction) {
        db.withTransaction {
            normalize(tx)
            if (dao.getById(tx.id) == null) dao.insert(tx.toEntity())
        }
    }
}

/** Data maintenance operations that touch several tables. */
@Singleton
class DataMaintenanceRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val billImages: BillImageStore,
) {
    /**
     * Deletes every record and bill photo, then restores the default categories (with grocery
     * groups) and Cash account. The database part is atomic.
     */
    suspend fun clearAllData() {
        db.withTransaction {
            deleteAllRows(db)
            insertDefaults(db, clock.millis())
        }
        billImages.deleteAllExcept(emptySet())
    }

    companion object {
        /** Deletes all rows in child-to-parent order so foreign keys never block. */
        suspend fun deleteAllRows(db: AppDatabase) {
            db.transactionDao().deleteAll()
            db.recurringDao().deleteAll()
            db.billDao().deleteAllItems()
            db.billDao().deleteAll()
            db.itemRuleDao().deleteAll()
            db.budgetDao().deleteAll()
            db.accountDao().deleteAll()
            db.categoryDao().deleteGroups()
            db.categoryDao().deleteAll()
        }

        suspend fun insertDefaults(db: AppDatabase, now: Long) {
            val mains = DefaultData.categories(now)
            db.categoryDao().insertAll(mains)
            val groceries = db.categoryDao().getAll().first { it.name == DefaultData.GROCERIES && it.parentId == null }
            db.categoryDao().insertAll(DefaultData.groceryGroups(groceries.id, now))
            db.accountDao().insertAll(DefaultData.accounts(now))
        }
    }
}
