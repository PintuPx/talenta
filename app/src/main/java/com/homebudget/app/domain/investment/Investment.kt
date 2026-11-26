package com.homebudget.app.domain.investment

import kotlinx.coroutines.flow.Flow

data class InvestmentValue(
    val accountId: Long,
    val valueMinor: Long,
    val recordedAt: Long,
)

interface InvestmentRepository {
    fun observeLatestValues(): Flow<List<InvestmentValue>>
    suspend fun recordValue(accountId: Long, valueMinor: Long)
}
