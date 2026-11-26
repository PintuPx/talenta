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
import com.homebudget.app.data.db.toStoredMonth
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.family.ExpenseShare
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a write would break a financial-integrity rule. Message is user-presentable. */
class IntegrityException(message: String) : IllegalArgumentException(message)

@Singleton
class AccountRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val writer: VersionedWriter,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
) {
    private val dao = db.accountDao()
    private val privateSpace get() = spaceProvider.current.value == Space.PRIVATE

    fun observeAccounts(): Flow<List<Account>> =
        spaceProvider.current.flatMapLatest { dao.observeInSpace(it == Space.PRIVATE) }
            .map { list -> list.map { it.toDomain() } }

    /** Ledger balance before applying any saved investment valuation. */
    fun observeLedgerBalances(): Flow<Map<Long, Long>> =
        combine(
            observeAccounts(),
            spaceProvider.current.flatMapLatest { db.transactionDao().observeInSpace(it == Space.PRIVATE) },
        ) { accounts, txns ->
            FinanceRules.accountBalances(accounts, txns.map { it.toDomain() })
        }

    /** Latest saved investment values replace ledger balances for account and total-balance displays. */
    fun observeBalances(): Flow<Map<Long, Long>> =
        combine(
            observeLedgerBalances(),
            spaceProvider.current.flatMapLatest { db.investmentValueDao().observeLatestInSpace(it == Space.PRIVATE) },
        ) { ledger, values ->
            ledger + values.associate { it.accountId to it.valueMinor }
        }

    suspend fun save(account: Account): Long {
        val name = account.name.trim()
        if (name.isEmpty()) throw IntegrityException("Account name is required")
        if (kotlin.math.abs(account.openingBalance) > Money.MAX_AMOUNT) throw IntegrityException("Opening balance is too large")
        if (account.type == AccountType.INVESTMENT && account.openingBalance < 0) {
            throw IntegrityException("Investment contributions cannot start below zero.")
        }
        val now = clock.millis()
        return if (account.id == 0L) {
            val entity = AccountEntity(
                name = name, type = account.type, openingBalance = account.openingBalance,
                includeInTotal = account.includeInTotal, archived = account.archived,
                sortOrder = dao.nextSortOrderInSpace(privateSpace), createdAt = now, updatedAt = now,
                isPrivate = privateSpace,
            )
            var insertedId = 0L
            val result = writer.apply(
                writer.change("account", entity.uid, entity.isPrivate, "ADD", entity) {
                    insertedId = dao.insert(entity)
                },
            )
            result.requireAccepted()
            if (result == WriteResult.PENDING_APPROVAL) 0L else insertedId
        } else {
            val existing = dao.getByIdInSpace(account.id, privateSpace) ?: throw IntegrityException("Account no longer exists")
            val updated = existing.copy(
                name = name, type = account.type, openingBalance = account.openingBalance,
                includeInTotal = account.includeInTotal, archived = account.archived, updatedAt = now,
            )
            writer.apply(
                writer.change(
                    "account",
                    updated.uid,
                    updated.isPrivate,
                    "EDIT",
                    updated,
                    validate = {
                        if (existing.type != AccountType.INVESTMENT &&
                            account.type == AccountType.INVESTMENT &&
                            db.transactionDao().countNonTransferForAccountInSpace(account.id, existing.isPrivate) > 0
                        ) {
                            throw IntegrityException("An account with income or expense history cannot become an investment account.")
                        }
                        if (existing.type == AccountType.INVESTMENT &&
                            account.type != AccountType.INVESTMENT &&
                            db.investmentValueDao().getAll().any { it.accountId == account.id }
                        ) {
                            throw IntegrityException("This investment has saved values and must remain an investment account.")
                        }
                    },
                ) { dao.update(updated) },
            )
                .requireAccepted()
            account.id
        }
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val e = dao.getByIdInSpace(id, privateSpace) ?: return
        val updated = e.copy(archived = archived, updatedAt = clock.millis())
        writer.apply(writer.change("account", updated.uid, updated.isPrivate, "EDIT", updated) { dao.update(updated) })
            .requireAccepted()
    }

    /** Deletes an account only if no transaction references it. Returns false if it is in use. */
    suspend fun deleteIfUnused(id: Long): Boolean = db.withTransaction {
        if (db.transactionDao().countForAccountInSpace(id, privateSpace) > 0 ||
            db.billDao().countForAccountInSpace(id, privateSpace) > 0 ||
            db.recurringDao().countForAccountInSpace(id, privateSpace) > 0 ||
            db.investmentValueDao().getAll().any { it.accountId == id }
        ) return@withTransaction false
        val account = dao.getByIdInSpace(id, privateSpace) ?: return@withTransaction false
        writer.apply(
            writer.change("account", account.uid, account.isPrivate, "DELETE", account, deleted = true) {
                dao.delete(id)
            },
        ).requireAccepted()
        true
    }
}

@Singleton
class CategoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val writer: VersionedWriter,
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
            val entity = CategoryEntity(
                name = name, kind = category.kind, iconKey = category.iconKey, colorArgb = category.colorArgb,
                sortOrder = dao.nextSortOrder(category.kind.name), active = true, createdAt = now, updatedAt = now,
                parentId = category.parentId,
            )
            var insertedId = 0L
            val result = writer.apply(
                writer.change("category", entity.uid, false, "ADD", entity) { insertedId = dao.insert(entity) },
            )
            result.requireAccepted()
            if (result == WriteResult.PENDING_APPROVAL) 0L else insertedId
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
            val updated = existing.copy(
                name = name, kind = category.kind, iconKey = category.iconKey,
                colorArgb = category.colorArgb, active = category.active, updatedAt = now,
                parentId = category.parentId,
            )
            writer.apply(writer.change("category", updated.uid, false, "EDIT", updated) { dao.update(updated) })
                .requireAccepted()
            category.id
        }
    }

    /** Categories are deactivated rather than deleted so history keeps its labels. */
    suspend fun setActive(id: Long, active: Boolean) {
        val e = dao.getById(id) ?: return
        val updated = e.copy(active = active, updatedAt = clock.millis())
        writer.apply(writer.change("category", updated.uid, false, "EDIT", updated) { dao.update(updated) })
            .requireAccepted()
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
    private val writer: VersionedWriter,
    private val expenseFamily: ExpenseFamilyRepository,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
) {
    private val dao = db.transactionDao()
    private val deletedTransactionUids = ConcurrentHashMap<Long, String>()
    private val privateSpace get() = spaceProvider.current.value == Space.PRIVATE

    fun observeAll(): Flow<List<Transaction>> =
        spaceProvider.current.flatMapLatest { dao.observeInSpace(it == Space.PRIVATE) }
            .map { l -> l.map { it.toDomain() } }

    fun observePayees(): Flow<List<String>> =
        spaceProvider.current.flatMapLatest { dao.observePayeesInSpace(it == Space.PRIVATE) }

    fun observeRange(range: DateRange): Flow<List<Transaction>> =
        spaceProvider.current.flatMapLatest {
            dao.observeBetweenInSpace(FloatingTime.toStored(range.start), FloatingTime.toStored(range.endExclusive), it == Space.PRIVATE)
        }
            .map { l -> l.map { it.toDomain() } }

    /** True while the current space has at least one transaction in [range]. */
    fun observeHasAnyInRange(range: DateRange): Flow<Boolean> =
        spaceProvider.current.flatMapLatest {
            dao.observeAnyBetweenInSpace(FloatingTime.toStored(range.start), FloatingTime.toStored(range.endExclusive), it == Space.PRIVATE)
        }

    fun observeRecent(limit: Int): Flow<List<Transaction>> =
        spaceProvider.current.flatMapLatest { dao.observeRecentInSpace(limit, it == Space.PRIVATE) }
            .map { l -> l.map { it.toDomain() } }

    fun observeFiltered(f: TransactionFilter): Flow<List<Transaction>> =
        spaceProvider.current.flatMapLatest {
            dao.observeFilteredInSpace(
                start = f.range?.let { range -> FloatingTime.toStored(range.start) },
                end = f.range?.let { range -> FloatingTime.toStored(range.endExclusive) },
                type = f.type?.name,
                accountId = f.accountId,
                categoryId = f.categoryId,
                query = f.query.trim(),
                sort = f.sort.key,
                privateSpace = it == Space.PRIVATE,
            )
        }.map { l -> l.map { it.toDomain() } }

    suspend fun get(id: Long): Transaction? = dao.getByIdInSpace(id, privateSpace)?.toDomain()

    suspend fun uid(id: Long): String? = dao.getByIdInSpace(id, privateSpace)?.uid

    suspend fun getForBill(billId: Long): List<Transaction> =
        dao.getForBillInSpace(billId, privateSpace).map { it.toDomain() }

    /**
     * Inserts or updates a transaction after enforcing integrity rules against the current database
     * state (accounts and categories exist, category kind matches the type, transfers are well-formed).
     * Runs in a single database transaction.
     */
    suspend fun save(
        tx: Transaction,
        privateOverride: Boolean? = null,
        memberUids: Set<String> = emptySet(),
    ): Long = db.withTransaction {
        val normalized = normalize(tx)
        val now = clock.millis()
        if (normalized.id == 0L) {
            val entity = normalized.copy(createdAt = now, updatedAt = now).toEntity()
                .copy(isPrivate = privateOverride ?: privateSpace)
            var insertedId = 0L
            val result = writer.apply(
                writer.change(
                    "transaction", entity.uid, entity.isPrivate, "ADD", entity,
                    month = monthOf(entity),
                    validate = { normalize(normalized) },
                ) { insertedId = dao.insert(entity) },
            )
            result.requireAccepted()
            expenseFamily.saveTransactionShares(
                transactionUid = entity.uid,
                totalMinor = entity.amount,
                personUids = if (entity.type == TransactionType.TRANSFER) emptySet() else memberUids,
                privateSpace = entity.isPrivate,
                month = monthOf(entity),
            )
            if (result == WriteResult.PENDING_APPROVAL) 0L else insertedId
        } else {
            val existing = dao.getByIdInSpace(normalized.id, privateSpace)
                ?: throw IntegrityException("Transaction no longer exists")
            val entity = normalized.copy(createdAt = existing.createdAt, updatedAt = now).toEntity()
                .copy(uid = existing.uid, isPrivate = privateOverride ?: existing.isPrivate)
            writer.apply(
                writer.change(
                    "transaction", entity.uid, entity.isPrivate, "EDIT", entity,
                    month = monthOf(entity),
                    validate = { normalize(normalized) },
                ) { dao.update(entity) },
            ).requireAccepted()
            expenseFamily.saveTransactionShares(
                transactionUid = entity.uid,
                totalMinor = entity.amount,
                personUids = if (entity.type == TransactionType.TRANSFER) emptySet() else memberUids,
                privateSpace = entity.isPrivate,
                month = monthOf(entity),
            )
            normalized.id
        }
    }

    suspend fun memberShares(id: Long): List<ExpenseShare> {
        val entity = dao.getByIdInSpace(id, privateSpace) ?: return emptyList()
        return expenseFamily.transactionShares(entity.uid, privateSpace)
    }

    /**
     * Validates [tx] against the database (without saving) and returns it normalised (trimmed text,
     * irrelevant fields cleared). Throws [IntegrityException] with a user-presentable message.
     */
    suspend fun check(tx: Transaction): Transaction = normalize(tx)

    private suspend fun normalize(tx: Transaction): Transaction {
        if (tx.amount <= 0) throw IntegrityException("Amount must be greater than zero")
        if (tx.amount > Money.MAX_AMOUNT) throw IntegrityException("Amount is too large")
        val sourceAccount = db.accountDao().getByIdInSpace(tx.accountId, privateSpace)
            ?: throw IntegrityException("Account not found")
        if (tx.type != TransactionType.TRANSFER && sourceAccount.type == AccountType.INVESTMENT) {
            throw IntegrityException("Use a transfer to add or withdraw investment money.")
        }
        if (tx.billId != null && db.billDao().getByIdInSpace(tx.billId, privateSpace) == null) {
            throw IntegrityException("Bill not found")
        }
        if (tx.recurringId != null && db.recurringDao().getByIdInSpace(tx.recurringId, privateSpace) == null) {
            throw IntegrityException("Repeating transaction not found")
        }
        if (tx.note.length > 500 || tx.payee.length > 100) throw IntegrityException("Note or payee is too long")
        return when (tx.type) {
            TransactionType.TRANSFER -> {
                val to = tx.toAccountId ?: throw IntegrityException("Destination account is required")
                if (to == tx.accountId) throw IntegrityException("Source and destination must be different")
                db.accountDao().getByIdInSpace(to, privateSpace) ?: throw IntegrityException("Destination account not found")
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

    private fun monthOf(entity: com.homebudget.app.data.db.TransactionEntity): Int =
        YearMonth.from(FloatingTime.fromStored(entity.occurredAt)).toStoredMonth()

    /** Deletes and returns the removed transaction so the UI can offer Undo via [restore]. */
    suspend fun delete(id: Long): Transaction? = db.withTransaction {
        val existing = dao.getByIdInSpace(id, privateSpace) ?: return@withTransaction null
        writer.apply(
            writer.change(
                "transaction", existing.uid, existing.isPrivate, "DELETE", existing,
                deleted = true,
                month = monthOf(existing),
            ) { dao.delete(id) },
        ).requireAccepted()
        deletedTransactionUids[id] = existing.uid
        existing.toDomain()
    }

    /** Re-inserts a previously deleted transaction with its original id and timestamps. */
    suspend fun restore(tx: Transaction, privateOverride: Boolean = privateSpace) {
        db.withTransaction {
            normalize(tx)
            if (dao.getByIdInSpace(tx.id, privateSpace) == null) {
                val restored = tx.toEntity()
                val entity = restored.copy(uid = deletedTransactionUids[tx.id] ?: restored.uid, isPrivate = privateOverride)
                writer.apply(
                    writer.change(
                        "transaction", entity.uid, entity.isPrivate, "RESTORE", entity,
                        month = monthOf(entity),
                        validate = { normalize(tx) },
                    ) { dao.insert(entity) },
                ).requireAccepted()
                deletedTransactionUids.remove(tx.id)
            }
        }
    }
}

/** Data maintenance operations that touch several tables. */
@Singleton
class DataMaintenanceRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val billImages: BillImageStore,
    private val writer: VersionedWriter,
) {
    /**
     * Deletes every record and bill photo, then restores the default categories (with grocery
     * groups) and Cash account. The database part is atomic.
     */
    suspend fun clearAllData() {
        writer.applySystem {
            db.withTransaction {
                deleteAllRows(db)
                insertDefaults(db, clock.millis())
            }
        }
        billImages.deleteAllExcept(emptySet())
    }

    companion object {
        /** Deletes all rows in child-to-parent order so foreign keys never block. */
        suspend fun deleteAllRows(db: AppDatabase) {
            db.investmentValueDao().deleteAll()
            db.familyAllocationDao().deleteAllTransactionMembers()
            db.familyAllocationDao().deleteAllTagMembers()
            db.familyAllocationDao().deleteAllTags()
            db.familyAllocationDao().deleteAllPeople()
            db.transactionDao().deleteAll()
            db.recurringDao().deleteAll()
            db.billDao().deleteAllItems()
            db.billDao().deleteAll()
            db.itemRuleDao().deleteAll()
            db.budgetDao().deleteAll()
            db.accountDao().deleteAll()
            db.categoryDao().deleteGroups()
            db.categoryDao().deleteAll()
            db.entityVersionDao().deleteAll()
            db.monthLockDao().deleteAll()
            db.memberDao().deleteAll()
            db.syncStateDao().deleteAll()
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
