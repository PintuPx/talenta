package com.homebudget.app.ui.wallet

import com.homebudget.app.data.repository.BudgetRepository
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.budget.BudgetSuggestion
import com.homebudget.app.domain.budget.EffectiveBudget
import com.homebudget.app.domain.budget.WalletLevel
import com.homebudget.app.domain.budget.walletLevel
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.BudgetLimit
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** The current month's budget state, used by the launcher icon and the home-screen widget. */
data class WalletSnapshot(
    val budget: EffectiveBudget?,
    val spent: Long,
    val level: WalletLevel,
    val currencyCode: String,
)

@Singleton
class WalletSnapshotLoader @Inject constructor(
    private val transactions: TransactionRepository,
    private val budgets: BudgetRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend fun load(): WalletSnapshot {
        val month = YearMonth.now(clock)
        val currency = settings.settings.first().currencyCode
        val overall = budgets.observeForMonth(month).first().firstOrNull { it.categoryId == null }
        val monthTxns = transactions.observeRange(DateRange.month(month)).first()
        val suggestion = if (overall == null) {
            val history = DateRange(
                month.minusMonths(BudgetSuggestion.MAX_MONTHS.toLong()).atDay(1).atStartOfDay(),
                month.atDay(1).atStartOfDay(),
            )
            BudgetSuggestion.suggestFor(transactions.observeRange(history).first(), month)
        } else {
            null
        }
        val effective = EffectiveBudget.resolve(overall?.limit, suggestion)
            ?: return WalletSnapshot(null, 0, WalletLevel.FULL, currency)
        val spent = FinanceRules.budgetUsage(BudgetLimit(month = month, categoryId = null, limit = effective.limit), monthTxns).spent
        return WalletSnapshot(effective, spent, walletLevel(effective.limit, spent), currency)
    }
}
