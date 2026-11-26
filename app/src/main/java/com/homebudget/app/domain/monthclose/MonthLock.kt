package com.homebudget.app.domain.monthclose

import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

data class MonthLock(
    val month: YearMonth,
    val closedAt: Long,
    val closedBy: String,
)

interface MonthLockRepository {
    fun observeLocks(): Flow<List<MonthLock>>
    fun observeClosed(month: YearMonth): Flow<Boolean>
    suspend fun isClosed(month: YearMonth): Boolean
    suspend fun close(month: YearMonth)
    suspend fun reopen(month: YearMonth)
}
