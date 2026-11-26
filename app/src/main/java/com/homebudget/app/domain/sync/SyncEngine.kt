package com.homebudget.app.domain.sync

import com.homebudget.app.domain.household.Role
import com.homebudget.app.domain.household.RosterEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Base64

/** A file in the shared folder. [ownerEmail] is the Google account that created it (null if unknown). */
data class RemoteFile(val id: String, val name: String, val ownerEmail: String?, val modifiedAt: Long)

/** The shared folder (Google Drive in the app, in-memory in tests). Implementations must be safe to retry. */
interface RemoteFolder {
    suspend fun list(): List<RemoteFile>
    suspend fun read(file: RemoteFile): ByteArray
    /** Creates [name], or overwrites [existing] (a file this account owns). */
    suspend fun write(name: String, bytes: ByteArray, existing: RemoteFile?): RemoteFile
}

/** This phone's identity in the household. */
data class SyncIdentity(val memberId: String, val name: String, val email: String, val role: Role, val adminMemberId: String)

/** Sync state kept on the phone (a private JSON file). */
@Serializable
data class LocalSyncState(
    /** This phone's own partition. */
    val myEvents: List<SyncEvent> = emptyList(),
    /** Highest own offset already uploaded. */
    val uploadedHead: Long = -1,
    /** Partition -> last offset applied here. */
    val consumed: Map<String, Long> = emptyMap(),
    /** Admin: the shared state as last turned into events. */
    val publishedState: JsonObject? = null,
    /** Admin: offset and month of the last snapshot written. */
    val snapshotOffset: Long = -1,
    val snapshotMonth: String? = null,
    /** Admin: household roster. Others: the roster as last read from the Admin. */
    val roster: List<RosterMember> = emptyList(),
    /** Member files as last downloaded, for showing lag. */
    val members: List<MemberFile> = emptyList(),
    val lastSyncAt: Long = 0,
) {
    val head get() = myEvents.lastOrNull()?.offset ?: -1
}

/** Where sync state is stored. Writes must be atomic (all or nothing). */
interface SyncStateStore {
    suspend fun load(): LocalSyncState
    suspend fun save(state: LocalSyncState)
}

/** The app's data, as seen by the sync engine. */
interface SyncDataPort {
    /** Shared household state of this phone's database ([StateDiff.stateOf]). */
    suspend fun currentState(): JsonObject
    /** Non-Admin phones: replaces the shared data with [state] in one transaction, keeping local-only data. */
    suspend fun replaceSharedState(state: JsonObject)
    /** Admin: a proposal arrived from [authorId]. Must ignore a uuid it already has. */
    suspend fun receiveProposal(authorId: String, message: ProposalMessage)
    /** Non-Admin phones: the Admin decided one of this phone's proposals. Unknown uuids are ignored. */
    suspend fun receiveDecision(message: DecisionMessage)
}

data class SyncReport(
    val uploaded: Int,
    val applied: Int,
    val proposalsReceived: Int,
    val lag: List<MemberLag>,
    val warnings: List<String>,
)

class SyncException(message: String) : Exception(message)

/**
 * The household sync protocol (see docs/SYNC.md). Network and database access come in through
 * [RemoteFolder], [SyncDataPort] and [SyncStateStore], so this class is plain Kotlin and unit-tested.
 * Callers must not run two operations of the same engine at the same time.
 */
// REVIEW: sync protocol applies encrypted, versioned financial data across household devices.
class SyncEngine(
    private val remote: RemoteFolder,
    private val data: SyncDataPort,
    private val store: SyncStateStore,
    private val identity: SyncIdentity,
    private val key: ByteArray,
    private val clock: () -> Long,
    private val appVersion: String,
) {
    private val json = SyncJson.json
    private val isAdmin get() = identity.role == Role.ADMIN

    companion object {
        /** Admin writes a new snapshot when the month changes or after this many events. */
        const val SNAPSHOT_EVERY = 300L

        /** Creates the household files in an empty folder. Returns the derived key. */
        suspend fun createHousehold(remote: RemoteFolder, householdName: String, admin: SyncIdentity, passphrase: String, salt: ByteArray = SyncCrypto.newSalt()): ByteArray {
            if (remote.list().any { it.name == SyncNames.HOUSEHOLD }) throw SyncException("This folder already has a household")
            val key = SyncCrypto.deriveKey(passphrase, salt)
            val b64 = Base64.getEncoder()
            val check = SyncCrypto.encrypt(key, HouseholdFile.CHECK_TEXT.toByteArray(), HouseholdFile.CHECK_AAD)
            val file = HouseholdFile(name = householdName, adminMemberId = admin.memberId, saltB64 = b64.encodeToString(salt), iterations = SyncCrypto.ITERATIONS, checkB64 = b64.encodeToString(check))
            remote.write(SyncNames.HOUSEHOLD, SyncJson.json.encodeToString(HouseholdFile.serializer(), file).toByteArray(), null)
            return key
        }

        /** Reads the household file and checks the passphrase. Returns the household and the key. */
        suspend fun openHousehold(remote: RemoteFolder, passphrase: String): Pair<HouseholdFile, ByteArray> {
            val f = remote.list().find { it.name == SyncNames.HOUSEHOLD } ?: throw SyncException("No Talenta household in this folder")
            val household = try {
                SyncJson.json.decodeFromString(HouseholdFile.serializer(), remote.read(f).toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw SyncException("The household file is damaged")
            }
            if (household.format != HouseholdFile.FORMAT) throw SyncException("Not a Talenta household")
            if (household.version > 1) throw SyncException("Created by a newer version of the app. Please update.")
            if (household.iterations < 100_000) throw SyncException("Unsafe household settings")
            val b64 = Base64.getDecoder()
            val key = SyncCrypto.deriveKey(passphrase, b64.decode(household.saltB64), household.iterations)
            val ok = try {
                SyncCrypto.decrypt(key, b64.decode(household.checkB64), HouseholdFile.CHECK_AAD).toString(Charsets.UTF_8) == HouseholdFile.CHECK_TEXT
            } catch (e: SyncCryptoException) {
                false
            }
            if (!ok) throw SyncException("Wrong passphrase")
            return household to key
        }

        /** Reads the Admin's roster (for a phone that is joining). */
        suspend fun readRoster(remote: RemoteFolder, key: ByteArray, adminMemberId: String): List<RosterEntry> {
            val files = remote.list()
            val household = files.find { it.name == SyncNames.HOUSEHOLD }
            val adminFile = files.find { it.name == SyncNames.member(adminMemberId) } ?: throw SyncException("The Admin has not synced yet")
            if (household?.ownerEmail != null && adminFile.ownerEmail != null && !household.ownerEmail.equals(adminFile.ownerEmail, true)) {
                throw SyncException("The Admin's file is not owned by the household owner")
            }
            val name = SyncNames.member(adminMemberId)
            val mf = SyncJson.json.decodeFromString(MemberFile.serializer(), SyncCrypto.decrypt(key, remote.read(adminFile), name).toString(Charsets.UTF_8))
            return mf.roster.map { it.toEntry() }
        }
    }

    // ---------- local events ----------

    /** Adds an event to this phone's own partition (uploaded on the next sync). */
    suspend fun append(kind: String, data: kotlinx.serialization.json.JsonElement?): SyncEvent {
        val s = store.load()
        val ev = SyncEvent(offset = s.head + 1, at = clock(), kind = kind, data = data)
        store.save(s.copy(myEvents = s.myEvents + ev))
        return ev
    }

    suspend fun propose(message: ProposalMessage) {
        check(identity.role == Role.REVIEWER) { "Only a Reviewer sends proposals" }
        append(SyncEvent.PROPOSAL, json.encodeToJsonElement(message))
    }

    suspend fun decide(message: DecisionMessage) {
        check(isAdmin) { "Only the Admin decides" }
        append(SyncEvent.DECISION, json.encodeToJsonElement(message))
    }

    suspend fun setRoster(roster: List<RosterEntry>) {
        check(isAdmin) { "Only the Admin manages members" }
        store.save(store.load().copy(roster = roster.map { RosterMember.of(it) }))
    }

    // ---------- operations ----------

    /** App start: publish this phone's changes, then download only the member files (lag). */
    suspend fun startup(): SyncReport {
        val warnings = mutableListOf<String>()
        val uploaded = publish()
        val files = remote.list()
        val members = readMemberFiles(files, warnings)
        val s = store.load()
        val roster = rosterFrom(members, s)
        store.save(s.copy(members = members, roster = roster.map { RosterMember.of(it) }))
        return SyncReport(uploaded, 0, 0, Lag.compute(members, identity.adminMemberId, roster), warnings)
    }

    /** "Sync now": publish, pull new events, publish the new consumed offsets. */
    suspend fun syncNow(): SyncReport {
        val warnings = mutableListOf<String>()
        var uploaded = publish()
        var files = remote.list()
        var members = readMemberFiles(files, warnings)
        var applied = 0
        var proposals = 0
        if (isAdmin) {
            proposals = pullProposals(files, members, warnings)
        } else {
            applied = pullAdmin(files, members, warnings)
        }
        uploaded += publish()
        files = remote.list()
        members = readMemberFiles(files, warnings)
        val s = store.load()
        val roster = rosterFrom(members, s)
        store.save(s.copy(members = members, roster = roster.map { RosterMember.of(it) }, lastSyncAt = clock()))
        return SyncReport(uploaded, applied, proposals, Lag.compute(members, identity.adminMemberId, roster), warnings)
    }

    // ---------- publish ----------

    /** Admin: turns data changes into events. Everyone: uploads new own events and the member file. */
    private suspend fun publish(): Int {
        var s = store.load()
        if (isAdmin) {
            val current = data.currentState()
            val old = s.publishedState ?: StateDiff.emptyState("")
            val changes = StateDiff.diff(old, current)
            if (changes.isNotEmpty() || s.publishedState == null) {
                val now = clock()
                var next = s.head + 1
                val events = changes.map { c -> SyncEvent(next++, now, c.kind, c.entity, c.key, c.data) }
                s = s.copy(myEvents = s.myEvents + events, publishedState = current)
                store.save(s)
            }
        }
        val files = remote.list().associateBy { it.name }
        val monthOf = SyncNames.assignMonths(s.myEvents)
        val newEvents = s.myEvents.filter { it.offset > s.uploadedHead }
        val months = newEvents.map { monthOf.getValue(it.offset) }.toSortedSet()
        for (month in months) {
            val name = SyncNames.segment(identity.memberId, month)
            val seg = Segment(identity.memberId, month, s.myEvents.filter { monthOf.getValue(it.offset) == month })
            remote.write(name, encrypt(name, json.encodeToString(Segment.serializer(), seg)), files[name])
        }
        if (isAdmin && s.publishedState != null) {
            val month = SyncNames.month(clock())
            val due = s.snapshotOffset < 0 || s.snapshotMonth != month || s.head - s.snapshotOffset >= SNAPSHOT_EVERY
            if (due && s.head >= 0) {
                val snap = SnapshotFile(s.head, clock(), s.publishedState!!)
                remote.write(SyncNames.SNAPSHOT, encrypt(SyncNames.SNAPSHOT, json.encodeToString(SnapshotFile.serializer(), snap)), files[SyncNames.SNAPSHOT])
                s = s.copy(snapshotOffset = s.head, snapshotMonth = month)
            }
        }
        val segments = s.myEvents.groupBy { monthOf.getValue(it.offset) }.mapValues { (_, evs) -> evs.first().offset }
        val mine = MemberFile(
            memberId = identity.memberId, name = identity.name, email = identity.email, role = identity.role.name,
            head = s.head, segments = segments, consumed = s.consumed, lastSyncAt = clock(), appVersion = appVersion,
            roster = if (isAdmin) s.roster else emptyList(), snapshotOffset = if (isAdmin) s.snapshotOffset else -1,
        )
        val memberName = SyncNames.member(identity.memberId)
        remote.write(memberName, encrypt(memberName, json.encodeToString(MemberFile.serializer(), mine)), files[memberName])
        store.save(s.copy(uploadedHead = s.head))
        return newEvents.size
    }

    // ---------- pull ----------

    private suspend fun pullProposals(files: List<RemoteFile>, members: List<MemberFile>, warnings: MutableList<String>): Int {
        var s = store.load()
        val roster = s.roster.map { it.toEntry() }
        var received = 0
        for (r in roster.filter { it.role == Role.REVIEWER }) {
            val mf = members.find { it.memberId == r.memberId } ?: continue
            val after = s.consumed[r.memberId] ?: -1
            val events = readEvents(files, r.memberId, r.email, mf, after, warnings) ?: continue
            for (ev in events) {
                if (ev.kind == SyncEvent.PROPOSAL && ev.data != null) {
                    val msg = runCatching { json.decodeFromJsonElement<ProposalMessage>(ev.data) }.getOrNull()
                    if (msg != null) {
                        data.receiveProposal(r.memberId, msg)
                        received++
                    }
                }
                s = s.copy(consumed = s.consumed + (r.memberId to ev.offset))
            }
            store.save(s)
        }
        return received
    }

    private suspend fun pullAdmin(files: List<RemoteFile>, members: List<MemberFile>, warnings: MutableList<String>): Int {
        val s = store.load()
        val adminId = identity.adminMemberId
        val adminFile = members.find { it.memberId == adminId } ?: run { warnings += "The Admin has not synced yet"; return 0 }
        val adminEmail = adminFile.email
        val consumed = s.consumed[adminId] ?: -1
        if (adminFile.head <= consumed) return 0
        val firstAvailable = adminFile.segments.values.minOrNull() ?: 0
        var base: JsonObject
        var after: Long
        if (consumed < 0 || firstAvailable > consumed + 1) {
            val snapFile = files.find { it.name == SyncNames.SNAPSHOT }
            if (snapFile == null || adminFile.snapshotOffset < 0) {
                if (firstAvailable > 0) throw SyncException("The Admin's data is not available yet. Ask the Admin to open the app.")
                base = StateDiff.emptyState("")
                after = -1
            } else {
                checkOwner(snapFile, adminEmail)
                val snap = json.decodeFromString(SnapshotFile.serializer(), decrypt(SyncNames.SNAPSHOT, remote.read(snapFile)))
                base = snap.state as? JsonObject ?: throw SyncException("The snapshot is damaged")
                after = snap.offset
            }
        } else {
            base = data.currentState()
            after = consumed
        }
        val events = readEvents(files, adminId, adminEmail, adminFile, after, warnings) ?: return 0
        val state = StateDiff.apply(base, events)
        data.replaceSharedState(state)
        events.filter { it.kind == SyncEvent.DECISION && it.data != null }.forEach { ev ->
            runCatching { json.decodeFromJsonElement<DecisionMessage>(ev.data!!) }.getOrNull()?.let { data.receiveDecision(it) }
        }
        val last = events.lastOrNull()?.offset ?: after
        store.save(store.load().let { it.copy(consumed = it.consumed + (adminId to last)) })
        return events.size
    }

    /**
     * Events of [partition] with offsets after [after] up to the member's head, in order, checked to be
     * consecutive. Returns null (with a warning) if something is missing or damaged.
     */
    private suspend fun readEvents(files: List<RemoteFile>, partition: String, ownerEmail: String, mf: MemberFile, after: Long, warnings: MutableList<String>): List<SyncEvent>? {
        if (mf.head <= after) return emptyList()
        val months = Lag.segmentsAfter(mf.segments, after, mf.head)
        val byName = files.associateBy { it.name }
        val events = mutableListOf<SyncEvent>()
        for (month in months) {
            val name = SyncNames.segment(partition, month)
            val f = byName[name] ?: run { warnings += "Missing changes from ${mf.name}; try again later"; return null }
            if (!ownerMatches(f, ownerEmail)) { warnings += "Ignored a file not created by ${mf.name}"; return null }
            val seg = try {
                json.decodeFromString(Segment.serializer(), decrypt(name, remote.read(f)))
            } catch (e: SyncCryptoException) {
                warnings += "Could not read changes from ${mf.name}: ${e.message}"; return null
            }
            events += seg.events.filter { it.offset > after && it.offset <= mf.head }
        }
        val sorted = events.sortedBy { it.offset }.distinctBy { it.offset }
        val expected = (after + 1)..mf.head
        if (sorted.map { it.offset } != expected.toList()) {
            warnings += "Some changes from ${mf.name} are not uploaded yet; try again later"
            // Use only the consecutive part, so nothing is skipped.
            val prefix = sorted.withIndex().takeWhile { (i, e) -> e.offset == after + 1 + i }.map { it.value }
            return prefix
        }
        return sorted
    }

    private suspend fun readMemberFiles(files: List<RemoteFile>, warnings: MutableList<String>): List<MemberFile> {
        val s = store.load()
        val rosterEmails = s.roster.associate { it.memberId to it.email }
        return files.mapNotNull { f ->
            val id = SyncNames.memberIdOf(f.name) ?: return@mapNotNull null
            val mf = try {
                json.decodeFromString(MemberFile.serializer(), decrypt(f.name, remote.read(f)))
            } catch (e: SyncCryptoException) {
                warnings += "Could not read a member file: ${e.message}"; return@mapNotNull null
            } catch (e: kotlinx.serialization.SerializationException) {
                warnings += "A member file is damaged"; return@mapNotNull null
            }
            if (mf.memberId != id) { warnings += "Ignored a renamed member file"; return@mapNotNull null }
            val expected = rosterEmails[id] ?: mf.email
            if (!ownerMatches(f, expected)) { warnings += "Ignored a member file not created by its member"; return@mapNotNull null }
            mf
        }
    }

    private fun rosterFrom(members: List<MemberFile>, s: LocalSyncState): List<RosterEntry> =
        if (isAdmin) s.roster.map { it.toEntry() }
        else members.find { it.memberId == identity.adminMemberId }?.roster?.map { it.toEntry() } ?: s.roster.map { it.toEntry() }

    private fun ownerMatches(f: RemoteFile, email: String) = f.ownerEmail == null || f.ownerEmail.equals(email, ignoreCase = true)

    private fun checkOwner(f: RemoteFile, email: String) {
        if (!ownerMatches(f, email)) throw SyncException("A household file was not created by the Admin")
    }

    private fun encrypt(name: String, text: String) = SyncCrypto.encrypt(key, text.toByteArray(Charsets.UTF_8), name)
    private fun decrypt(name: String, bytes: ByteArray) = SyncCrypto.decrypt(key, bytes, name).toString(Charsets.UTF_8)
}
