package com.homebudget.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.TransactionFilter
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.repository.TransactionSort
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.finance.PeriodSummary
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.ui.common.TransactionRowModel
import com.homebudget.app.ui.navigation.TransactionsRoute
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
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** User-controlled list filters. [month] == null means all time. */
data class ListFilters(
    val month: YearMonth? = YearMonth.now(),
    val type: TransactionType? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val query: String = "",
    val sort: TransactionSort = TransactionSort.NEWEST,
) {
    val activeCount: Int get() = listOfNotNull(type, accountId, categoryId).size
}

sealed interface ListItem {
    data class Header(val date: LocalDate) : ListItem
    data class Row(val model: TransactionRowModel) : ListItem
}

data class TransactionListUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val filters: ListFilters = ListFilters(),
    val items: List<ListItem> = emptyList(),
    val count: Int = 0,
    val summary: PeriodSummary = PeriodSummary.ZERO,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
) : ViewModel() {

    private val initialAccount = savedStateHandle.toRoute<TransactionsRoute>().accountId.takeIf { it > 0 }

    private val filters = MutableStateFlow(
        // Opening an account's history shows all of its transactions, not just this month.
        ListFilters(accountId = initialAccount, month = if (initialAccount != null) null else YearMonth.now())
    )

    private val results = filters.flatMapLatest { f ->
        transactionRepository.observeFiltered(
            TransactionFilter(
                range = f.month?.let(DateRange::month),
                type = f.type,
                accountId = f.accountId,
                categoryId = f.categoryId,
                query = f.query,
                sort = f.sort,
            )
        ).map { f to it }
    }

    val state: StateFlow<TransactionListUiState> = combine(
        accountRepository.observeAccounts(),
        categoryRepository.observeCategories(),
        results,
    ) { accounts, categories, (f, txns) ->
        val accountMap = accounts.associateBy { it.id }
        val categoryMap = categories.associateBy { it.id }
        val rows = txns.map { TransactionRowModel.from(it, accountMap, categoryMap) }
        val grouped = f.sort == TransactionSort.NEWEST || f.sort == TransactionSort.OLDEST
        val items = if (grouped) {
            buildList<ListItem> {
                var last: LocalDate? = null
                rows.forEach { r ->
                    val d = r.transaction.occurredAt.toLocalDate()
                    if (d != last) { add(ListItem.Header(d)); last = d }
                    add(ListItem.Row(r))
                }
            }
        } else rows.map { ListItem.Row(it) }
        TransactionListUiState(
            loading = false,
            filters = f,
            items = items,
            count = txns.size,
            summary = FinanceRules.summarize(txns, DateRange.ALL),
            accounts = accounts,
            categories = categories,
        )
    }
        .catch { emit(TransactionListUiState(loading = false, error = "Could not load transactions")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionListUiState(filters = filters.value))

    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setType(t: TransactionType?) = filters.update {
        // Clearing type-incompatible category filter avoids an impossible empty combination.
        val keepCategory = t == null || t != TransactionType.TRANSFER
        it.copy(type = t, categoryId = if (keepCategory) it.categoryId else null)
    }
    fun setAccount(id: Long?) = filters.update { it.copy(accountId = id) }
    fun setCategory(id: Long?) = filters.update { it.copy(categoryId = id) }
    fun setSort(s: TransactionSort) = filters.update { it.copy(sort = s) }
    fun previousMonth() = filters.update { it.copy(month = (it.month ?: YearMonth.now()).minusMonths(1)) }
    fun nextMonth() = filters.update { it.copy(month = (it.month ?: YearMonth.now()).plusMonths(1)) }
    fun setAllTime(allTime: Boolean) = filters.update { it.copy(month = if (allTime) null else YearMonth.now()) }
    fun clearFilters() = filters.update { it.copy(type = null, accountId = null, categoryId = null, query = "") }
}
