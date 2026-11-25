package com.homebudget.app.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.db.AccountEntity
import com.homebudget.app.data.db.BillEntity
import com.homebudget.app.data.db.BillItemEntity
import com.homebudget.app.data.db.ItemRuleEntity
import com.homebudget.app.data.db.RecurringRuleEntity
import com.homebudget.app.domain.backup.BackupRecurringRule
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.data.repository.DataMaintenanceRepository
import com.homebudget.app.domain.backup.BackupBill
import com.homebudget.app.domain.backup.BackupBillItem
import com.homebudget.app.domain.backup.BackupItemRule
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.BudgetEntity
import com.homebudget.app.data.db.CategoryEntity
import com.homebudget.app.data.db.FloatingTime
import com.homebudget.app.data.db.TransactionEntity
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.backup.BackupAccount
import com.homebudget.app.domain.backup.BackupBudget
import com.homebudget.app.domain.backup.BackupCategory
import com.homebudget.app.domain.backup.BackupCodec
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupFile
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.backup.BackupTransaction
import com.homebudget.app.domain.backup.BackupValidator
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Local backup storage through the Storage Access Framework. [target]/[source] are content:// URIs. */
class SafBackupStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupStorage {
    override suspend fun write(target: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target.toUri(), "wt")
            ?: throw IOException("Cannot open the selected file for writing")
        out.use { it.write(bytes) }
    }

    override suspend fun read(source: String): ByteArray = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source.toUri())
            ?: throw IOException("Cannot open the selected file")
        input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > BackupCodec.MAX_BYTES) throw IOException("The file is too large to be a backup")
            }
            buffer.toByteArray()
        }
    }
}

data class ImportSummary(val accounts: Int, val categories: Int, val transactions: Int)

@Singleton
class BackupRepository @Inject constructor(
    private val db: AppDatabase,
    private val storage: BackupStorage,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val billImages: BillImageStore,
) {
    /** Reads a consistent snapshot of all data. */
    suspend fun createSnapshot(): BackupFile {
        val currency = settings.settings.first().currencyCode
        return db.withTransaction {
            BackupFile(
                exportedAtEpochMillis = clock.millis(),
                currencyCode = currency,
                accounts = db.accountDao().getAll().map {
                    BackupAccount(it.id, it.name, it.type.name, it.openingBalance, it.includeInTotal, it.archived, it.sortOrder, it.createdAt, it.updatedAt)
                },
                categories = db.categoryDao().getAll().map {
                    BackupCategory(it.id, it.name, it.kind.name, it.iconKey, it.colorArgb, it.sortOrder, it.active, it.createdAt, it.updatedAt, it.parentId)
                },
                transactions = db.transactionDao().getAll().map {
                    BackupTransaction(
                        id = it.id, type = it.type.name, amount = it.amount, accountId = it.accountId,
                        toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                        occurredAtIso = FloatingTime.fromStored(it.occurredAt).truncatedTo(ChronoUnit.SECONDS).toString(),
                        note = it.note, payee = it.payee, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        billId = it.billId, recurringId = it.recurringId,
                    )
                },
                recurringRules = db.recurringDao().getAll().map {
                    BackupRecurringRule(
                        id = it.id, type = it.type.name, amount = it.amount, accountId = it.accountId,
                        toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                        note = it.note, payee = it.payee, frequency = it.frequency.name, interval = it.interval,
                        startAt = FloatingTime.fromStored(it.startAt).truncatedTo(ChronoUnit.SECONDS).toString(),
                        postedCount = it.postedCount, active = it.active, createdAt = it.createdAt, updatedAt = it.updatedAt,
                    )
                },
                budgets = db.budgetDao().getAll().map { BackupBudget(it.id, it.month, it.categoryId, it.limitAmount) },
                bills = db.billDao().getAll().map {
                    BackupBill(
                        id = it.id, merchant = it.merchant,
                        billDate = FloatingTime.fromStored(it.billDate).truncatedTo(ChronoUnit.SECONDS).toString(),
                        total = it.total, accountId = it.accountId, imageFile = it.imageFile, ocrText = it.ocrText,
                        status = it.status.name, createdAt = it.createdAt, updatedAt = it.updatedAt,
                    )
                },
                billItems = db.billDao().getAllItems().map {
                    BackupBillItem(it.id, it.billId, it.position, it.description, it.amount, it.categoryId, it.included)
                },
                itemRules = db.itemRuleDao().getAll().map { BackupItemRule(it.key, it.categoryId, it.useCount, it.updatedAt) },
            )
        }
    }

    suspend fun exportTo(target: String): Int {
        val snapshot = createSnapshot()
        storage.write(target, BackupCodec.encode(snapshot))
        return snapshot.transactions.size
    }

    /** Reads and fully validates a backup. The database is not modified. */
    suspend fun inspect(source: String): BackupDecodeResult {
        val bytes = try {
            storage.read(source)
        } catch (e: IOException) {
            return BackupDecodeResult.Invalid(e.message ?: "The file could not be read")
        } catch (e: SecurityException) {
            return BackupDecodeResult.Invalid("Permission to read the file was denied")
        }
        return BackupCodec.decode(bytes, BackupValidator::validate)
    }

    /**
     * Replaces ALL current data with the validated backup in one database transaction. Replacing
     * (rather than merging) makes repeated imports of the same file idempotent: it never duplicates.
     * If anything fails, the transaction rolls back and existing data is untouched.
     */
    suspend fun restore(file: BackupFile): ImportSummary {
        BackupValidator.validate(file)?.let { throw IllegalArgumentException(it) }
        // Photos are not in the backup; keep a bill's photo reference only if that file exists here.
        val bills = file.bills.map { it.copy(imageFile = it.imageFile?.takeIf { name -> billImages.file(name) != null }) }
        db.withTransaction {
            DataMaintenanceRepository.deleteAllRows(db)
            db.accountDao().insertAll(file.accounts.map {
                AccountEntity(it.id, it.name.trim(), AccountType.valueOf(it.type), it.openingBalance, it.includeInTotal, it.archived, it.sortOrder, it.createdAt, it.updatedAt)
            })
            // Main groups before groups so the parent foreign key is satisfied.
            db.categoryDao().insertAll(file.categories.sortedBy { it.parentId != null }.map {
                CategoryEntity(it.id, it.name.trim(), CategoryKind.valueOf(it.kind), it.iconKey, it.colorArgb, it.sortOrder, it.active, it.createdAt, it.updatedAt, it.parentId)
            })
            db.billDao().insertAll(bills.map {
                BillEntity(
                    id = it.id, merchant = it.merchant, billDate = FloatingTime.toStored(LocalDateTime.parse(it.billDate)),
                    total = it.total, accountId = it.accountId, imageFile = it.imageFile, ocrText = it.ocrText,
                    status = BillStatus.valueOf(it.status), createdAt = it.createdAt, updatedAt = it.updatedAt,
                )
            })
            db.recurringDao().insertAll(file.recurringRules.map {
                RecurringRuleEntity(
                    id = it.id, type = TransactionType.valueOf(it.type), amount = it.amount, accountId = it.accountId,
                    toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                    note = it.note, payee = it.payee, frequency = Frequency.valueOf(it.frequency), interval = it.interval,
                    startAt = FloatingTime.toStored(LocalDateTime.parse(it.startAt)), postedCount = it.postedCount,
                    active = it.active, createdAt = it.createdAt, updatedAt = it.updatedAt,
                )
            })
            db.transactionDao().insertAll(file.transactions.map {
                TransactionEntity(
                    id = it.id, type = TransactionType.valueOf(it.type), amount = it.amount, accountId = it.accountId,
                    toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                    occurredAt = FloatingTime.toStored(LocalDateTime.parse(it.occurredAtIso)),
                    note = it.note, payee = it.payee, createdAt = it.createdAt, updatedAt = it.updatedAt,
                    billId = it.billId, recurringId = it.recurringId,
                )
            })
            db.budgetDao().insertAll(file.budgets.map { BudgetEntity(it.id, it.month, it.categoryId, it.limitAmount) })
            db.billDao().insertItems(file.billItems.map {
                BillItemEntity(it.id, it.billId, it.position, it.description, it.amount, it.categoryId, it.included)
            })
            db.itemRuleDao().insertAll(file.itemRules.map { ItemRuleEntity(it.key, it.categoryId, it.useCount, it.updatedAt) })
        }
        billImages.deleteAllExcept(bills.mapNotNullTo(HashSet()) { it.imageFile })
        settings.setCurrency(file.currencyCode)
        return ImportSummary(file.accounts.size, file.categories.size, file.transactions.size)
    }
}
