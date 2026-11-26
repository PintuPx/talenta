package com.homebudget.app.data.repository

import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.EntityVersionEntity
import com.homebudget.app.domain.history.AdminHistoryRow
import com.homebudget.app.domain.history.EntryHistory
import com.homebudget.app.domain.history.HistoryChange
import com.homebudget.app.domain.history.HistoryMarker
import com.homebudget.app.domain.history.HistoryRepository as HistoryRepositoryContract
import com.homebudget.app.domain.history.HistoryVersion
import com.homebudget.app.data.settings.CurrencyProvider
import com.homebudget.app.data.settings.DefaultCurrencyProvider
import com.homebudget.app.data.settings.SharedSpaceProvider
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.money.MoneyFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val spaceProvider: SpaceProvider = SharedSpaceProvider,
    private val currencyProvider: CurrencyProvider = DefaultCurrencyProvider,
) : HistoryRepositoryContract {
    private val dao = db.entityVersionDao()

    /** Display formatting for diffs, rebuilt when the selected currency changes. Stored snapshots are untouched. */
    private val fieldFormatter: Flow<HistoryFieldFormatter> =
        currencyProvider.currencyCode.map { HistoryFieldFormatter(MoneyFormatter(it)) }

    /** Only versions from the active space, so shared view never shows private-era versions. */
    override fun observeEntity(entityType: String, entityUid: String): Flow<EntryHistory?> =
        spaceProvider.current.flatMapLatest { space ->
            dao.observeForEntityInSpace(entityType, entityUid, space == Space.PRIVATE)
        }.combine(fieldFormatter) { versions, formatter ->
            if (versions.isEmpty()) return@combine null
            EntryHistory(
                entityType = entityType,
                entityUid = entityUid,
                label = formatter.entityLabel(versions.first()),
                versions = versions.mapIndexed { index, version ->
                    formatter.historyVersion(version, versions.getOrNull(index + 1))
                },
            )
        }

    override fun observeAll(
        member: String?,
        entityType: String?,
        startAt: Long?,
        endAt: Long?,
    ): Flow<List<AdminHistoryRow>> =
        dao.observeShared().combine(fieldFormatter) { rows, formatter ->
            val filtered = rows.filter { version ->
                (member.isNullOrBlank() || version.authorName.contains(member, ignoreCase = true)) &&
                    (entityType.isNullOrBlank() || version.entityType.equals(entityType, ignoreCase = true)) &&
                    (startAt == null || version.createdAt >= startAt) &&
                    (endAt == null || version.createdAt < endAt)
            }
            val versionsByEntity = rows.groupBy { it.entityType to it.entityUid }
            filtered.map { version ->
                val entityVersions = versionsByEntity.getValue(version.entityType to version.entityUid)
                val nextOlder = entityVersions.dropWhile { it.id != version.id }.drop(1).firstOrNull()
                AdminHistoryRow(
                    id = version.id,
                    entityType = version.entityType,
                    entityUid = version.entityUid,
                    entryLabel = formatter.entityLabel(version),
                    member = version.authorName,
                    whenLabel = dateTime(version.createdAt),
                    dateKey = dateKey(version.createdAt),
                    version = version.version,
                    changes = formatter.diff(version.entityType, version.snapshot, nextOlder?.snapshot),
                    marker = marker(version),
                    status = version.status,
                )
            }
        }

    private fun HistoryFieldFormatter.historyVersion(version: EntityVersionEntity, older: EntityVersionEntity?) =
        HistoryVersion(
            id = version.id,
            version = version.version,
            member = version.authorName,
            whenLabel = dateTime(version.createdAt),
            changes = diff(version.entityType, version.snapshot, older?.snapshot),
            marker = marker(version),
            status = version.status,
        )

    private fun HistoryFieldFormatter.entityLabel(version: EntityVersionEntity): String {
        val snapshot = Json.parseToJsonElement(version.snapshot).jsonObjectOrEmpty()
        val label = listOf("name", "merchant", "payee", "note", "key", "memberName", "memberId", "month")
            .firstNotNullOfOrNull { key ->
                value(version.entityType, key, snapshot).takeIf { it.isNotBlank() && it != "—" }
            }
        return label ?: "${version.entityType.replace('_', ' ')} ${version.entityUid.take(8)}"
    }

    /** Field changes between two stored snapshots, as display text (formatting never touches the stored JSON). */
    private fun HistoryFieldFormatter.diff(entityType: String, newerJson: String, olderJson: String?): List<HistoryChange> {
        val newer = Json.parseToJsonElement(newerJson).jsonObjectOrEmpty()
        val older = olderJson?.let { Json.parseToJsonElement(it).jsonObjectOrEmpty() } ?: JsonObject(emptyMap())
        return (newer.keys + older.keys)
            .asSequence()
            .filterNot(::isHidden)
            .mapNotNull { key ->
                val before = value(entityType, key, older)
                val after = value(entityType, key, newer)
                if (before == after) null else HistoryChange(label(key), before, after)
            }
            .sortedBy { it.field } // alphabetical by the label the user reads
            .toList()
    }

    private fun marker(version: EntityVersionEntity): HistoryMarker = when {
        version.deleted -> HistoryMarker.DELETED
        version.kind == "RESTORE" || version.kind == "UNDO" -> HistoryMarker.RESTORED
        else -> HistoryMarker.NORMAL
    }

    private fun JsonElement.jsonObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private fun dateTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT)

    private fun dateKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)

    private companion object {
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
