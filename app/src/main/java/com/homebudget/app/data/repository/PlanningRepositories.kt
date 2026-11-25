package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.BudgetEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.db.toEntity
import com.homebudget.app.data.db.toStoredMonth
import com.homebudget.app.data.db.toYearMonth
import com.homebudget.app.domain.budget.BudgetRules
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.recurring.Recurrence
import com.homebudget.app.domain.report.PurchasedLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val dao = db.budgetDao()

    fun observeForMonth(month: YearMonth): Flow<List<BudgetLimit>> =
        dao.observeForMonth(month.toStoredMonth()).map { l -> l.map { it.toDomain() } }

    /** The most recent earlier month that has budgets (offered as "copy from"), or null. */
    fun observeLatestMonthBefore(month: YearMonth): Flow<YearMonth?> =
        dao.observeLatestMonthBefore(month.toStoredMonth()).map { it?.toYearMonth() }

    /**
     * Saves a budget. There is at most one budget per category per month (and one overall budget):
     * saving a second one for the same category updates the existing one.
     */
    suspend fun save(budget: BudgetLimit): Long = db.withTransaction {
        if (budget.limit <= 0) throw IntegrityException("The budget must be greater than zero")
        if (budget.limit > Money.MAX_AMOUNT) throw IntegrityException("The budget is too large")
        budget.categoryId?.let { id ->
            val c = db.categoryDao().getById(id) ?: throw IntegrityException("Category not found")
            if (c.kind != CategoryKind.EXPENSE) throw IntegrityException("Budgets can only be set for expense categories")
        }
        val month = budget.month.toStoredMonth()
        val sameCategory = dao.getForMonth(month).firstOrNull { it.categoryId == budget.categoryId && it.id != budget.id }
        when {
            sameCategory != null -> {
                dao.update(sameCategory.copy(limitAmount = budget.limit))
                if (budget.id != 0L) dao.delete(budget.id)
                sameCategory.id
            }
            budget.id == 0L -> dao.insert(BudgetEntity(month = month, categoryId = budget.categoryId, limitAmount = budget.limit))
            else -> {
                val existing = dao.getById(budget.id) ?: throw IntegrityException("Budget no longer exists")
                dao.update(existing.copy(month = month, categoryId = budget.categoryId, limitAmount = budget.limit))
                budget.id
            }
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Copies [from]'s budgets into [to] (categories already budgeted in [to] are kept). Returns how many were added. */
    suspend fun copy(from: YearMonth, to: YearMonth): Int = db.withTransaction {
        val source = dao.getForMonth(from.toStoredMonth()).map { it.toDomain() }
        val existing = dao.getForMonth(to.toStoredMonth()).map { it.toDomain() }
        val added = BudgetRules.copyToMonth(source, existing, to)
        added.forEach { dao.insert(it.toEntity()) }
        added.size
    }
}

@Singleton
class RecurringRepository @Inject constructor(
    private val db: AppDatabase,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {
    private val dao = db.recurringDao()

    fun observeRules(): Flow<List<RecurringRule>> = dao.observeAll().map { l -> l.map { it.toDomain() } }

    suspend fun get(id: Long): RecurringRule? = dao.getById(id)?.toDomain()

    /**
     * Inserts or updates a rule, then adds any occurrences already due. When an existing rule's
     * start changes, counting restarts from the new start (it is the next occurrence).
     * Returns the rule id.
     */
    suspend fun save(rule: RecurringRule): Long {
        val id = db.withTransaction {
            // Validates accounts, category and type exactly like a normal transaction.
            val checked = transactions.check(Recurrence.toTransaction(rule.copy(id = 0), rule.startAt).copy(recurringId = null))
            val clean = rule.copy(
                amount = checked.amount, toAccountId = checked.toAccountId, categoryId = checked.categoryId,
                isRefund = checked.isRefund, note = checked.note, payee = checked.payee,
            )
            if (clean.interval !in 1..366) throw IntegrityException("Invalid repeat interval")
            val now = clock.millis()
            if (clean.id == 0L) {
                dao.insert(clean.copy(postedCount = 0, createdAt = now, updatedAt = now).toEntity())
            } else {
                val existing = dao.getById(clean.id)?.toDomain() ?: throw IntegrityException("This repeating transaction no longer exists")
                val restart = existing.startAt != clean.startAt || existing.frequency != clean.frequency || existing.interval != clean.interval
                dao.update(
                    clean.copy(
                        postedCount = if (restart) 0 else existing.postedCount,
                        createdAt = existing.createdAt, updatedAt = now,
                    ).toEntity()
                )
                clean.id
            }
        }
        postDue()
        return id
    }

    /**
     * Pauses or resumes a rule. Resuming skips the occurrences missed while paused (they are not
     * back-filled); the next one is the first occurrence from now on.
     */
    suspend fun setActive(id: Long, active: Boolean) = db.withTransaction {
        val rule = dao.getById(id)?.toDomain() ?: return@withTransaction
        var count = rule.postedCount
        if (active && !rule.active) {
            val now = LocalDateTime.now(clock)
            while (Recurrence.occurrence(rule.startAt, rule.frequency, rule.interval, count) < now) count++
        }
        dao.update(rule.copy(active = active, postedCount = count, updatedAt = clock.millis()).toEntity())
    }

    /** Deletes the rule. Transactions it already added are kept. */
    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Adds every due occurrence of every active rule as a transaction. Each rule is handled in its
     * own database transaction together with its counter, so an occurrence is never added twice.
     * A rule that can no longer be posted (e.g. its account was removed) is paused.
     * Returns the number of transactions added.
     */
    suspend fun postDue(): Int {
        val now = LocalDateTime.now(clock)
        var added = 0
        for (entity in dao.getActive()) {
            added += db.withTransaction {
                val rule = dao.getById(entity.id)?.toDomain()?.takeIf { it.active } ?: return@withTransaction 0
                val due = Recurrence.dueOccurrences(rule, now)
                if (due.isEmpty()) return@withTransaction 0
                // Check first (no writes), so a failing rule is paused without partial posting.
                val valid = runCatching { transactions.check(Recurrence.toTransaction(rule, due.first())) }.isSuccess
                if (!valid) {
                    dao.update(rule.copy(active = false, updatedAt = clock.millis()).toEntity())
                    return@withTransaction 0
                }
                due.forEach { at -> transactions.save(Recurrence.toTransaction(rule, at)) }
                dao.update(rule.copy(postedCount = rule.postedCount + due.size, updatedAt = clock.millis()).toEntity())
                due.size
            }
        }
        return added
    }
}

/** Approved bill lines, for item price trends. */
@Singleton
class PurchaseRepository @Inject constructor(private val db: AppDatabase) {
    fun observePurchasedLines(): Flow<List<PurchasedLine>> = db.billDao().observePurchasedLines().map { rows ->
        rows.map { PurchasedLine(it.description, it.amount, FloatingTime.fromStored(it.billDate).toLocalDate(), it.merchant) }
    }
}
