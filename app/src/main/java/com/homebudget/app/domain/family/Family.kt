package com.homebudget.app.domain.family

import kotlinx.coroutines.flow.Flow

enum class FamilyRole(val label: String) {
    ADMIN("Admin"),
    REVIEWER("Reviewer"),
    READ_ONLY("Read-only"),
}

data class FamilyMember(
    val memberId: String,
    val email: String,
    val name: String,
    val role: FamilyRole,
    val active: Boolean,
    val appVersionCode: Int,
    val appVersionName: String,
    val syncFormat: Int,
    val lastSeenAt: Long?,
)

interface FamilyRepository {
    fun observeMembers(): Flow<List<FamilyMember>>
    suspend fun saveMembers(members: List<FamilyMember>)
    suspend fun currentMember(): FamilyMember?
    suspend fun handOverAdmin(memberId: String)
    suspend fun leaveFamily()
}
