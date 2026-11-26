package com.homebudget.app.data.repository

import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.family.FamilyRole
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class WriteIdentity(
    val memberId: String,
    val displayName: String,
    val role: FamilyRole,
    val appMode: AppMode,
)

interface IdentityProvider {
    suspend fun current(): WriteIdentity
}

@Singleton
// REVIEW: this identity is the authority for family write permissions.
class RoomIdentityProvider @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : IdentityProvider {
    override suspend fun current(): WriteIdentity {
        val currentSettings = settings.settings.first()
        if (currentSettings.appMode != AppMode.SHARED) {
            return WriteIdentity("me", "You", FamilyRole.ADMIN, currentSettings.appMode)
        }
        val memberId = currentSettings.currentMemberId ?: run {
            if (db.memberDao().getAll().isEmpty()) {
                return WriteIdentity("me", "You", FamilyRole.ADMIN, currentSettings.appMode)
            }
            throw IntegrityException("This phone is not an active family member.")
        }
        val member = db.memberDao().getById(memberId)
            ?.takeIf { it.active }
            ?: throw IntegrityException("This phone is not an active family member.")
        val role = FamilyRole.entries.firstOrNull { it.name == member.role }
            ?: throw IntegrityException("This family role is not supported on this phone.")
        return WriteIdentity(member.memberId, member.name, role, currentSettings.appMode)
    }
}
