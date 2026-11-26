package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.BuildConfig
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.MemberEntity
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.family.FamilyMember
import com.homebudget.app.domain.family.FamilyRepository
import com.homebudget.app.domain.family.FamilyRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomFamilyRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val writer: VersionedWriter,
) : FamilyRepository {
    private val memberDao = db.memberDao()

    override fun observeMembers() = memberDao.observeAll()
        .map { rows -> rows.filter { it.active }.map { it.toDomain() } }

    override suspend fun saveMembers(incoming: List<FamilyMember>) {
        require(incoming.count { it.role == FamilyRole.ADMIN } == 1) {
            "A family must have exactly one Admin."
        }
        require(incoming.isNotEmpty() && incoming.all { it.name.isNotBlank() && it.email.isNotBlank() }) {
            "Enter a name and email address for every family member."
        }
        require(incoming.all { it.email.trim().matches(Regex("[^\\s@]+@gmail\\.com", RegexOption.IGNORE_CASE)) }) {
            "Enter a valid Gmail address for every family member."
        }
        require(incoming.map { it.email.trim().lowercase() }.distinct().size == incoming.size) {
            "Each family member must have a different email address."
        }

        val existing = memberDao.getAll()
        val existingByEmail = existing.associateBy { it.email.trim().lowercase() }
        val currentMemberId = settings.settings.first().currentMemberId
        if (currentMemberId != null) {
            val current = existing.firstOrNull { it.memberId == currentMemberId && it.active }
                ?: throw IntegrityException("This phone is not an active family member.")
            if (current.role != FamilyRole.ADMIN.name) {
                throw IntegrityException("Only the Admin can manage family members and roles.")
            }
        }
        val prepared = incoming.map { draft ->
            val email = draft.email.trim().lowercase()
            val previous = existingByEmail[email] ?: existing.firstOrNull { it.memberId == draft.memberId }
            val memberId = previous?.memberId ?: draft.memberId.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
            val isCurrent = memberId == currentMemberId || currentMemberId == null && draft.role == FamilyRole.ADMIN
            MemberEntity(
                memberId = memberId,
                email = email,
                name = draft.name.trim(),
                role = draft.role.name,
                active = true,
                appVersionCode = if (isCurrent) BuildConfig.VERSION_CODE else previous?.appVersionCode ?: 0,
                appVersionName = if (isCurrent) BuildConfig.VERSION_NAME else previous?.appVersionName.orEmpty(),
                syncFormat = previous?.syncFormat ?: 1,
                lastSeenAt = previous?.lastSeenAt,
            )
        }
        val resolvedCurrentId = currentMemberId ?: prepared.single { it.role == FamilyRole.ADMIN.name }.memberId
        require(prepared.any { it.memberId == resolvedCurrentId }) {
            "Use Leave family to remove this phone from the member list."
        }

        // REVIEW: roster changes are data-critical and remain local until sync publishes them.
        // TODO(sync): publish roster changes as admin-authored events.
        db.withTransaction {
            val activeIds = prepared.mapTo(mutableSetOf()) { it.memberId }
            val changes = mutableListOf<VersionedChange>()
            existing.filter { it.active && it.memberId !in activeIds }.forEach { old ->
                val inactive = old.copy(active = false)
                changes += writer.change("member", old.memberId, false, "EDIT", inactive) {
                        memberDao.update(inactive)
                    }
            }
            prepared.forEach { member ->
                val old = existing.firstOrNull { it.memberId == member.memberId }
                changes += writer.change(
                    "member",
                    member.memberId,
                    false,
                    if (old == null) "ADD" else "EDIT",
                    member,
                ) { memberDao.upsert(member) }
            }
            writer.applyBulk(changes)
        }
        if (currentMemberId == null) settings.setCurrentMemberId(resolvedCurrentId)
    }

    override suspend fun currentMember(): FamilyMember? {
        val memberId = settings.settings.first().currentMemberId ?: return null
        return memberDao.getById(memberId)?.takeIf { it.active }?.toDomain()
    }

    override suspend fun handOverAdmin(memberId: String) {
        val current = currentMember() ?: throw IntegrityException("This phone is not an active family member.")
        if (current.role != FamilyRole.ADMIN) throw IntegrityException("Only the Admin can hand over the role.")
        val target = memberDao.getById(memberId)?.takeIf { it.active }
            ?: throw IntegrityException("Choose an active family member.")
        if (target.memberId == current.memberId) throw IntegrityException("Choose another family member.")
        db.withTransaction {
            val formerAdmin = current.toEntity().copy(role = FamilyRole.REVIEWER.name)
            val newAdmin = target.copy(role = FamilyRole.ADMIN.name)
            writer.applyBulk(
                listOf(
                    writer.change("member", formerAdmin.memberId, false, "EDIT", formerAdmin) {
                    memberDao.update(formerAdmin)
                    },
                    writer.change("member", newAdmin.memberId, false, "EDIT", newAdmin) {
                    memberDao.update(newAdmin)
                    },
                ),
            )
        }
        // TODO(sync): publish this role handover as an admin-authored roster event.
    }

    override suspend fun leaveFamily() {
        val current = currentMember() ?: throw IntegrityException("This phone is not an active family member.")
        val activeMembers = memberDao.getAll().filter { it.active }
        if (current.role == FamilyRole.ADMIN && activeMembers.any { it.memberId != current.memberId }) {
            throw IntegrityException("Hand over the Admin role before leaving the family.")
        }
        db.withTransaction {
            val inactive = current.toEntity().copy(active = false)
            writer.apply(
                writer.change("member", inactive.memberId, false, "EDIT", inactive) {
                    memberDao.update(inactive)
                },
            ).requireAccepted()
        }
        // TODO(sync): publish the member leave event to the household log.
        settings.setCurrentMemberId(null)
        settings.setPrivateMode(false)
        settings.setAppMode(AppMode.PERSONAL)
    }

    private fun MemberEntity.toDomain() = FamilyMember(
        memberId = memberId,
        email = email,
        name = name,
        role = FamilyRole.valueOf(role),
        active = active,
        appVersionCode = appVersionCode,
        appVersionName = appVersionName,
        syncFormat = syncFormat,
        lastSeenAt = lastSeenAt,
    )

    private fun FamilyMember.toEntity() = MemberEntity(
        memberId = memberId,
        email = email,
        name = name,
        role = role.name,
        active = active,
        appVersionCode = appVersionCode,
        appVersionName = appVersionName,
        syncFormat = syncFormat,
        lastSeenAt = lastSeenAt,
    )
}
