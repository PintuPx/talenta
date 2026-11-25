package com.homebudget.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.BudgetRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.domain.budget.BudgetLevel
import com.homebudget.app.domain.budget.BudgetSuggestion
import com.homebudget.app.domain.budget.EffectiveBudget
import com.homebudget.app.domain.budget.WalletLevel
import com.homebudget.app.domain.budget.walletLevel
import com.homebudget.app.domain.finance.BudgetUsage
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.ui.common.UserMessages
import com.homebudget.app.domain.budget.BudgetRules
import com.homebudget.app.domain.budget.BudgetStatus
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.finance.PeriodSummary
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.mainGroupId
import com.homebudget.app.ui.common.TransactionRowModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class BreakdownItem(val category: Category?, val amount: Long, val share: Float)

data class DashboardUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val month: YearMonth = YearMonth.now(),
    val totalBalance: Long = 0,
    val summary: PeriodSummary = PeriodSummary.ZERO,
    val breakdown: List<BreakdownItem> = emptyList(),
    val recent: List<TransactionRowModel> = emptyList(),
    val hasTransactions: Boolean = false,
    /** Overall budget of the month, if set. */
    val overallBudget: BudgetStatus? = null,
    val categoryBudgetCount: Int = 0,
    /** Category budgets at or over the warning level. */
    val budgetsNeedingAttention: Int = 0,
    /** Suggested budget for the current month when it has no overall budget (not saved yet). */
    val suggestion: Long? = null,
    val suggestionMonths: Int = 0,
    /** Usage against the overall budget or, when none is set, against the suggestion. */
    val effectiveBudget: EffectiveBudget? = null,
    val effectiveUsage: BudgetUsage? = null,
    val walletLevel: WalletLevel = WalletLevel.FULL,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val messages: UserMessages,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    private val monthTransactions = month.flatMapLatest { ym ->
        val history = DateRange(
            ym.minusMonths(BudgetSuggestion.MAX_MONTHS.toLong()).atDay(1).atStartOfDay(),
            ym.atDay(1).atStartOfDay(),
        )
        combine(
            transactionRepository.observeRange(DateRange.month(ym)),
            budgetRepository.observeForMonth(ym),
            transactionRepository.observeRange(history),
        ) { t, b, h -> MonthData(ym, t, b, h) }
    }

    private data class MonthData(
        val month: YearMonth,
        val transactions: List<com.homebudget.app.domain.model.Transaction>,
        val budgets: List<com.homebudget.app.domain.model.BudgetLimit>,
        val history: List<com.homebudget.app.domain.model.Transaction>,
    )

    val state: StateFlow<DashboardUiState> = combine(
        accountRepository.observeAccounts(),
        accountRepository.observeBalances(),
        categoryRepository.observeCategories(),
        monthTransactions,
        transactionRepository.observeRecent(5),
    ) { accounts, balances, categories, monthData, recent ->
        val ym = monthData.month
        val txns = monthData.transactions
        val accountMap = accounts.associateBy { it.id }
        val categoryMap = categories.associateBy { it.id }
        val range = DateRange.month(ym)
        val summary = FinanceRules.summarize(txns, range)
        // Groups (e.g. Vegetables) roll up into their main group (Groceries) on the dashboard.
        val spends = FinanceRules.spendingByCategory(txns, range) { categoryMap.mainGroupId(it) }
        val totalSpend = spends.sumOf { it.amount }
        val budgetStatuses = BudgetRules.statuses(monthData.budgets, txns) { categoryMap.mainGroupId(it) }
        val overall = budgetStatuses.firstOrNull { it.budget.categoryId == null }
        // Only the current month gets a suggestion, and only while it has no overall budget of its own.
        val monthlyHistory = BudgetSuggestion.monthlyExpenses(monthData.history, ym)
        val suggestion = if (overall == null && ym == YearMonth.now()) BudgetSuggestion.suggest(monthlyHistory) else null
        val effective = EffectiveBudget.resolve(overall?.budget?.limit, suggestion)
        val effectiveUsage = overall?.usage ?: effective?.let {
            FinanceRules.budgetUsage(BudgetLimit(month = ym, categoryId = null, limit = it.limit), txns)
        }
        DashboardUiState(
            loading = false,
            month = ym,
            suggestion = suggestion,
            suggestionMonths = BudgetSuggestion.monthsUsed(monthlyHistory),
            effectiveBudget = effective,
            effectiveUsage = effectiveUsage,
            walletLevel = effectiveUsage?.let(::walletLevel) ?: WalletLevel.FULL,
            totalBalance = FinanceRules.totalBalance(accounts, balances),
            summary = summary,
            breakdown = spends.take(6).map {
                BreakdownItem(
                    category = it.categoryId?.let(categoryMap::get),
                    amount = it.amount,
                    share = if (totalSpend > 0) it.amount.toFloat() / totalSpend else 0f,
                )
            },
            recent = recent.map { TransactionRowModel.from(it, accountMap, categoryMap) },
            hasTransactions = recent.isNotEmpty(),
            overallBudget = overall,
            categoryBudgetCount = budgetStatuses.count { it.budget.categoryId != null },
            budgetsNeedingAttention = budgetStatuses.count { it.budget.categoryId != null && it.level != BudgetLevel.OK },
        )
    }
        .catch { emit(DashboardUiState(loading = false, error = "Could not load your data")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Saves the suggestion as this month overall budget. */
    fun useSuggestion() {
        val amount = state.value.suggestion ?: return
        val ym = state.value.month
        viewModelScope.launch {
            try {
                budgetRepository.save(BudgetLimit(month = ym, categoryId = null, limit = amount))
                messages.show("Budget set")
            } catch (ex: IntegrityException) {
                messages.show(ex.message ?: "Could not save the budget")
            }
        }
    }

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }
}
