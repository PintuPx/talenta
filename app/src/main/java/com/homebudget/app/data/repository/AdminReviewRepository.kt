package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.bill.BillRepository
import com.homebudget.app.data.db.AccountSnapshot
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.BillItemSnapshot
import com.homebudget.app.data.db.BillSnapshot
import com.homebudget.app.data.db.BudgetSnapshot
import com.homebudget.app.data.db.CategorySnapshot
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.data.db.FamilyPersonSnapshot
import com.homebudget.app.data.db.FamilyTagMemberSnapshot
import com.homebudget.app.data.db.FamilyTagSnapshot
import com.homebudget.app.data.db.ItemRuleSnapshot
import com.homebudget.app.data.db.InvestmentValueSnapshot
import com.homebudget.app.data.db.LocalEntityReferences
import com.homebudget.app.data.db.MemberSnapshot
import com.homebudget.app.data.db.MonthLockSnapshot
import com.homebudget.app.data.db.RecurringRuleSnapshot
import com.homebudget.app.data.db.TransactionSnapshot
import com.homebudget.app.data.db.TransactionMemberSnapshot
import com.homebudget.app.data.db.SyncStateEntity
import com.homebudget.app.data.db.toEntity
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.review.AdminReviewRepository as AdminReviewRepositoryContract
import com.homebudget.app.domain.review.AdminReviewRequest
import com.homebudget.app.domain.review.ProposedChange
import com.homebudget.app.domain.review.SkippedBulkChangeNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAdminReviewRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val writer: VersionedWriter,
    private val bills: BillRepository,
) : AdminReviewRepositoryContract {
    private val versions = db.entityVersionDao()
    private val snapshotCodec = Json { ignoreUnknownKeys = true }

    override fun observePending(): Flow<List<AdminReviewRequest>> =
        combine(versions.observePendingShared(), versions.observeAll()) { pending, all ->
            val uidLabels = loadEntityLabels()
            pending.map { request ->
                val latest = all.filter {
                    !it.isPrivate && it.entityType == request.entityType && it.entityUid == request.entityUid &&
                        it.status == "APPROVED"
                }.maxByOrNull { it.version }
                val base = all.firstOrNull {
                    !it.isPrivate && it.entityType == request.entityType && it.entityUid == request.entityUid &&
                        it.version == request.baseVersion && it.status == "APPROVED"
                }
                val displayBase = if (latest?.version != request.baseVersion) latest?.snapshot ?: base?.snapshot else base?.snapshot
                val changes = changes(displayBase, request.snapshot, uidLabels)
                val displayedChanges = changes.map {
                    ProposedChange(it.field.asFieldLabel(), it.oldValue, it.newValue, it.field)
                } + if (request.kind == "MAKE_PRIVATE") {
                    listOf(ProposedChange("Space", "Shared", "Private", "isPrivate"))
                } else {
                    emptyList()
                }
                AdminReviewRequest(
                    id = request.id,
                    entryId = entryKey(request.entityType, request.entityUid),
                    entityType = request.entityType,
                    entityUid = request.entityUid,
                    entryLabel = label(request, uidLabels),
                    memberLabel = request.authorName,
                    baseVersion = request.baseVersion,
                    latestVersion = latest?.version ?: 0,
                    changes = displayedChanges,
                    currentDeleted = latest?.deleted == true,
                    deletionRequested = request.deleted,
                )
            }
        }

    override fun observeSkippedBulkChanges(): Flow<List<SkippedBulkChangeNotice>> =
        db.syncStateDao().observeAll().map { rows ->
            rows.filter { it.key.startsWith(ADMIN_SKIPPED_BULK_NOTICE_PREFIX) }
                .map { snapshotCodec.decodeFromString<SkippedBulkChangeNotice>(it.value) }
                .sortedBy { it.id }
        }

    override suspend fun applySkippedBulkChange(noticeId: String) {
        writer.requireAdmin()
        val key = ADMIN_SKIPPED_BULK_NOTICE_PREFIX + noticeId
        val notice = db.syncStateDao().get(key)
            ?.value?.let { snapshotCodec.decodeFromString<SkippedBulkChangeNotice>(it) }
            ?: throw IntegrityException("This skipped change is no longer available.")
        bills.changePastCategories(
            description = notice.itemDescription,
            categoryId = notice.categoryId,
            previousCategoryId = notice.previousCategoryId,
            requireSharedSpace = true,
            excludeBillId = notice.sourceBillId,
        )
        ignoreSkippedBulkChange(noticeId)
    }

    override suspend fun ignoreSkippedBulkChange(noticeId: String) = db.withTransaction {
        writer.requireAdmin()
        val key = ADMIN_SKIPPED_BULK_NOTICE_PREFIX + noticeId
        val notice = db.syncStateDao().get(key)
            ?.value?.let { snapshotCodec.decodeFromString<SkippedBulkChangeNotice>(it) }
            ?: return@withTransaction
        db.syncStateDao().delete(key)
        val countKey = ADMIN_SKIPPED_BULK_NOTICE_KEY
        val count = db.syncStateDao().get(countKey)?.value?.toIntOrNull() ?: 0
        val remaining = (count - notice.skippedEntries).coerceAtLeast(0)
        if (remaining == 0) db.syncStateDao().delete(countKey)
        else db.syncStateDao().upsert(SyncStateEntity(countKey, remaining.toString()))
    }

    // REVIEW: merge and current-row application are data-critical and must be reviewed.
    override suspend fun approve(entryId: String, resolutions: Map<String, String>) {
        val (entityType, entityUid) = parseEntryKey(entryId)
        db.withTransaction {
            writer.requireAdmin()
            val identity = writer.currentIdentity()
            val pending = versions.getPendingForEntity(entityType, entityUid)
            if (pending.isEmpty()) return@withTransaction
            val makePrivate = pending.all { it.kind == "MAKE_PRIVATE" }
            if (pending.any { (it.kind == "MAKE_PRIVATE") != makePrivate }) {
                throw IntegrityException("Review these space changes separately.")
            }
            val latest = versions.latestApproved(entityType, entityUid)
            val pendingBases = pending.associateWith { request ->
                if (request.baseVersion == 0) JsonObject(emptyMap())
                else versions.approvedVersion(entityType, entityUid, request.baseVersion)
                    ?.snapshot?.let(::parseObject)
                    ?: throw IntegrityException("The base version for this request is unavailable")
            }
            val currentObject = latest?.snapshot?.let(::parseObject)
                ?: pending.first().snapshot.let(::parseObject)
            val nextVersion = versions.maxApprovedVersion(entityType, entityUid) + 1
            val proposedByField = pending.flatMap { request ->
                changes(pendingBases.getValue(request), parseObject(request.snapshot)).map { change ->
                    request to change
                }
            }.groupBy({ it.second.field }, { it })
            val merged = currentObject.toMutableMap()
            val chosenValues = mutableMapOf<String, JsonElement>()
            proposedByField.forEach { (field, options) ->
                val distinctOptions = options.distinctBy { it.second.newValue }
                val resolution = resolutions[field]
                val selected = if (resolution == null && distinctOptions.size == 1) {
                    distinctOptions.first().second.newElement
                } else {
                    distinctOptions.firstOrNull { it.second.newValue == resolution }?.second?.newElement
                        ?: customValue(
                            resolution ?: throw IntegrityException("Choose a value for $field"),
                            currentObject[field] ?: distinctOptions.first().second.newElement,
                        )
                }
                merged[field] = selected
                chosenValues[field] = selected
            }
            if (makePrivate) merged["isPrivate"] = JsonPrimitive(true)
            val onlyDeletes = pending.all { it.deleted }
            val decisionTime = clock.millis()
            var approvedSnapshot = JsonObject(merged).toString()
            writer.applySystem {
                if (onlyDeletes) {
                    applySnapshot(entityType, entityUid, approvedSnapshot, deleted = true)
                } else {
                    approvedSnapshot = applySnapshot(
                        entityType,
                        entityUid,
                        approvedSnapshot,
                        deleted = false,
                        allowPrivate = makePrivate,
                    )
                }
                pending.forEach { request ->
                    val requestChanges = changes(pendingBases.getValue(request), parseObject(request.snapshot))
                    val accepted = !request.deleted && requestChanges.all { change ->
                        chosenValues[change.field] == change.newElement
                    } || onlyDeletes && request.deleted
                    versions.update(
                        request.copy(
                            status = if (accepted) "APPROVED" else "REJECTED",
                            kind = if (accepted) "MERGED_REQUEST" else request.kind,
                            decidedBy = identity.memberId,
                            decidedAt = decisionTime,
                            decisionNote = if (accepted) "Merged by Admin" else "Conflicting change was not selected",
                        )
                    )
                }
                if (makePrivate) {
                    versions.insert(
                        EntityVersionEntity(
                            entityType = entityType,
                            entityUid = entityUid,
                            version = nextVersion,
                            status = "APPROVED",
                            baseVersion = latest?.version ?: 0,
                            kind = "MAKE_PRIVATE",
                            deleted = true,
                            snapshot = tombstone(entityUid),
                            snapshotFormat = 1,
                            authorId = identity.memberId,
                            authorName = identity.displayName,
                            createdAt = decisionTime,
                            decidedBy = identity.memberId,
                            decidedAt = decisionTime,
                            decisionNote = "Made private",
                            isPrivate = false,
                            eventUid = UUID.randomUUID().toString(),
                        ),
                    )
                    val privateBase = versions.latestApprovedForSpace(entityType, entityUid, true)?.version ?: 0
                    versions.insert(
                        EntityVersionEntity(
                            entityType = entityType,
                            entityUid = entityUid,
                            version = privateBase + 1,
                            status = "APPROVED",
                            baseVersion = privateBase,
                            kind = "MAKE_PRIVATE",
                            deleted = false,
                            snapshot = approvedSnapshot,
                            snapshotFormat = 1,
                            authorId = identity.memberId,
                            authorName = identity.displayName,
                            createdAt = decisionTime,
                            decidedBy = identity.memberId,
                            decidedAt = decisionTime,
                            decisionNote = "Made private",
                            isPrivate = true,
                            eventUid = UUID.randomUUID().toString(),
                        ),
                    )
                } else {
                    versions.insert(
                        EntityVersionEntity(
                            entityType = entityType,
                            entityUid = entityUid,
                            version = nextVersion,
                            status = "APPROVED",
                            baseVersion = latest?.version ?: 0,
                            kind = when {
                                onlyDeletes -> "DELETE"
                                latest == null -> "ADD"
                                latest.deleted -> "RESTORE"
                                else -> "EDIT"
                            },
                            deleted = onlyDeletes,
                            snapshot = approvedSnapshot,
                            snapshotFormat = 1,
                            authorId = identity.memberId,
                            authorName = identity.displayName,
                            createdAt = decisionTime,
                            decidedBy = identity.memberId,
                            decidedAt = decisionTime,
                            decisionNote = "Reviewed and merged",
                            isPrivate = false,
                            eventUid = UUID.randomUUID().toString(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun reject(entryId: String, reason: String?) {
        val (entityType, entityUid) = parseEntryKey(entryId)
        db.withTransaction {
            writer.requireAdmin()
            val identity = writer.currentIdentity()
            val now = clock.millis()
            writer.applySystem {
                versions.getPendingForEntity(entityType, entityUid).forEach { request ->
                    versions.update(
                        request.copy(
                            status = "REJECTED",
                            decidedBy = identity.memberId,
                            decidedAt = now,
                            decisionNote = reason?.take(300),
                        )
                    )
                }
            }
        }
    }

    private suspend fun applySnapshot(
        entityType: String,
        entityUid: String,
        snapshotJson: String,
        deleted: Boolean,
        allowPrivate: Boolean = false,
    ): String {
        val accounts = db.accountDao().getAll()
        val categories = db.categoryDao().getAll()
        val bills = db.billDao().getAll()
        val recurring = db.recurringDao().getAll()
        val localRefs = LocalEntityReferences(
            accountIdsByUid = accounts.associate { it.uid to it.id },
            categoryIdsByUid = categories.associate { it.uid to it.id },
            billIdsByUid = bills.associate { it.uid to it.id },
            recurringRuleIdsByUid = recurring.associate { it.uid to it.id },
        )
        when (entityType) {
            "account" -> {
                val snapshot = snapshotCodec.decodeFromString<AccountSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = accounts.firstOrNull { it.uid == entityUid }
                old?.let { requireSharedRow(it.isPrivate) }
                val oldInvestment = old?.takeIf { it.type == AccountType.INVESTMENT }
                if (
                    allowPrivate &&
                    snapshot.isPrivate &&
                    oldInvestment != null &&
                    db.investmentValueDao().getAll().any { it.accountId == oldInvestment.id && !it.isPrivate }
                ) {
                    throw IntegrityException("Make the investment's saved values private before making its account private.")
                }
                if (old != null && old.type.name != snapshot.type) {
                    if (old.type == AccountType.INVESTMENT &&
                        snapshot.type != AccountType.INVESTMENT.name &&
                        db.investmentValueDao().getAll().any { it.accountId == old.id }
                    ) {
                        throw IntegrityException("This investment has saved values and must remain an investment account.")
                    }
                    if (old.type != AccountType.INVESTMENT &&
                        snapshot.type == AccountType.INVESTMENT.name &&
                        db.transactionDao().countNonTransferForAccountInSpace(old.id, old.isPrivate) > 0
                    ) {
                        throw IntegrityException("An account with income or expense history cannot become an investment account.")
                    }
                }
                if (deleted) old?.let { db.accountDao().delete(it.id) }
                else if (old == null) {
                    val id = db.accountDao().insert(snapshot.toEntity())
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.accountDao().update(snapshot.toEntity(old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "category" -> {
                val snapshot = snapshotCodec.decodeFromString<CategorySnapshot>(snapshotJson)
                requireEntityUid(snapshot.uid, entityUid)
                val old = categories.firstOrNull { it.uid == entityUid }
                if (deleted) old?.let { db.categoryDao().delete(it.id) }
                else if (old == null) {
                    val id = db.categoryDao().insert(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.categoryDao().update(snapshot.toEntity(localRefs, old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "transaction" -> {
                val snapshot = snapshotCodec.decodeFromString<TransactionSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = db.transactionDao().getAll().firstOrNull { it.uid == entityUid }
                old?.let { requireSharedRow(it.isPrivate) }
                if (deleted) old?.let { db.transactionDao().delete(it.id) }
                else if (old == null) {
                    val id = db.transactionDao().insert(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.transactionDao().update(snapshot.toEntity(localRefs, old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "bill" -> {
                val snapshot = snapshotCodec.decodeFromString<BillSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = bills.firstOrNull { it.uid == entityUid }
                old?.let { requireSharedRow(it.isPrivate) }
                if (deleted) old?.let { db.billDao().delete(it.id) }
                else if (old == null) {
                    val id = db.billDao().insert(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.billDao().update(snapshot.toEntity(localRefs, old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "bill_item" -> {
                val snapshot = snapshotCodec.decodeFromString<BillItemSnapshot>(snapshotJson)
                requireEntityUid(snapshot.uid, entityUid)
                val old = db.billDao().getAllItems().firstOrNull { it.uid == entityUid }
                old?.let { item ->
                    bills.firstOrNull { it.id == item.billId }?.let { requireSharedRow(it.isPrivate) }
                }
                if (!deleted) {
                    val targetBillId = localRefs.billIdsByUid[snapshot.billUid]
                        ?: throw IntegrityException("The bill for this line is unavailable")
                    bills.firstOrNull { it.id == targetBillId }?.let { requireSharedRow(it.isPrivate) }
                }
                if (deleted) old?.let { db.billDao().deleteItem(it.id) }
                else if (old == null) {
                    val id = db.billDao().insertItem(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.billDao().updateItem(snapshot.toEntity(localRefs, old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "budget" -> {
                val snapshot = snapshotCodec.decodeFromString<BudgetSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = db.budgetDao().getAll().firstOrNull { it.uid == entityUid }
                old?.let { requireSharedRow(it.isPrivate) }
                if (deleted) old?.let { db.budgetDao().delete(it.id) }
                else if (old == null) {
                    val id = db.budgetDao().insert(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.budgetDao().update(snapshot.toEntity(localRefs, old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "recurring_rule" -> {
                val snapshot = snapshotCodec.decodeFromString<RecurringRuleSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = recurring.firstOrNull { it.uid == entityUid }
                old?.let { requireSharedRow(it.isPrivate) }
                if (deleted) old?.let { db.recurringDao().delete(it.id) }
                else if (old == null) {
                    val id = db.recurringDao().insert(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    db.recurringDao().update(snapshot.toEntity(localRefs, old.id))
                    return snapshotCodec.encodeToString(snapshot.copy(id = old.id))
                }
            }
            "item_rule" -> {
                val snapshot = snapshotCodec.decodeFromString<ItemRuleSnapshot>(snapshotJson)
                requireEntityUid(snapshot.uid, entityUid)
                val old = db.itemRuleDao().getAll().firstOrNull { it.uid == entityUid }
                if (deleted) old?.let { db.itemRuleDao().delete(it.key) }
                else {
                    old?.takeIf { it.key != snapshot.key }?.let { db.itemRuleDao().delete(it.key) }
                    db.itemRuleDao().upsert(snapshot.toEntity(localRefs))
                }
            }
            "month_lock" -> {
                val snapshot = snapshotCodec.decodeFromString<MonthLockSnapshot>(snapshotJson)
                requireEntityUid(snapshot.entityUid, entityUid)
                val old = db.monthLockDao().getByMonth(snapshot.month)
                if (deleted) db.monthLockDao().delete(snapshot.month)
                else if (old == null) db.monthLockDao().insert(snapshot.toEntity())
                else db.monthLockDao().update(snapshot.toEntity())
            }
            "member" -> {
                val snapshot = snapshotCodec.decodeFromString<MemberSnapshot>(snapshotJson)
                requireEntityUid(snapshot.entityUid, entityUid)
                val old = db.memberDao().getById(snapshot.memberId)
                if (deleted) old?.let { db.memberDao().delete(it.memberId) }
                else if (old == null) db.memberDao().insert(snapshot.toEntity())
                else db.memberDao().update(snapshot.toEntity())
            }
            "family_person" -> {
                val snapshot = snapshotCodec.decodeFromString<FamilyPersonSnapshot>(snapshotJson)
                requireEntityUid(snapshot.uid, entityUid)
                val old = db.familyAllocationDao().getPerson(entityUid)
                if (deleted) old?.let {
                    db.familyAllocationDao().updatePerson(it.copy(active = false, updatedAt = clock.millis()))
                } else if (old == null) {
                    db.familyAllocationDao().insertPerson(snapshot.toEntity())
                } else {
                    db.familyAllocationDao().updatePerson(snapshot.toEntity(old.id))
                }
            }
            "family_tag" -> {
                val snapshot = snapshotCodec.decodeFromString<FamilyTagSnapshot>(snapshotJson)
                requireEntityUid(snapshot.uid, entityUid)
                val old = db.familyAllocationDao().getTag(entityUid)
                if (deleted) old?.let {
                    db.familyAllocationDao().updateTag(it.copy(active = false, updatedAt = clock.millis()))
                } else if (old == null) {
                    db.familyAllocationDao().insertTag(snapshot.toEntity())
                } else {
                    db.familyAllocationDao().updateTag(snapshot.toEntity(old.id))
                }
            }
            "family_tag_member" -> {
                val snapshot = snapshotCodec.decodeFromString<FamilyTagMemberSnapshot>(snapshotJson)
                requireEntityUid(snapshot.uid, entityUid)
                val old = db.familyAllocationDao().getTagMember(entityUid)
                if (deleted) old?.let { db.familyAllocationDao().deleteTagMember(it.id) }
                else if (old == null) {
                    db.familyAllocationDao().insertTagMember(snapshot.toEntity())
                } else {
                    db.familyAllocationDao().updateTagMember(snapshot.toEntity(old.id))
                }
            }
            "transaction_member" -> {
                val snapshot = snapshotCodec.decodeFromString<TransactionMemberSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = db.familyAllocationDao().getTransactionMember(entityUid)
                old?.let { requireSharedRow(it.isPrivate) }
                if (deleted) old?.let { db.familyAllocationDao().deleteTransactionMember(it.id) }
                else if (old == null) {
                    db.familyAllocationDao().insertTransactionMember(snapshot.toEntity())
                } else {
                    db.familyAllocationDao().updateTransactionMember(snapshot.toEntity(old.id))
                }
            }
            "investment_value" -> {
                val snapshot = snapshotCodec.decodeFromString<InvestmentValueSnapshot>(snapshotJson)
                requireSpaceChangeUid(snapshot.uid, entityUid, snapshot.isPrivate, allowPrivate)
                val old = db.investmentValueDao().getAll().firstOrNull { it.uid == entityUid }
                old?.let { requireSharedRow(it.isPrivate) }
                if (deleted) old?.let { db.investmentValueDao().delete(it.id) }
                else if (old == null) {
                    val id = db.investmentValueDao().insert(snapshot.toEntity(localRefs))
                    return snapshotCodec.encodeToString(snapshot.copy(id = id))
                } else {
                    throw IntegrityException("Investment value records cannot be edited.")
                }
            }
            else -> throw IntegrityException("Unsupported review entity type: $entityType")
        }
        return snapshotJson
    }

    private suspend fun loadEntityLabels(): Map<String, String> {
        val labels = mutableMapOf<String, String>()
        db.accountDao().getAll().forEach { labels[it.uid] = it.name }
        db.categoryDao().getAll().forEach { labels[it.uid] = it.name }
        db.transactionDao().getAll().forEach {
            labels[it.uid] = it.payee.ifBlank { it.note.ifBlank { "Transaction ${it.id}" } }
        }
        db.billDao().getAll().forEach { labels[it.uid] = it.merchant.ifBlank { "Bill ${it.id}" } }
        db.billDao().getAllItems().forEach { labels[it.uid] = it.description }
        db.budgetDao().getAll().forEach { labels[it.uid] = "Budget ${it.month}" }
        db.recurringDao().getAll().forEach {
            labels[it.uid] = it.payee.ifBlank { it.note.ifBlank { "Repeating transaction ${it.id}" } }
        }
        db.itemRuleDao().getAll().forEach { labels[it.uid] = it.key }
        db.memberDao().getAll().forEach { labels[it.memberId] = it.name }
        db.familyAllocationDao().getAllPeopleForBackup().forEach { labels[it.uid] = it.name }
        db.familyAllocationDao().getAllTagsForBackup().forEach { labels[it.uid] = it.name }
        return labels
    }

    private fun changes(
        oldJson: String?,
        newJson: String,
        uidLabels: Map<String, String> = emptyMap(),
    ): List<RawChange> =
        changes(oldJson?.let(::parseObject) ?: JsonObject(emptyMap()), parseObject(newJson), uidLabels)

    private fun changes(
        old: JsonObject,
        new: JsonObject,
        uidLabels: Map<String, String> = emptyMap(),
    ): List<RawChange> =
        (old.keys + new.keys).asSequence()
            .filterNot(::isInternalField)
            .distinct()
            .mapNotNull { field ->
                val before = old[field] ?: JsonNull
                val after = new[field] ?: JsonNull
                if (before == after) null else RawChange(
                    field,
                    before.display(field, uidLabels),
                    after.display(field, uidLabels),
                    after,
                )
            }
            .toList()

    private fun label(version: EntityVersionEntity, uidLabels: Map<String, String>): String {
        val snapshot = parseObject(version.snapshot)
        val value = listOf("name", "merchant", "payee", "description", "note", "key", "month", "memberId", "accountUid")
            .firstNotNullOfOrNull {
                snapshot[it]?.display(it, uidLabels)?.takeIf(String::isNotBlank)
            }
        return value ?: "${version.entityType.replace('_', ' ')} ${version.entityUid.take(8)}"
    }

    private fun isInternalField(field: String): Boolean =
        field in setOf(
            "id", "uid", "createdAt", "created_at", "updatedAt", "updated_at", "isPrivate", "is_private",
            "imageFile", "image_file", "ocrText", "ocr_text", "sortOrder", "sort_order",
        ) || field.endsWith("_uid") || field.endsWith("_id")

    private fun String.asFieldLabel(): String = when (this) {
        "accountUid" -> "Account"
        "toAccountUid" -> "Destination account"
        "categoryUid" -> "Category"
        "parentUid" -> "Parent category"
        "billUid" -> "Bill"
        "recurringUid" -> "Repeating transaction"
        "memberId" -> "Member"
        else -> replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    private fun customValue(value: String, typeFromCurrent: JsonElement?): JsonElement =
        if (typeFromCurrent is JsonPrimitive && typeFromCurrent.isString) JsonPrimitive(value)
        else Json.parseToJsonElement(value)

    private fun parseObject(json: String): JsonObject =
        Json.parseToJsonElement(json) as? JsonObject
            ?: throw IntegrityException("The saved review snapshot is not an object")

    private fun requireSpaceChangeUid(
        snapshotUid: String,
        requestedUid: String,
        isPrivate: Boolean,
        allowPrivate: Boolean,
    ) {
        requireEntityUid(snapshotUid, requestedUid)
        if (isPrivate && !allowPrivate) requireSharedRow(true)
    }

    private fun tombstone(uid: String): String = JsonObject(
        mapOf("uid" to JsonPrimitive(uid), "tombstone" to JsonPrimitive(true)),
    ).toString()

    private fun requireSharedRow(isPrivate: Boolean) {
        if (isPrivate) throw IntegrityException("A private-space row cannot be changed through shared review")
    }

    private fun requireEntityUid(snapshotUid: String, requestedUid: String) {
        if (snapshotUid != requestedUid) throw IntegrityException("The snapshot does not match the requested entry")
    }

    private fun JsonElement.display(field: String, uidLabels: Map<String, String>): String = when (this) {
        JsonNull -> "—"
        is JsonPrimitive -> if (field.endsWith("Uid") || field == "memberId") {
            uidLabels[content] ?: "Linked entry ${content.take(8)}"
        } else {
            content
        }
        is JsonObject -> "[details]"
        else -> toString()
    }

    private fun entryKey(type: String, uid: String) = "$type:$uid"

    private fun parseEntryKey(entryId: String): Pair<String, String> {
        val separator = entryId.indexOf(':')
        if (separator <= 0 || separator == entryId.lastIndex) throw IntegrityException("Invalid review entry")
        return entryId.substring(0, separator) to entryId.substring(separator + 1)
    }

    private data class RawChange(
        val field: String,
        val oldValue: String,
        val newValue: String,
        val newElement: JsonElement,
    )
}
