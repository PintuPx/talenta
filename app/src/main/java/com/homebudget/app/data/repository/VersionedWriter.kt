package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.data.db.SyncStateEntity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.family.FamilyRole
import com.homebudget.app.domain.review.SkippedBulkChangeNotice
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val ADMIN_SKIPPED_BULK_NOTICE_KEY = "admin_skipped_bulk_changes"
internal const val ADMIN_SKIPPED_BULK_NOTICE_PREFIX = "admin_skipped_bulk_change:"

data class VersionedChange(
    val event: EntityVersionEntity,
    val month: Int? = null,
    val expectedBaseVersion: Int? = null,
    val sourceIsPrivate: Boolean? = null,
    val validate: suspend () -> Unit = {},
    val applyCurrent: suspend () -> Unit,
)

enum class WriteResult {
    APPLIED,
    PENDING_APPROVAL,
    BLOCKED_MONTH_CLOSED,
    BLOCKED_READ_ONLY,
    STALE_BASE,
}

fun WriteResult.requireAccepted() {
    val message = when (this) {
        WriteResult.APPLIED, WriteResult.PENDING_APPROVAL -> return
        WriteResult.BLOCKED_MONTH_CLOSED -> "This month is closed. Only the Admin can edit it."
        WriteResult.BLOCKED_READ_ONLY -> "Read-only family members cannot change shared entries."
        WriteResult.STALE_BASE -> "This entry changed while you were editing it. Review the latest version and try again."
    }
    throw IntegrityException(message)
}

data class BulkWriteResult(
    val applied: Int,
    val pendingApproval: Int,
    val skippedClosedMonths: Int,
    val bulkId: String = "",
)

@Singleton
// REVIEW: role, version and privacy decisions control shared financial data.
class VersionedWriter @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val identityProvider: IdentityProvider,
    private val spaceProvider: SpaceProvider,
    private val snapshotJson: SnapshotJsonCodec,
) {
    suspend fun change(
        entityType: String,
        entityUid: String,
        isPrivate: Boolean,
        kind: String,
        entity: Any,
        deleted: Boolean = false,
        month: Int? = null,
        expectedBaseVersion: Int? = null,
        sourceIsPrivate: Boolean? = null,
        snapshotOverride: String? = null,
        validate: suspend () -> Unit = {},
        applyCurrent: suspend () -> Unit,
    ): VersionedChange = VersionedChange(
        event = EntityVersionEntity(
            entityType = entityType,
            entityUid = entityUid,
            version = 0,
            status = "PENDING",
            baseVersion = 0,
            kind = kind,
            deleted = deleted,
            snapshot = snapshotOverride ?: snapshotJson.encode(entity),
            snapshotFormat = 1,
            authorId = "",
            authorName = "",
            createdAt = 0,
            isPrivate = isPrivate,
            eventUid = UUID.randomUUID().toString(),
        ),
        month = month,
        expectedBaseVersion = expectedBaseVersion,
        sourceIsPrivate = sourceIsPrivate,
        validate = validate,
        applyCurrent = applyCurrent,
    )

    suspend fun apply(change: VersionedChange): WriteResult = db.withTransaction {
        writeInTransaction(change, identityProvider.current())
    }

    suspend fun requireAdmin() {
        val identity = identityProvider.current()
        if (identity.appMode == AppMode.SHARED && identity.role != FamilyRole.ADMIN) {
            throw IntegrityException("Only the Family Admin can do this.")
        }
    }

    suspend fun currentIdentity(): WriteIdentity = identityProvider.current()

    suspend fun applyBulk(changes: List<VersionedChange>, bulkId: String = UUID.randomUUID().toString()): BulkWriteResult =
        db.withTransaction {
            val identity = identityProvider.current()
            val admin = identity.appMode != AppMode.SHARED || identity.role == FamilyRole.ADMIN
            if (!admin && changes.any { !it.event.isPrivate } && identity.role == FamilyRole.READ_ONLY) {
                throw IntegrityException("Read-only family members cannot change shared entries.")
            }
            val stale = changes.any { change ->
                val expected = change.expectedBaseVersion ?: return@any false
                val latest = db.entityVersionDao().latestApprovedForSpace(
                    change.event.entityType,
                    change.event.entityUid,
                    change.sourceIsPrivate ?: change.event.isPrivate,
                )?.version ?: 0
                expected != latest
            }
            if (stale) throw IntegrityException("Some entries changed while you were editing them. Review the current entries and try again.")
            var applied = 0
            var pending = 0
            var skippedClosed = 0
            for (change in changes) {
                val result = writeInTransaction(
                    change.copy(
                        event = change.event.copy(
                            kind = if (change.event.kind in setOf("MAKE_PRIVATE", "MAKE_SHARED", "UNDO")) {
                                change.event.kind
                            } else {
                                "BULK"
                            },
                            bulkId = bulkId,
                        ),
                    ),
                    identity,
                )
                when (result) {
                    WriteResult.APPLIED -> applied++
                    WriteResult.PENDING_APPROVAL -> pending++
                    WriteResult.BLOCKED_MONTH_CLOSED -> skippedClosed++
                    WriteResult.BLOCKED_READ_ONLY,
                    WriteResult.STALE_BASE -> throw IntegrityException("This bulk change is no longer valid. Review the current entries and try again.")
                }
            }
            if (skippedClosed > 0) {
                val existing = db.syncStateDao().get(ADMIN_SKIPPED_BULK_NOTICE_KEY)
                    ?.value?.toIntOrNull() ?: 0
                db.syncStateDao().upsert(
                    SyncStateEntity(ADMIN_SKIPPED_BULK_NOTICE_KEY, (existing + skippedClosed).toString()),
                )
            }
            BulkWriteResult(applied, pending, skippedClosed, bulkId)
        }

    suspend fun recordSkippedBulkNotice(notice: SkippedBulkChangeNotice) = db.withTransaction {
        if (notice.skippedEntries <= 0) return@withTransaction
        val existing = db.syncStateDao().get(ADMIN_SKIPPED_BULK_NOTICE_KEY)
            ?.value?.toIntOrNull() ?: 0
        db.syncStateDao().upsert(
            SyncStateEntity(ADMIN_SKIPPED_BULK_NOTICE_KEY, (existing + notice.skippedEntries).toString()),
        )
        db.syncStateDao().upsert(
            SyncStateEntity(
                ADMIN_SKIPPED_BULK_NOTICE_PREFIX + notice.id,
                Json.encodeToString(notice),
            ),
        )
    }

    suspend fun <T> applySystem(write: suspend () -> T): T = db.withTransaction { write() }

    private suspend fun writeInTransaction(change: VersionedChange, identity: WriteIdentity): WriteResult {
        change.validate()
        val event = change.event
        val spaceChange = event.kind == "MAKE_PRIVATE" || event.kind == "MAKE_SHARED"
        val privateCapable = event.entityType in PRIVATE_ENTITY_TYPES
        if (!spaceChange && privateCapable && event.isPrivate != (spaceProvider.current.value == Space.PRIVATE)) {
            throw IntegrityException("This entry is not in the currently selected space.")
        }
        val admin = identity.appMode != AppMode.SHARED ||
            identity.role == FamilyRole.ADMIN
        if (!event.isPrivate && event.entityType in ADMIN_ONLY_ENTITY_TYPES && !admin) {
            return WriteResult.BLOCKED_READ_ONLY
        }
        val sourceIsPrivate = change.sourceIsPrivate ?: event.isPrivate
        val sourceVersion = db.entityVersionDao()
            .latestApprovedForSpace(event.entityType, event.entityUid, sourceIsPrivate)
        val baseVersion = sourceVersion?.version ?: 0
        if (change.expectedBaseVersion != null && change.expectedBaseVersion != baseVersion) {
            return WriteResult.STALE_BASE
        }
        if (change.month != null && db.monthLockDao().getByMonth(change.month) != null && !admin) {
            return WriteResult.BLOCKED_MONTH_CLOSED
        }
        if (!event.isPrivate && !admin && identity.role == FamilyRole.READ_ONLY) {
            return WriteResult.BLOCKED_READ_ONLY
        }
        val eventUid = event.eventUid.ifBlank { UUID.randomUUID().toString() }
        if (db.entityVersionDao().hasEventUid(eventUid)) return WriteResult.APPLIED

        val makePrivate = event.kind == "MAKE_PRIVATE"
        val makeShared = event.kind == "MAKE_SHARED"
        val pending = !event.isPrivate && !makeShared && identity.appMode == AppMode.SHARED &&
            identity.role == FamilyRole.REVIEWER
        val now = clock.millis()
        if (pending) {
            db.entityVersionDao().insert(
                event.copy(
                    id = 0,
                    version = 0,
                    status = "PENDING",
                    baseVersion = baseVersion,
                    authorId = identity.memberId,
                    authorName = identity.displayName,
                    createdAt = now,
                    decidedBy = null,
                    decidedAt = null,
                    eventUid = eventUid,
                ),
            )
            return WriteResult.PENDING_APPROVAL
        }

        change.applyCurrent()
        // Prefer the stored row (it reflects DB defaults). When the row is not visible to the lookup
        // (e.g. a transaction_member whose transaction is not stored), fall back to the change's own
        // snapshot, which encodes exactly the entity applyCurrent() wrote.
        val snapshot = if (event.deleted) {
            event.snapshot
        } else {
            snapshotJson.current(event.entityType, event.entityUid) ?: event.snapshot
        }
        val targetPrivate = event.isPrivate
        val targetBase = db.entityVersionDao()
            .latestApprovedForSpace(event.entityType, event.entityUid, targetPrivate)?.version ?: 0
        val approved = event.copy(
            id = 0,
            version = targetBase + 1,
            status = "APPROVED",
            baseVersion = targetBase,
            snapshot = snapshot,
            authorId = identity.memberId,
            authorName = identity.displayName,
            createdAt = now,
            decidedBy = identity.memberId,
            decidedAt = now,
            isPrivate = targetPrivate,
            eventUid = eventUid,
        )
        if (makePrivate) {
            db.entityVersionDao().insert(
                approved.copy(
                    version = baseVersion + 1,
                    baseVersion = baseVersion,
                    deleted = true,
                    snapshot = tombstone(event.entityUid),
                    isPrivate = false,
                ),
            )
            val privateBase = db.entityVersionDao()
                .latestApprovedForSpace(event.entityType, event.entityUid, true)?.version ?: 0
            db.entityVersionDao().insert(
                approved.copy(
                    id = 0,
                    version = privateBase + 1,
                    status = "APPROVED",
                    baseVersion = privateBase,
                    kind = "MAKE_PRIVATE",
                    deleted = false,
                    snapshot = snapshot,
                    isPrivate = true,
                    eventUid = UUID.randomUUID().toString(),
                ),
            )
        } else if (makeShared) {
            db.entityVersionDao().insert(approved.copy(isPrivate = false))
            db.entityVersionDao().insert(
                approved.copy(
                    id = 0,
                    version = baseVersion + 1,
                    status = "APPROVED",
                    baseVersion = baseVersion,
                    kind = "MAKE_SHARED",
                    deleted = true,
                    snapshot = tombstone(event.entityUid),
                    isPrivate = true,
                    eventUid = UUID.randomUUID().toString(),
                ),
            )
        } else {
            db.entityVersionDao().insert(approved)
        }
        return WriteResult.APPLIED
    }

    private fun tombstone(uid: String): String = JsonObject(
        mapOf("uid" to JsonPrimitive(uid), "tombstone" to JsonPrimitive(true)),
    ).toString()

    private companion object {
        val PRIVATE_ENTITY_TYPES = setOf(
            "account", "transaction", "bill", "budget", "recurring_rule", "transaction_member", "investment_value",
        )
        val ADMIN_ONLY_ENTITY_TYPES = setOf("category", "budget", "month_lock", "member")
    }
}
