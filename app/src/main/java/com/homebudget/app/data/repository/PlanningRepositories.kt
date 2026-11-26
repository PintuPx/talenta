package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.BudgetEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.db.toEntity
import com.homebudget.app.data.db.toStoredMonth
import com.homebudget.app.data.db.toYearMonth
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.domain.budget.BudgetRules
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.monthclose.MonthLockRepository
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.recurring.Recurrence
import com.homebudget.app.domain.report.PurchasedLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(
    private val db: AppDatabase,
    private val writer: VersionedWriter,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
) {
    private val dao = db.budgetDao()
    private val privateSpace get() = spaceProvider.current.value == Space.PRIVATE

    fun observeForMonth(month: YearMonth): Flow<List<BudgetLimit>> =
        spaceProvider.current.flatMapLatest {
            dao.observeForMonthInSpace(month.toStoredMonth(), it == Space.PRIVATE)
        }.map { l -> l.map { it.toDomain() } }

    /** The most recent earlier month that has budgets (offered as "copy from"), or null. */
    fun observeLatestMonthBefore(month: YearMonth): Flow<YearMonth?> =
        spaceProvider.current.flatMapLatest {
            dao.observeLatestMonthBeforeInSpace(month.toStoredMonth(), it == Space.PRIVATE)
        }.map { it?.toYearMonth() }

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
        val sameCategory = dao.getForMonthInSpace(month, privateSpace)
            .firstOrNull { it.categoryId == budget.categoryId && it.id != budget.id }
        when {
            sameCategory != null -> {
                val updated = sameCategory.copy(limitAmount = budget.limit)
                writer.apply(
                    writer.change("budget", updated.uid, updated.isPrivate, "EDIT", updated, month = month) {
                        dao.update(updated)
                    },
                ).requireAccepted()
                if (budget.id != 0L) {
                    dao.getByIdInSpace(budget.id, privateSpace)?.let { duplicate ->
                        writer.apply(
                            writer.change(
                                "budget", duplicate.uid, duplicate.isPrivate, "DELETE", duplicate,
                                deleted = true, month = duplicate.month,
                            ) { dao.delete(duplicate.id) },
                        ).requireAccepted()
                    }
                }
                sameCategory.id
            }
            budget.id == 0L -> {
                val entity = BudgetEntity(
                        month = month,
                        categoryId = budget.categoryId,
                        limitAmount = budget.limit,
                        isPrivate = privateSpace,
                    )
                var insertedId = 0L
                val result = writer.apply(
                    writer.change("budget", entity.uid, entity.isPrivate, "ADD", entity, month = month) {
                        insertedId = dao.insert(entity)
                    },
                )
                result.requireAccepted()
                if (result == WriteResult.PENDING_APPROVAL) 0L else insertedId
            }
            else -> {
                val existing = dao.getByIdInSpace(budget.id, privateSpace)
                    ?: throw IntegrityException("Budget no longer exists")
                val updated = existing.copy(month = month, categoryId = budget.categoryId, limitAmount = budget.limit)
                writer.apply(
                    writer.change("budget", updated.uid, updated.isPrivate, "EDIT", updated, month = month) {
                        dao.update(updated)
                    },
                ).requireAccepted()
                budget.id
            }
        }
    }

    suspend fun delete(id: Long) {
        dao.getByIdInSpace(id, privateSpace)?.let { budget ->
            writer.apply(
                writer.change(
                    "budget", budget.uid, budget.isPrivate, "DELETE", budget,
                    deleted = true, month = budget.month,
                ) { dao.delete(id) },
            ).requireAccepted()
        }
    }

    /** Copies [from]'s budgets into [to] (categories already budgeted in [to] are kept). Returns how many were added. */
    suspend fun copy(from: YearMonth, to: YearMonth): Int = db.withTransaction {
        val source = dao.getForMonthInSpace(from.toStoredMonth(), privateSpace).map { it.toDomain() }
        val existing = dao.getForMonthInSpace(to.toStoredMonth(), privateSpace).map { it.toDomain() }
        val added = BudgetRules.copyToMonth(source, existing, to)
        val changes = added.map { budget ->
            val entity = budget.toEntity().copy(isPrivate = privateSpace)
            writer.change("budget", entity.uid, entity.isPrivate, "ADD", entity, month = entity.month) {
                dao.insert(entity)
            }
        }
        val result = writer.applyBulk(changes)
        result.applied + result.pendingApproval
    }
}

@Singleton
class RecurringRepository @Inject constructor(
    private val db: AppDatabase,
    private val transactions: TransactionRepository,
    private val clock: Clock,
    private val writer: VersionedWriter,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
    private val monthLocks: MonthLockRepository,
) {
    private val dao = db.recurringDao()
    private val privateSpace get() = spaceProvider.current.value == Space.PRIVATE

    fun observeRules(): Flow<List<RecurringRule>> =
        spaceProvider.current.flatMapLatest { dao.observeAllInSpace(it == Space.PRIVATE) }
            .map { l -> l.map { it.toDomain() } }

    suspend fun get(id: Long): RecurringRule? = dao.getByIdInSpace(id, privateSpace)?.toDomain()

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
            if (clean.amountChanges && (clean.type != TransactionType.EXPENSE || clean.isRefund)) {
                throw IntegrityException("Changing amounts is only available for repeating expenses.")
            }
            if (clean.interval !in 1..366) throw IntegrityException("Invalid repeat interval")
            val now = clock.millis()
            val existingEntity = if (clean.id == 0L) null else {
                dao.getByIdInSpace(clean.id, privateSpace)
                    ?: throw IntegrityException("This repeating transaction no longer exists")
            }
            if (existingEntity == null) {
                val entity = clean.copy(postedCount = 0, createdAt = now, updatedAt = now)
                    .toEntity().copy(isPrivate = privateSpace)
                var insertedId = 0L
                val result = writer.apply(
                    writer.change("recurring_rule", entity.uid, entity.isPrivate, "ADD", entity) {
                        insertedId = dao.insert(entity)
                    },
                )
                result.requireAccepted()
                if (result == WriteResult.PENDING_APPROVAL) 0L else insertedId
            } else {
                val existing = existingEntity.toDomain()
                val restart = existing.startAt != clean.startAt || existing.frequency != clean.frequency || existing.interval != clean.interval
                val updated = clean.copy(
                    postedCount = if (restart) 0 else existing.postedCount,
                    createdAt = existing.createdAt, updatedAt = now,
                ).toEntity().copy(uid = existingEntity.uid, isPrivate = existingEntity.isPrivate)
                writer.apply(writer.change("recurring_rule", updated.uid, updated.isPrivate, "EDIT", updated) {
                    dao.update(updated)
                }).requireAccepted()
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
        val entity = dao.getByIdInSpace(id, privateSpace) ?: return@withTransaction
        val rule = entity.toDomain()
        var count = rule.postedCount
        if (active && !rule.active) {
            val now = LocalDateTime.now(clock)
            while (Recurrence.occurrence(rule.startAt, rule.frequency, rule.interval, count) < now) count++
        }
        val updated = rule.copy(active = active, postedCount = count, updatedAt = clock.millis()).toEntity()
            .copy(uid = entity.uid, isPrivate = entity.isPrivate)
        writer.apply(writer.change("recurring_rule", updated.uid, updated.isPrivate, "EDIT", updated) {
            dao.update(updated)
        }).requireAccepted()
    }

    /** Deletes the rule. Transactions it already added are kept. */
    suspend fun delete(id: Long) {
        dao.getByIdInSpace(id, privateSpace)?.let { rule ->
            writer.apply(
                writer.change(
                    "recurring_rule", rule.uid, rule.isPrivate, "DELETE", rule, deleted = true,
                ) { dao.delete(id) },
            ).requireAccepted()
        }
    }

    /**
     * Adds every due occurrence of every active rule as a transaction. Each rule is handled in its
     * own database transaction together with its counter, so an occurrence is never added twice.
     * A rule that can no longer be posted (e.g. its account was removed) is paused.
     * Returns the number of transactions added.
     */
    suspend fun postDue(): Int {
        val now = LocalDateTime.now(clock)
        var added = 0
        for (entity in dao.getActiveInSpace(privateSpace)) {
            added += db.withTransaction {
                val current = dao.getByIdInSpace(entity.id, privateSpace)
                    ?.takeIf { it.active } ?: return@withTransaction 0
                val rule = current.toDomain()
                if (rule.amountChanges) return@withTransaction 0
                val due = Recurrence.dueOccurrences(rule, now)
                if (due.isEmpty()) return@withTransaction 0
                val postable = due.takeWhile { !monthLocks.isClosed(YearMonth.from(it)) }
                if (postable.isEmpty()) return@withTransaction 0
                // Check first (no writes), so a failing rule is paused without partial posting.
                val valid = runCatching { transactions.check(Recurrence.toTransaction(rule, postable.first())) }.isSuccess
                if (!valid) {
                    val paused = rule.copy(active = false, updatedAt = clock.millis()).toEntity()
                        .copy(uid = current.uid, isPrivate = current.isPrivate)
                    writer.apply(writer.change("recurring_rule", paused.uid, paused.isPrivate, "EDIT", paused) {
                        dao.update(paused)
                    }).requireAccepted()
                    return@withTransaction 0
                }
                postable.forEach { at ->
                    transactions.save(Recurrence.toTransaction(rule, at), privateOverride = current.isPrivate)
                }
                val updated = rule.copy(postedCount = rule.postedCount + postable.size, updatedAt = clock.millis())
                    .toEntity().copy(uid = current.uid, isPrivate = current.isPrivate)
                writer.apply(writer.change("recurring_rule", updated.uid, updated.isPrivate, "EDIT", updated) {
                    dao.update(updated)
                }).requireAccepted()
                postable.size
            }
        }
        return added
    }

    suspend fun isCurrentUserAdmin(): Boolean {
        val identity = writer.currentIdentity()
        return identity.appMode != AppMode.SHARED ||
            identity.role == FamilyRole.ADMIN
    }

    fun observeCanRecordDueInCurrentSpace(): Flow<Boolean> =
        spaceProvider.current.map { space ->
            space == Space.PRIVATE || isCurrentUserAdmin()
        }

    // REVIEW: posting the transaction and advancing its rule must remain atomic.
    suspend fun postDueOccurrence(
        ruleId: Long,
        amountMinor: Long? = null,
        useCurrentMonth: Boolean = false,
    ): Long {
        val result = db.withTransaction {
            val current = dao.getByIdInSpace(ruleId, privateSpace)
                ?.takeIf { it.active }
                ?: throw IntegrityException("This repeating bill is no longer active.")
            val rule = current.toDomain()
            val occurrence = Recurrence.nextDue(rule)
            if (occurrence > LocalDateTime.now(clock)) throw IntegrityException("This bill is not due yet.")
            val occurrenceMonth = YearMonth.from(occurrence)
            val occurrenceMonthClosed = monthLocks.isClosed(occurrenceMonth)
            if (!rule.amountChanges && !occurrenceMonthClosed) {
                throw IntegrityException("This fixed repeating transaction is posted automatically.")
            }
            val amount = when {
                rule.amountChanges && amountMinor == null -> throw IntegrityException("Enter the amount due.")
                rule.amountChanges -> amountMinor ?: throw IntegrityException("Enter the amount due.")
                amountMinor != null -> throw IntegrityException("This repeating transaction has a fixed amount.")
                else -> rule.amount
            }
            if (amount !in 1..Money.MAX_AMOUNT) throw IntegrityException("Enter a valid amount.")
            val useAt = if (useCurrentMonth) LocalDateTime.now(clock) else occurrence
            if (!useCurrentMonth && occurrenceMonthClosed) {
                writer.requireAdmin()
                monthLocks.reopen(occurrenceMonth)
            }
            val identity = writer.currentIdentity()
            if (
                !current.isPrivate &&
                identity.appMode == AppMode.SHARED &&
                identity.role != FamilyRole.ADMIN
            ) {
                throw IntegrityException("Only the Family Admin can record a shared repeating bill payment.")
            }
            val transactionId = transactions.save(
                Recurrence.toTransaction(rule, useAt, amount),
                privateOverride = current.isPrivate,
            )
            if (transactionId == 0L) throw IntegrityException("This repeating bill payment was not applied.")
            val updated = rule.copy(
                amount = amount,
                postedCount = rule.postedCount + 1,
                updatedAt = clock.millis(),
            ).toEntity().copy(uid = current.uid, isPrivate = current.isPrivate)
            writer.apply(writer.change("recurring_rule", updated.uid, updated.isPrivate, "EDIT", updated) {
                dao.update(updated)
            }).requireAccepted()
            current.id
        }
        postDue()
        return result
    }
}

/** Approved bill lines, for item price trends. */
@Singleton
class PurchaseRepository @Inject constructor(
    private val db: AppDatabase,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
) {
    fun observePurchasedLines(): Flow<List<PurchasedLine>> =
        spaceProvider.current.flatMapLatest { db.billDao().observePurchasedLinesInSpace(it == Space.PRIVATE) }
            .map { rows ->
                rows.map { PurchasedLine(it.description, it.amount, FloatingTime.fromStored(it.billDate).toLocalDate(), it.merchant) }
            }
}
