package com.homebudget.app.domain.history

import kotlinx.coroutines.flow.Flow

enum class HistoryMarker { NORMAL, DELETED, RESTORED }

data class HistoryChange(val field: String, val before: String, val after: String)

data class HistoryVersion(
    val id: Long,
    val version: Int,
    val member: String,
    val whenLabel: String,
    val changes: List<HistoryChange>,
    val marker: HistoryMarker,
    val status: String,
)

data class EntryHistory(
    val entityType: String,
    val entityUid: String,
    val label: String,
    val versions: List<HistoryVersion>,
)

data class AdminHistoryRow(
    val id: Long,
    val entityType: String,
    val entityUid: String,
    val entryLabel: String,
    val member: String,
    val whenLabel: String,
    val dateKey: String,
    val version: Int,
    val changes: List<HistoryChange>,
    val marker: HistoryMarker,
    val status: String,
)

interface HistoryRepository {
    fun observeEntity(entityType: String, entityUid: String): Flow<EntryHistory?>

    fun observeAll(
        member: String? = null,
        entityType: String? = null,
        startAt: Long? = null,
        endAt: Long? = null,
    ): Flow<List<AdminHistoryRow>>
}
