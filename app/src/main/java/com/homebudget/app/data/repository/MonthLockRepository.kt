package com.homebudget.app.data.repository

import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.MonthLockEntity
import com.homebudget.app.data.db.toStoredMonth
import com.homebudget.app.data.db.toYearMonth
import com.homebudget.app.domain.monthclose.MonthLock
import com.homebudget.app.domain.monthclose.MonthLockRepository as MonthLockRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomMonthLockRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val writer: VersionedWriter,
) : MonthLockRepositoryContract {
    private val dao = db.monthLockDao()

    override fun observeLocks(): Flow<List<MonthLock>> =
        dao.observeAll().map { rows -> rows.map { MonthLock(it.month.toYearMonth(), it.closedAt, it.closedBy) } }

    override fun observeClosed(month: YearMonth): Flow<Boolean> =
        dao.observeAll().map { rows -> rows.any { it.month == month.toStoredMonth() } }

    override suspend fun isClosed(month: YearMonth): Boolean =
        dao.getByMonth(month.toStoredMonth()) != null

    override suspend fun close(month: YearMonth) {
        val storedMonth = month.toStoredMonth()
        if (dao.getByMonth(storedMonth) == null) {
            val identity = writer.currentIdentity()
            val lock = MonthLockEntity(storedMonth, clock.millis(), identity.displayName)
            writer.apply(
                writer.change(
                    entityType = "month_lock",
                    entityUid = storedMonth.toString(),
                    isPrivate = false,
                    kind = "CLOSE_MONTH",
                    entity = lock,
                    month = storedMonth,
                ) { dao.insert(lock) },
            ).requireAccepted()
        }
    }

    override suspend fun reopen(month: YearMonth) {
        val storedMonth = month.toStoredMonth()
        val lock = dao.getByMonth(storedMonth) ?: return
        writer.apply(
            writer.change(
                entityType = "month_lock",
                entityUid = storedMonth.toString(),
                isPrivate = false,
                kind = "REOPEN_MONTH",
                entity = lock,
                deleted = true,
                month = storedMonth,
            ) { dao.delete(storedMonth) },
        ).requireAccepted()
    }
}
