package com.homebudget.app.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.BudgetRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.domain.budget.BudgetRules
import com.homebudget.app.domain.budget.BudgetSuggestion
import com.homebudget.app.domain.budget.BudgetStatus
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.mainGroupId
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser
import com.homebudget.app.ui.common.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class BudgetRow(val status: BudgetStatus, val category: Category?, val label: String)

/** Spending in a category that has no budget this month. */
data class UnbudgetedSpend(val category: Category?, val label: String, val amount: Long)

data class BudgetsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val month: YearMonth = YearMonth.now(),
    val overall: BudgetRow? = null,
    val rows: List<BudgetRow> = emptyList(),
    val unbudgeted: List<UnbudgetedSpend> = emptyList(),
    val totalSpent: Long = 0,
    /** Per-day amount left for the overall budget (only for the current month). */
    val dailyAllowance: Long? = null,
    /** An earlier month with budgets that can be copied, shown when this month has none. */
    val copyFrom: YearMonth? = null,
    val categories: List<Category> = emptyList(),
    /** Suggested overall budget when the current month has none (not saved until accepted). */
    val suggestion: Long? = null,
    val suggestionMonths: Int = 0,
)

/** Editor dialog state. [categoryId] null = the overall monthly budget. */
data class BudgetEditor(
    val editingId: Long = 0,
    val categoryId: Long? = null,
    val amountText: String = "",
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val budgets: BudgetRepository,
    categories: CategoryRepository,
    transactions: TransactionRepository,
    private val messages: UserMessages,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    private val _editor = MutableStateFlow<BudgetEditor?>(null)
    val editor: StateFlow<BudgetEditor?> = _editor

    val state: StateFlow<BudgetsUiState> = month.flatMapLatest { ym ->
        combine(
            budgets.observeForMonth(ym),
            budgets.observeLatestMonthBefore(ym),
            categories.observeCategories(),
            transactions.observeRange(DateRange.month(ym)),
            transactions.observeRange(
                DateRange(
                    ym.minusMonths(BudgetSuggestion.MAX_MONTHS.toLong()).atDay(1).atStartOfDay(),
                    ym.atDay(1).atStartOfDay(),
                ),
            ),
        ) { limits, latestBefore, cats, txns, history -> build(ym, limits, latestBefore, cats, txns, history) }
    }
        .catch { emit(BudgetsUiState(loading = false, error = "Could not load budgets")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState())

    private fun build(
        ym: YearMonth,
        limits: List<BudgetLimit>,
        latestBefore: YearMonth?,
        cats: List<Category>,
        txns: List<com.homebudget.app.domain.model.Transaction>,
        history: List<com.homebudget.app.domain.model.Transaction>,
    ): BudgetsUiState {
        val byId = cats.associateBy { it.id }
        val rollUp: (Long?) -> Long? = { byId.mainGroupId(it) }
        val statuses = BudgetRules.statuses(limits, txns, rollUp)
        fun label(id: Long?): String = id?.let { c ->
            val cat = byId[c] ?: return@let "Deleted category"
            cat.parentId?.let { p -> "${cat.name} · ${byId[p]?.name ?: ""}" } ?: cat.name
        } ?: "All spending"
        val rows = statuses.map { BudgetRow(it, it.budget.categoryId?.let(byId::get), label(it.budget.categoryId)) }
        val overall = rows.firstOrNull { it.status.budget.categoryId == null }

        // Spending not covered by any category budget (a main-group budget covers its groups).
        val range = DateRange.month(ym)
        val budgeted = limits.mapNotNull { it.categoryId }.toSet()
        val unbudgeted = FinanceRules.spendingByCategory(txns, range)
            .filter { s -> s.categoryId !in budgeted && rollUp(s.categoryId) !in budgeted }
            .groupBy { rollUp(it.categoryId) }
            .map { (main, list) -> UnbudgetedSpend(main?.let(byId::get), label(main), list.sumOf { it.amount }) }
            .sortedByDescending { it.amount }

        val summary = FinanceRules.summarize(txns, range)
        val monthlyHistory = BudgetSuggestion.monthlyExpenses(history, ym)
        val suggestion = if (overall == null && ym == YearMonth.now()) BudgetSuggestion.suggest(monthlyHistory) else null
        return BudgetsUiState(
            suggestion = suggestion,
            suggestionMonths = BudgetSuggestion.monthsUsed(monthlyHistory),
            loading = false,
            month = ym,
            overall = overall,
            rows = rows.filter { it.status.budget.categoryId != null },
            unbudgeted = unbudgeted,
            totalSpent = summary.netExpense.coerceAtLeast(0),
            dailyAllowance = overall?.let { BudgetRules.dailyAllowance(it.status.usage.remaining, ym, LocalDate.now()) },
            copyFrom = if (limits.isEmpty()) latestBefore else null,
            categories = cats.filter { it.kind == CategoryKind.EXPENSE },
        )
    }

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }

    fun startAdd(categoryId: Long? = null) {
        val taken = state.value.overall != null
        _editor.value = BudgetEditor(categoryId = categoryId ?: if (taken) state.value.categories.firstOrNull()?.id else null)
    }

    fun startEdit(row: BudgetRow) {
        _editor.value = BudgetEditor(
            editingId = row.status.budget.id,
            categoryId = row.status.budget.categoryId,
            amountText = com.homebudget.app.domain.money.Money.toEditableText(row.status.budget.limit),
        )
    }

    fun setEditorCategory(id: Long?) = _editor.update { it?.copy(categoryId = id, error = null) }
    fun setEditorAmount(text: String) = _editor.update { it?.copy(amountText = text.take(20), error = null) }
    fun dismissEditor() { _editor.value = null }

    fun saveEditor(fractionDigits: Int) {
        val e = _editor.value ?: return
        val amount = when (val r = AmountParser.parse(e.amountText, fractionDigits)) {
            is AmountParseResult.Valid -> r.minor
            AmountParseResult.Empty -> return _editor.update { it?.copy(error = "Enter the monthly limit") }
            AmountParseResult.TooManyDecimals -> return _editor.update { it?.copy(error = "Too many decimal places") }
            AmountParseResult.NotPositive -> return _editor.update { it?.copy(error = "The limit must be greater than zero") }
            AmountParseResult.TooLarge -> return _editor.update { it?.copy(error = "The limit is too large") }
            AmountParseResult.NotANumber -> return _editor.update { it?.copy(error = "Enter a valid amount") }
        }
        viewModelScope.launch {
            try {
                budgets.save(BudgetLimit(id = e.editingId, month = state.value.month, categoryId = e.categoryId, limit = amount))
                _editor.value = null
                messages.show(if (e.editingId == 0L) "Budget added" else "Budget updated")
            } catch (ex: IntegrityException) {
                _editor.update { it?.copy(error = ex.message) }
            }
        }
    }

    fun deleteEditing() {
        val id = _editor.value?.editingId?.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            budgets.delete(id)
            _editor.value = null
            messages.show("Budget removed")
        }
    }

    /** Saves the suggestion as this month overall budget. */
    fun useSuggestion() {
        val amount = state.value.suggestion ?: return
        val ym = state.value.month
        viewModelScope.launch {
            try {
                budgets.save(BudgetLimit(month = ym, categoryId = null, limit = amount))
                messages.show("Budget set")
            } catch (ex: IntegrityException) {
                messages.show(ex.message ?: "Could not save the budget")
            }
        }
    }

    fun copyFromEarlier() {
        val from = state.value.copyFrom ?: return
        val to = state.value.month
        viewModelScope.launch {
            val n = budgets.copy(from, to)
            messages.show("Copied $n budget${if (n == 1) "" else "s"}")
        }
    }
}
