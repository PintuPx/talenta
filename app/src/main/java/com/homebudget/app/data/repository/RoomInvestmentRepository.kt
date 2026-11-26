package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.InvestmentValueEntity
import com.homebudget.app.data.db.toDomain
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.domain.investment.InvestmentRepository
import com.homebudget.app.domain.investment.InvestmentValue
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// REVIEW: valuations are versioned financial records and follow account privacy.
class RoomInvestmentRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val writer: VersionedWriter,
    private val spaceProvider: SpaceProvider,
) : InvestmentRepository {
    private val dao get() = db.investmentValueDao()
    private val privateSpace get() = spaceProvider.current.value == Space.PRIVATE

    override fun observeLatestValues(): Flow<List<InvestmentValue>> =
        spaceProvider.current.flatMapLatest { space ->
            dao.observeLatestInSpace(space == Space.PRIVATE)
                .map { rows -> rows.map { it.toDomain() } }
        }

    override suspend fun recordValue(accountId: Long, valueMinor: Long) = db.withTransaction {
        if (valueMinor < 0 || valueMinor > Money.MAX_AMOUNT) {
            throw IntegrityException("Enter a valid investment value.")
        }
        val account = db.accountDao().getByIdInSpace(accountId, privateSpace)
            ?: throw IntegrityException("Investment account not found.")
        if (account.type != AccountType.INVESTMENT) {
            throw IntegrityException("Choose an investment account.")
        }
        val value = InvestmentValueEntity(
            accountId = accountId,
            valueMinor = valueMinor,
            recordedAt = clock.millis(),
            isPrivate = account.isPrivate,
        )
        writer.apply(
            writer.change("investment_value", value.uid, value.isPrivate, "ADD", value) {
                dao.insert(value)
            },
        ).requireAccepted()
    }
}
