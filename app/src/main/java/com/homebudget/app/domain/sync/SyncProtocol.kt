package com.homebudget.app.domain.sync

import com.homebudget.app.domain.household.Role
import com.homebudget.app.domain.household.RosterEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/*
 * Shared-folder layout (see docs/SYNC.md). A log in the style of Kafka:
 *
 *   household.json              plain: format, salt, KDF parameters, passphrase check (no data)
 *   member-<id>.enc             one per member, written ONLY by that member: head offset, month
 *                               segments, offsets consumed from other partitions, last sync.
 *                               The Admin's member file also carries the roster.
 *   log-<id>-<yyyy-MM>.enc      one partition per member, one file per month, written ONLY by that member
 *   snapshot.enc                Admin: full household state at an offset, for new phones
 *
 * Every file has exactly one writer, so concurrent phones never overwrite each other.
 * The Admin partition is the source of truth: other phones build their data from it.
 */

/** One event in a partition. Offsets start at 0 and increase by 1 within a partition. */
@Serializable
data class SyncEvent(
    val offset: Long,
    val at: Long,
    val kind: String,
    /** For upsert/delete: the state list name, e.g. "transactions". */
    val entity: String? = null,
    /** For upsert/delete: the row key (id or key) as text. */
    val key: String? = null,
    val data: JsonElement? = null,
    /** Entity-version uid, or a stable control-event uid. */
    val eventUid: String? = null,
) {
    companion object {
        /** Admin: a row was added or changed. data = the row. */
        const val UPSERT = "upsert"
        /** Admin: a row was deleted. */
        const val DELETE = "delete"
        /** Admin: a household setting changed (entity = setting name). */
        const val SETTING = "setting"
        /** Reviewer: a proposal. data = ProposalMessage. */
        const val PROPOSAL = "proposal"
        /** Admin: a proposal was approved or rejected. data = DecisionMessage. */
        const val DECISION = "decision"
        /** One shared EntityVersion row. */
        const val VERSION = "version"
        const val ROSTER = "roster"
        const val HOUSEHOLD_END = "household_end"
        const val BULK_SKIPPED = "bulk_skipped"
    }
}

@Serializable
data class Segment(val partition: String, val month: String, val events: List<SyncEvent>)

@Serializable
data class RosterMember(
    val memberId: String,
    val email: String,
    val name: String,
    val role: String,
    val maxSyncFormat: Int = 1,
) {
    fun toEntry() = RosterEntry(memberId, email, name, Role.valueOf(role))

    companion object {
        fun of(e: RosterEntry) = RosterMember(e.memberId, e.email, e.name, e.role.name)
    }
}

@Serializable
data class MemberFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val memberId: String,
    val name: String,
    val email: String,
    val role: String,
    /** Last offset written to this member's own partition; -1 when empty. */
    val head: Long = -1,
    /** Month ("yyyy-MM") -> first offset in that month's segment. */
    val segments: Map<String, Long> = emptyMap(),
    /** Partition (member id) -> last offset this member has applied; -1 when nothing. */
    val consumed: Map<String, Long> = emptyMap(),
    val lastSyncAt: Long = 0,
    val appVersion: String = "",
    val appVersionCode: Int = 0,
    val appVersionName: String = appVersion,
    val maxSyncFormat: Int = 1,
    val releaseLabel: String = "Good to have",
    val whatsNew: String = "",
    val deviceId: String = "",
    /** Admin only. */
    val roster: List<RosterMember> = emptyList(),
    /** Admin only: offset the snapshot was taken at; -1 when none. */
    val snapshotOffset: Long = -1,
) {
    companion object {
        const val FORMAT = "talenta.member"
    }
}

@Serializable
data class HouseholdFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val name: String,
    val adminMemberId: String,
    val saltB64: String,
    val iterations: Int,
    /** Encrypted [CHECK_TEXT]; proves the passphrase before anything else is read. */
    val checkB64: String,
) {
    companion object {
        const val FORMAT = "talenta.household"
        const val CHECK_TEXT = "talenta-passphrase-ok"
        const val CHECK_AAD = "household-check"
    }
}

@Serializable
data class SnapshotFile(val offset: Long, val takenAt: Long, val state: JsonElement)

@Serializable
data class ProposalMessage(
    val uuid: String,
    val kind: String,
    val authorName: String,
    val payload: String,
    val summary: String,
    val createdAt: Long,
)

@Serializable
data class DecisionMessage(
    val uuid: String,
    val approved: Boolean,
    val reason: String = "",
    val decidedAt: Long,
    val resultId: Long? = null,
)

object SyncNames {
    const val HOUSEHOLD = "household.json"
    const val SNAPSHOT = "snapshot.enc"
    const val FOLDER_NAME = "Talenta household"

    fun member(id: String) = "member-$id.enc"
    fun segment(id: String, month: String) = "log-$id-$month.enc"
    fun month(epochMillis: Long): String = YearMonth.from(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC)).toString()

    private val MEMBER = Regex("^member-([a-z0-9-]{1,36})\\.enc$")
    fun memberIdOf(fileName: String): String? = MEMBER.matchEntire(fileName)?.groupValues?.get(1)

    /** UUID member ids and older short ids are safe to use in Drive file names. */
    fun isValidMemberId(id: String) =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id) ||
            Regex("^[a-z0-9]{1,32}$").matches(id)

    /**
     * Month of each event, never moving backwards when the device clock does, so each segment has
     * one consecutive range of offsets.
     */
    fun assignMonths(events: List<SyncEvent>): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        var last = ""
        events.sortedBy { it.offset }.forEach { event ->
            val current = month(event.at).let { if (it < last) last else it }
            out[event.offset] = current
            last = current
        }
        return out
    }
}

/** JSON-safe representation of one shared row from entity_versions. */
@Serializable
data class VersionSyncEvent(
    val entityType: String,
    val entityUid: String,
    val version: Int,
    val status: String,
    val baseVersion: Int,
    val kind: String,
    val deleted: Boolean,
    val snapshot: String,
    val snapshotFormat: Int,
    val authorId: String,
    val authorName: String,
    val createdAt: Long,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val decisionNote: String? = null,
    val bulkId: String? = null,
    val eventUid: String,
)

object SyncJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
}

/** How far a member is behind. */
data class MemberLag(
    val memberId: String,
    val name: String,
    val role: Role?,
    /** Admin changes this member has not applied yet. */
    val behind: Long,
    /** For the Admin's own row: proposals from others not yet pulled. Otherwise this member's own unsent-to-admin count. */
    val waitingForAdmin: Long,
    val lastSyncAt: Long,
    val known: Boolean,
)

object Lag {
    /**
     * Lag for every member from the member files alone (that is all a phone downloads at start).
     * behind = admin head - the member's consumed admin offset. waitingForAdmin = member's own head -
     * the Admin's consumed offset for that member (proposals the Admin hasn't pulled).
     */
    fun compute(files: List<MemberFile>, adminMemberId: String, roster: List<RosterEntry>): List<MemberLag> {
        val byId = files.associateBy { it.memberId }
        val admin = byId[adminMemberId]
        val adminHead = admin?.head ?: -1
        return roster.map { r ->
            val f = byId[r.memberId]
            val behind = when {
                r.memberId == adminMemberId -> 0
                f == null -> adminHead + 1
                else -> (adminHead - (f.consumed[adminMemberId] ?: -1)).coerceAtLeast(0)
            }
            val waiting = when {
                f == null || r.memberId == adminMemberId -> 0
                else -> (f.head - (admin?.consumed?.get(r.memberId) ?: -1)).coerceAtLeast(0)
            }
            MemberLag(r.memberId, r.name, r.role, behind, waiting, f?.lastSyncAt ?: 0, known = f != null)
        }
    }

    /** Months whose segment can contain offsets after [after], given month -> first offset. */
    fun segmentsAfter(segments: Map<String, Long>, after: Long, head: Long): List<String> {
        if (head <= after) return emptyList()
        val sorted = segments.entries.sortedBy { it.value }
        return sorted.filterIndexed { i, e ->
            val nextFirst = sorted.getOrNull(i + 1)?.value ?: (head + 1)
            nextFirst - 1 > after && e.value <= head
        }.map { it.key }
    }
}
