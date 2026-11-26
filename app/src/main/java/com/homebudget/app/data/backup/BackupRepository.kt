package com.homebudget.app.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.db.AccountEntity
import com.homebudget.app.data.db.BillEntity
import com.homebudget.app.data.db.BillItemEntity
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.data.db.FamilyPersonEntity
import com.homebudget.app.data.db.FamilyTagEntity
import com.homebudget.app.data.db.FamilyTagMemberEntity
import com.homebudget.app.data.db.ItemRuleEntity
import com.homebudget.app.data.db.InvestmentValueEntity
import com.homebudget.app.data.db.MemberEntity
import com.homebudget.app.data.db.MonthLockEntity
import com.homebudget.app.data.db.RecurringRuleEntity
import com.homebudget.app.data.db.SnapshotReferences
import com.homebudget.app.data.db.TransactionMemberEntity
import com.homebudget.app.data.db.toSnapshot
import com.homebudget.app.domain.backup.BackupRecurringRule
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.data.repository.DataMaintenanceRepository
import com.homebudget.app.data.repository.VersionedWriter
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
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.domain.backup.BackupAccount
import com.homebudget.app.domain.backup.BackupBudget
import com.homebudget.app.domain.backup.BackupCategory
import com.homebudget.app.domain.backup.BackupEntityVersion
import com.homebudget.app.domain.backup.BackupCodec
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupFile
import com.homebudget.app.domain.backup.BackupMember
import com.homebudget.app.domain.backup.BackupMonthLock
import com.homebudget.app.domain.backup.BackupFamilyPerson
import com.homebudget.app.domain.backup.BackupFamilyTag
import com.homebudget.app.domain.backup.BackupFamilyTagMember
import com.homebudget.app.domain.backup.BackupTransactionMember
import com.homebudget.app.domain.backup.BackupInvestmentValue
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.backup.BackupTransaction
import com.homebudget.app.domain.backup.BackupValidator
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.Clock
import java.util.UUID
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
    private val writer: VersionedWriter,
) {
    private val snapshotJson = Json { encodeDefaults = true }

    /** Reads a consistent snapshot of all data. */
    suspend fun createSnapshot(): BackupFile {
        val currency = settings.settings.first().currencyCode
        return db.withTransaction {
            val accountUids = db.accountDao().getAll().associate { it.id to it.uid }
            BackupFile(
                exportedAtEpochMillis = clock.millis(),
                currencyCode = currency,
                accounts = db.accountDao().getAll().map {
                    BackupAccount(it.id, it.name, it.type.name, it.openingBalance, it.includeInTotal, it.archived, it.sortOrder, it.createdAt, it.updatedAt, it.uid, it.isPrivate)
                },
                categories = db.categoryDao().getAll().map {
                    BackupCategory(it.id, it.name, it.kind.name, it.iconKey, it.colorArgb, it.sortOrder, it.active, it.createdAt, it.updatedAt, it.parentId, it.uid)
                },
                transactions = db.transactionDao().getAll().map {
                    BackupTransaction(
                        id = it.id, type = it.type.name, amount = it.amount, accountId = it.accountId,
                        toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                        occurredAtIso = FloatingTime.fromStored(it.occurredAt).truncatedTo(ChronoUnit.SECONDS).toString(),
                        note = it.note, payee = it.payee, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        billId = it.billId, recurringId = it.recurringId,
                        uid = it.uid, isPrivate = it.isPrivate,
                    )
                },
                recurringRules = db.recurringDao().getAll().map {
                    BackupRecurringRule(
                        id = it.id, type = it.type.name, amount = it.amount, accountId = it.accountId,
                        toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                        note = it.note, payee = it.payee, frequency = it.frequency.name, interval = it.interval,
                        startAt = FloatingTime.fromStored(it.startAt).truncatedTo(ChronoUnit.SECONDS).toString(),
                        postedCount = it.postedCount, active = it.active, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        uid = it.uid, isPrivate = it.isPrivate,
                        amountChanges = it.amountChanges,
                    )
                },
                budgets = db.budgetDao().getAll().map { BackupBudget(it.id, it.month, it.categoryId, it.limitAmount, it.uid, it.isPrivate) },
                bills = db.billDao().getAll().map {
                    BackupBill(
                        id = it.id, merchant = it.merchant,
                        billDate = FloatingTime.fromStored(it.billDate).truncatedTo(ChronoUnit.SECONDS).toString(),
                        total = it.total, accountId = it.accountId, imageFile = it.imageFile, ocrText = it.ocrText,
                        status = it.status.name, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        uid = it.uid, isPrivate = it.isPrivate,
                    )
                },
                billItems = db.billDao().getAllItems().map {
                    BackupBillItem(it.id, it.billId, it.position, it.description, it.amount, it.categoryId, it.included, it.uid)
                },
                itemRules = db.itemRuleDao().getAll().map { BackupItemRule(it.key, it.categoryId, it.useCount, it.updatedAt, it.uid) },
                entityVersions = db.entityVersionDao().getAll().map { it.toBackup() },
                monthLocks = db.monthLockDao().getAll().map { BackupMonthLock(it.month, it.closedAt, it.closedBy) },
                members = db.memberDao().getAll().map { it.toBackup() },
                familyPeople = db.familyAllocationDao().getAllPeopleForBackup().map {
                    BackupFamilyPerson(it.id, it.name, it.active, it.createdAt, it.updatedAt, it.uid)
                },
                familyTags = db.familyAllocationDao().getAllTagsForBackup().map {
                    BackupFamilyTag(it.id, it.name, it.active, it.createdAt, it.updatedAt, it.uid)
                },
                familyTagMembers = db.familyAllocationDao().getAllTagMembersForBackup().map {
                    BackupFamilyTagMember(it.id, it.tagUid, it.personUid, it.createdAt, it.uid)
                },
                transactionMembers = db.familyAllocationDao().getAllTransactionMembersForBackup().map {
                    BackupTransactionMember(it.id, it.transactionUid, it.personUid, it.shareAmount, it.isPrivate, it.uid)
                },
                investmentValues = db.investmentValueDao().getAllForBackup().map {
                    BackupInvestmentValue(it.id, accountUids.getValue(it.accountId), it.valueMinor, it.recordedAt, it.isPrivate, it.uid)
                },
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
    // REVIEW: backups include private data and Family restores replace both current rows and history.
    suspend fun restore(file: BackupFile): ImportSummary {
        BackupValidator.validate(file)?.let { throw IllegalArgumentException(it) }
        val settingsAtRestore = settings.settings.first()
        val existingMembers = db.memberDao().getAll()
        val familyAdmin = if (settingsAtRestore.appMode == AppMode.SHARED) {
            val memberId = settingsAtRestore.currentMemberId
                ?: throw IllegalArgumentException("Only the Family Admin can restore a backup")
            existingMembers.firstOrNull { it.memberId == memberId && it.active && it.role == "ADMIN" }
                ?: throw IllegalArgumentException("Only the Family Admin can restore a backup")
        } else {
            null
        }
        if (familyAdmin != null && file.formatVersion >= 5 &&
            file.members.none { it.memberId == familyAdmin.memberId && it.active && it.role == "ADMIN" }
        ) {
            throw IllegalArgumentException("This backup does not belong to this Family")
        }

        val withUids = file.withRestorableUids()
        // Photos are not in the backup; keep a bill's photo reference only if that file exists here.
        val bills = withUids.bills.map { it.copy(imageFile = it.imageFile?.takeIf { name -> billImages.file(name) != null }) }
        val members = if (file.formatVersion >= 5) {
            withUids.members.map { it.toEntity() }
        } else {
            existingMembers
        }
        writer.applySystem {
            db.withTransaction {
                DataMaintenanceRepository.deleteAllRows(db)
                db.accountDao().insertAll(withUids.accounts.map {
                    AccountEntity(
                        id = it.id, name = it.name.trim(), type = AccountType.valueOf(it.type),
                        openingBalance = it.openingBalance, includeInTotal = it.includeInTotal,
                        archived = it.archived, sortOrder = it.sortOrder, createdAt = it.createdAt,
                        updatedAt = it.updatedAt, uid = it.uid, isPrivate = it.isPrivate,
                    )
                })
                // Main groups before groups so the parent foreign key is satisfied.
                db.categoryDao().insertAll(withUids.categories.sortedBy { it.parentId != null }.map {
                    CategoryEntity(
                        id = it.id, name = it.name.trim(), kind = CategoryKind.valueOf(it.kind),
                        iconKey = it.iconKey, colorArgb = it.colorArgb, sortOrder = it.sortOrder,
                        active = it.active, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        parentId = it.parentId, uid = it.uid,
                    )
                })
                db.billDao().insertAll(bills.map {
                    BillEntity(
                        id = it.id, merchant = it.merchant, billDate = FloatingTime.toStored(LocalDateTime.parse(it.billDate)),
                        total = it.total, accountId = it.accountId, imageFile = it.imageFile, ocrText = it.ocrText,
                        status = BillStatus.valueOf(it.status), createdAt = it.createdAt, updatedAt = it.updatedAt,
                        uid = it.uid, isPrivate = it.isPrivate,
                    )
                })
                db.recurringDao().insertAll(withUids.recurringRules.map {
                    RecurringRuleEntity(
                        id = it.id, type = TransactionType.valueOf(it.type), amount = it.amount, accountId = it.accountId,
                        toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                        note = it.note, payee = it.payee, frequency = Frequency.valueOf(it.frequency), interval = it.interval,
                        startAt = FloatingTime.toStored(LocalDateTime.parse(it.startAt)), postedCount = it.postedCount,
                        active = it.active, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        uid = it.uid, isPrivate = it.isPrivate, amountChanges = it.amountChanges,
                    )
                })
                db.transactionDao().insertAll(withUids.transactions.map {
                    TransactionEntity(
                        id = it.id, type = TransactionType.valueOf(it.type), amount = it.amount, accountId = it.accountId,
                        toAccountId = it.toAccountId, categoryId = it.categoryId, isRefund = it.isRefund,
                        occurredAt = FloatingTime.toStored(LocalDateTime.parse(it.occurredAtIso)),
                        note = it.note, payee = it.payee, createdAt = it.createdAt, updatedAt = it.updatedAt,
                        billId = it.billId, recurringId = it.recurringId,
                        uid = it.uid, isPrivate = it.isPrivate,
                    )
                })
                db.budgetDao().insertAll(withUids.budgets.map {
                    BudgetEntity(it.id, it.month, it.categoryId, it.limitAmount, it.uid, it.isPrivate)
                })
                db.billDao().insertItems(withUids.billItems.map {
                    BillItemEntity(it.id, it.billId, it.position, it.description, it.amount, it.categoryId, it.included, it.uid)
                })
                db.itemRuleDao().insertAll(withUids.itemRules.map {
                    ItemRuleEntity(it.key, it.categoryId, it.useCount, it.updatedAt, it.uid)
                })
                db.monthLockDao().insertAll(withUids.monthLocks.map {
                    MonthLockEntity(it.month, it.closedAt, it.closedBy)
                })
                db.memberDao().insertAll(members)
                if (file.formatVersion >= 6) {
                    db.familyAllocationDao().insertPeople(withUids.familyPeople.map {
                        FamilyPersonEntity(it.id, it.name, it.active, it.createdAt, it.updatedAt, it.uid)
                    })
                    db.familyAllocationDao().insertTags(withUids.familyTags.map {
                        FamilyTagEntity(it.id, it.name, it.active, it.createdAt, it.updatedAt, it.uid)
                    })
                    db.familyAllocationDao().insertTagMembers(withUids.familyTagMembers.map {
                        FamilyTagMemberEntity(it.id, it.tagUid, it.personUid, it.createdAt, it.uid)
                    })
                    db.familyAllocationDao().insertTransactionMembers(withUids.transactionMembers.map {
                        TransactionMemberEntity(
                            it.id, it.transactionUid, it.personUid, it.shareAmount, it.isPrivate, it.uid,
                        )
                    })
                }
                if (file.formatVersion >= 7) {
                    val accountIdsByUid = withUids.accounts.associate { it.uid to it.id }
                    db.investmentValueDao().insertAll(withUids.investmentValues.map { value ->
                        InvestmentValueEntity(
                            id = value.id,
                            accountId = accountIdsByUid.getValue(value.accountUid),
                            valueMinor = value.valueMinor,
                            recordedAt = value.recordedAt,
                            isPrivate = value.isPrivate,
                            uid = value.uid,
                        )
                    })
                }
                if (file.formatVersion >= 5) {
                    db.entityVersionDao().insertAll(withUids.entityVersions.map { it.toEntity() })
                    if (familyAdmin != null) {
                        db.entityVersionDao().insertAll(
                            buildInitialVersions().map { restored ->
                                val previous = withUids.entityVersions
                                    .asSequence()
                                    .filter {
                                        it.entityType == restored.entityType && it.entityUid == restored.entityUid &&
                                            it.status == "APPROVED" && it.kind != "MERGED_REQUEST"
                                    }
                                    .maxOfOrNull { it.version } ?: 0
                                restored.copy(
                                    version = previous + 1,
                                    baseVersion = previous,
                                    kind = "RESTORE",
                                    authorId = familyAdmin.memberId,
                                    authorName = familyAdmin.name,
                                    createdAt = clock.millis(),
                                )
                            },
                        )
                    }
                } else {
                    db.entityVersionDao().insertAll(buildInitialVersions())
                }
            }
        }
        billImages.deleteAllExcept(bills.mapNotNullTo(HashSet()) { it.imageFile })
        settings.setCurrency(file.currencyCode)
        return ImportSummary(withUids.accounts.size, withUids.categories.size, withUids.transactions.size)
    }

    private fun BackupFile.withRestorableUids(): BackupFile {
        // One "used" set per list, so a blank or repeated uid within that list gets a fresh one.
        fun <T> List<T>.uniqueUids(uidOf: (T) -> String, copyUid: (T, String) -> T): List<T> {
            val used = HashSet<String>()
            return map { value ->
                val uid = uidOf(value).takeIf { it.isNotBlank() && used.add(it) } ?: UUID.randomUUID().toString().also(used::add)
                copyUid(value, uid)
            }
        }
        return copy(
            accounts = accounts.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            categories = categories.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            transactions = transactions.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            bills = bills.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            billItems = billItems.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            budgets = budgets.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            recurringRules = recurringRules.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            itemRules = itemRules.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            familyPeople = familyPeople.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            familyTags = familyTags.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            familyTagMembers = familyTagMembers.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            transactionMembers = transactionMembers.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
            investmentValues = investmentValues.uniqueUids({ it.uid }, { v, uid -> v.copy(uid = uid) }),
        )
    }

    private suspend fun buildInitialVersions(): List<EntityVersionEntity> {
        val accounts = db.accountDao().getAll()
        val categories = db.categoryDao().getAll()
        val bills = db.billDao().getAll()
        val recurring = db.recurringDao().getAll()
        val references = SnapshotReferences(
            accountUidsById = accounts.associate { it.id to it.uid },
            categoryUidsById = categories.associate { it.id to it.uid },
            billUidsById = bills.associate { it.id to it.uid },
            recurringRuleUidsById = recurring.associate { it.id to it.uid },
        )
        val versions = mutableListOf<EntityVersionEntity>()
        fun add(type: String, uid: String, json: String, private: Boolean = false) {
            versions += EntityVersionEntity(
                entityType = type,
                entityUid = uid,
                version = 1,
                status = "APPROVED",
                baseVersion = 0,
                kind = "ADD",
                deleted = false,
                snapshot = json,
                snapshotFormat = 1,
                authorId = "me",
                authorName = "You",
                createdAt = clock.millis(),
                isPrivate = private,
                eventUid = UUID.randomUUID().toString(),
            )
        }
        accounts.forEach { add("account", it.uid, snapshotJson.encodeToString(it.toSnapshot()), it.isPrivate) }
        categories.forEach { add("category", it.uid, snapshotJson.encodeToString(it.toSnapshot(references))) }
        db.transactionDao().getAll().forEach {
            add("transaction", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)), it.isPrivate)
        }
        bills.forEach { add("bill", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)), it.isPrivate) }
        db.billDao().getAllItems().forEach {
            add("bill_item", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)))
        }
        db.budgetDao().getAll().forEach {
            add("budget", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)), it.isPrivate)
        }
        recurring.forEach {
            add("recurring_rule", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)), it.isPrivate)
        }
        db.itemRuleDao().getAll().forEach {
            add("item_rule", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)))
        }
        db.monthLockDao().getAll().forEach {
            add("month_lock", it.month.toString(), snapshotJson.encodeToString(it.toSnapshot()))
        }
        db.memberDao().getAll().forEach {
            add("member", it.memberId, snapshotJson.encodeToString(it.toSnapshot()))
        }
        db.familyAllocationDao().getAllPeopleForBackup().forEach {
            add("family_person", it.uid, snapshotJson.encodeToString(it.toSnapshot()))
        }
        db.familyAllocationDao().getAllTagsForBackup().forEach {
            add("family_tag", it.uid, snapshotJson.encodeToString(it.toSnapshot()))
        }
        db.familyAllocationDao().getAllTagMembersForBackup().forEach {
            add("family_tag_member", it.uid, snapshotJson.encodeToString(it.toSnapshot()))
        }
        db.familyAllocationDao().getAllTransactionMembersForBackup().forEach {
            add("transaction_member", it.uid, snapshotJson.encodeToString(it.toSnapshot()), it.isPrivate)
        }
        db.investmentValueDao().getAllForBackup().forEach {
            add("investment_value", it.uid, snapshotJson.encodeToString(it.toSnapshot(references)), it.isPrivate)
        }
        return versions
    }
}

private fun EntityVersionEntity.toBackup() = BackupEntityVersion(
    id = id,
    entityType = entityType,
    entityUid = entityUid,
    version = version,
    status = status,
    baseVersion = baseVersion,
    kind = kind,
    deleted = deleted,
    snapshot = snapshot,
    snapshotFormat = snapshotFormat,
    authorId = authorId,
    authorName = authorName,
    createdAt = createdAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    decisionNote = decisionNote,
    bulkId = bulkId,
    isPrivate = isPrivate,
    eventUid = eventUid,
    archived = archived,
)

private fun BackupEntityVersion.toEntity() = EntityVersionEntity(
    id = id,
    entityType = entityType,
    entityUid = entityUid,
    version = version,
    status = status,
    baseVersion = baseVersion,
    kind = kind,
    deleted = deleted,
    snapshot = snapshot,
    snapshotFormat = snapshotFormat,
    authorId = authorId,
    authorName = authorName,
    createdAt = createdAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    decisionNote = decisionNote,
    bulkId = bulkId,
    isPrivate = isPrivate,
    eventUid = eventUid,
    archived = archived,
)

private fun MemberEntity.toBackup() = BackupMember(
    memberId = memberId,
    email = email,
    name = name,
    role = role,
    active = active,
    appVersionCode = appVersionCode,
    appVersionName = appVersionName,
    syncFormat = syncFormat,
    lastSeenAt = lastSeenAt,
)

private fun BackupMember.toEntity() = MemberEntity(
    memberId = memberId,
    email = email,
    name = name,
    role = role,
    active = active,
    appVersionCode = appVersionCode,
    appVersionName = appVersionName,
    syncFormat = syncFormat,
    lastSeenAt = lastSeenAt,
)
