package com.homebudget.app.domain.family

import kotlinx.coroutines.flow.Flow

data class ExpensePerson(
    val uid: String,
    val name: String,
    val active: Boolean,
)

data class ExpenseTag(
    val uid: String,
    val name: String,
    val active: Boolean,
)

data class ExpensePersonTag(
    val tagUid: String,
    val personUid: String,
)

data class ExpenseShare(
    val personUid: String,
    val amount: Long,
)

data class ReportAllocation(
    val transactionId: Long,
    val amount: Long,
)

sealed interface FamilyReportFilter {
    data object None : FamilyReportFilter
    data object Unassigned : FamilyReportFilter
    data class Person(val uid: String) : FamilyReportFilter
    data class Tag(val uid: String) : FamilyReportFilter
}

interface ExpenseFamilyRepository {
    fun observeActivePeople(): Flow<List<ExpensePerson>>
    fun observeAllPeople(): Flow<List<ExpensePerson>>
    fun observeActiveTags(): Flow<List<ExpenseTag>>
    fun observeTagLinks(): Flow<List<ExpensePersonTag>>
    suspend fun addPerson(name: String): String
    suspend fun deactivatePerson(uid: String)
    suspend fun addTag(name: String): String
    suspend fun deactivateTag(uid: String)
    suspend fun setTagMember(tagUid: String, personUid: String, assigned: Boolean)
    suspend fun transactionShares(transactionUid: String, privateSpace: Boolean): List<ExpenseShare>
    suspend fun saveTransactionShares(
        transactionUid: String,
        totalMinor: Long,
        personUids: Set<String>,
        privateSpace: Boolean,
        month: Int,
    )
    fun observeReportAllocations(filter: FamilyReportFilter, privateSpace: Boolean): Flow<List<ReportAllocation>>
}
