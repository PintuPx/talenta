package com.homebudget.app.domain.review

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

data class ProposedChange(
    val field: String,
    val oldValue: String,
    val newValue: String,
    val fieldKey: String = field,
)

data class AdminReviewRequest(
    val id: Long,
    val entryId: String,
    val entityType: String,
    val entityUid: String,
    val entryLabel: String,
    val memberLabel: String,
    val baseVersion: Int,
    val latestVersion: Int,
    val changes: List<ProposedChange>,
    val currentDeleted: Boolean,
    val deletionRequested: Boolean,
)

@Serializable
data class SkippedBulkChangeNotice(
    val id: String,
    val memberName: String,
    val itemDescription: String,
    val previousCategoryId: Long,
    val previousCategoryName: String,
    val categoryId: Long,
    val categoryName: String,
    val skippedEntries: Int,
    val sourceBillId: Long? = null,
)

interface AdminReviewRepository {
    fun observePending(): Flow<List<AdminReviewRequest>>
    fun observeSkippedBulkChanges(): Flow<List<SkippedBulkChangeNotice>>
    suspend fun approve(entryId: String, resolutions: Map<String, String>)
    suspend fun reject(entryId: String, reason: String?)
    suspend fun applySkippedBulkChange(noticeId: String)
    suspend fun ignoreSkippedBulkChange(noticeId: String)
}
