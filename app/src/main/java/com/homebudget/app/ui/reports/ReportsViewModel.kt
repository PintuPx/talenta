package com.homebudget.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.PurchaseRepository
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.Space
import com.homebudget.app.data.settings.SpaceProvider
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.family.ExpensePerson
import com.homebudget.app.domain.family.ExpenseTag
import com.homebudget.app.domain.family.FamilyReportFilter
import com.homebudget.app.domain.family.ReportAllocation
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.export.CsvExporter
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.finance.PeriodSummary
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.mainGroupId
import com.homebudget.app.domain.report.ItemPriceHistory
import com.homebudget.app.domain.report.MonthTotals
import com.homebudget.app.domain.report.PriceHistory
import com.homebudget.app.domain.report.Reports
import com.homebudget.app.ui.common.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.YearMonth
import javax.inject.Inject

enum class ReportPeriod { MONTH, YEAR }

data class ReportSelection(
    val period: ReportPeriod = ReportPeriod.MONTH,
    val month: YearMonth = YearMonth.now(),
    /** Main group being drilled into (its groups are shown), or null for all main groups. */
    val drillMainGroupId: Long? = null,
    val trendSelected: Int? = null,
    val itemQuery: String = "",
    val selectedItemKey: String? = null,
    val pricePointSelected: Int? = null,
    val familyFilter: FamilyReportFilter = FamilyReportFilter.None,
)

data class SpendShare(val categoryId: Long?, val category: Category?, val label: String, val amount: Long, val share: Float, val hasGroups: Boolean)

data class ReportsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val selection: ReportSelection = ReportSelection(),
    val range: DateRange? = null,
    val summary: PeriodSummary = PeriodSummary.ZERO,
    val savingsRate: Int? = null,
    /** Net spending in the previous period (previous month or year) for comparison. */
    val previousExpense: Long = 0,
    /** Average monthly net spending over the trend months that have any activity. */
    val averageMonthlyExpense: Long = 0,
    val trend: List<MonthTotals> = emptyList(),
    val breakdown: List<SpendShare> = emptyList(),
    val drillLabel: String? = null,
    val items: List<ItemPriceHistory> = emptyList(),
    val selectedItem: ItemPriceHistory? = null,
    val periodTransactionCount: Int = 0,
    val familyPeople: List<ExpensePerson> = emptyList(),
    val familyTags: List<ExpenseTag> = emptyList(),
    val investedThisPeriodMinor: Long = 0,
    val investmentAccountCount: Int = 0,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    purchases: PurchaseRepository,
    private val settings: SettingsRepository,
    private val storage: BackupStorage,
    private val messages: UserMessages,
    private val expenseFamily: ExpenseFamilyRepository,
    private val spaceProvider: SpaceProvider,
) : ViewModel() {

    private val selection = MutableStateFlow(ReportSelection())

    private data class ReportInputs(
        val selection: ReportSelection,
        val transactions: List<Transaction>,
        val categories: List<Category>,
        val priceLines: List<com.homebudget.app.domain.report.PurchasedLine>,
        val privateSpace: Boolean,
        val accounts: List<Account> = emptyList(),
    )

    private val baseInputs = combine(
        selection,
        transactionRepository.observeAll(),
        categoryRepository.observeCategories(),
        purchases.observePurchasedLines(),
        spaceProvider.current,
    ) { sel, txns, cats, lines, space ->
        ReportInputs(sel, txns, cats, lines, space == Space.PRIVATE)
    }
    private val inputs = combine(baseInputs, accountRepository.observeAccounts()) { input, accounts ->
        input.copy(accounts = accounts)
    }

    val state: StateFlow<ReportsUiState> = inputs.flatMapLatest { input ->
        combine(
            expenseFamily.observeReportAllocations(input.selection.familyFilter, input.privateSpace),
            expenseFamily.observeAllPeople(),
            expenseFamily.observeActiveTags(),
        ) { allocations, people, tags ->
            build(
                input.selection,
                input.transactions,
                input.categories,
                PriceHistory.build(input.priceLines),
                allocations,
                people,
                tags,
                input.accounts,
            )
        }
    }
        .catch { emit(ReportsUiState(loading = false, error = "Could not load reports")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    private fun rangeOf(sel: ReportSelection): DateRange = when (sel.period) {
        ReportPeriod.MONTH -> DateRange.month(sel.month)
        ReportPeriod.YEAR -> DateRange(
            sel.month.withMonth(1).atDay(1).atStartOfDay(),
            sel.month.withMonth(1).plusYears(1).atDay(1).atStartOfDay(),
        )
    }

    private fun build(
        sel: ReportSelection,
        txns: List<Transaction>,
        cats: List<Category>,
        items: List<ItemPriceHistory>,
        allocations: List<ReportAllocation>,
        people: List<ExpensePerson>,
        tags: List<ExpenseTag>,
        accounts: List<Account>,
    ): ReportsUiState {
        val filteredTransactions = if (sel.familyFilter == FamilyReportFilter.None) {
            txns
        } else {
            val amounts = allocations.associate { it.transactionId to it.amount }
            txns.mapNotNull { transaction ->
                amounts[transaction.id]?.let { amount -> transaction.copy(amount = amount) }
            }
        }
        val byId = cats.associateBy { it.id }
        val rollUp: (Long?) -> Long? = { byId.mainGroupId(it) }
        val range = rangeOf(sel)
        val investmentAccountIds = accounts
            .filter { it.type == AccountType.INVESTMENT }
            .mapTo(HashSet()) { it.id }
        val summary = FinanceRules.summarize(filteredTransactions, range)
        val previousRange = when (sel.period) {
            ReportPeriod.MONTH -> DateRange.month(sel.month.minusMonths(1))
            ReportPeriod.YEAR -> DateRange(range.start.minusYears(1), range.start)
        }
        val trendMonths = when (sel.period) {
            ReportPeriod.MONTH -> Reports.monthsEnding(sel.month, 6)
            ReportPeriod.YEAR -> Reports.monthsOf(sel.month.year)
        }
        val trend = Reports.monthlyTotals(filteredTransactions, trendMonths)
        val active = trend.filter { it.income != 0L || it.expense != 0L }

        val parents = cats.mapNotNullTo(HashSet()) { it.parentId }
        val drill = sel.drillMainGroupId?.takeIf { byId.containsKey(it) }
        val spends = if (drill == null) FinanceRules.spendingByCategory(filteredTransactions, range, rollUp)
        else Reports.spendingWithin(filteredTransactions, range, drill, rollUp)
        val total = spends.sumOf { it.amount }
        val breakdown = spends.map { s ->
            val c = s.categoryId?.let(byId::get)
            val label = when {
                c == null -> "Uncategorised"
                drill != null && c.id == drill -> "${c.name} (not in a group)"
                else -> c.name
            }
            SpendShare(s.categoryId, c, label, s.amount, if (total > 0) s.amount.toFloat() / total else 0f,
                hasGroups = drill == null && s.categoryId in parents)
        }

        val filteredItems = PriceHistory.search(items, sel.itemQuery)
        return ReportsUiState(
            loading = false,
            selection = sel.copy(drillMainGroupId = drill),
            range = range,
            summary = summary,
            savingsRate = Reports.savingsRatePercent(summary.income, summary.netExpense),
            previousExpense = FinanceRules.summarize(filteredTransactions, previousRange).netExpense,
            averageMonthlyExpense = if (active.isEmpty()) 0 else active.sumOf { it.expense } / active.size,
            trend = trend,
            breakdown = breakdown,
            drillLabel = drill?.let { byId[it]?.name },
            items = filteredItems,
            selectedItem = sel.selectedItemKey?.let { k -> items.firstOrNull { it.key == k } },
            periodTransactionCount = filteredTransactions.count { it.occurredAt in range },
            familyPeople = people,
            familyTags = tags,
            investedThisPeriodMinor = FinanceRules.investedThisPeriod(txns, investmentAccountIds, range),
            investmentAccountCount = investmentAccountIds.size,
        )
    }

    fun setPeriod(p: ReportPeriod) = selection.update { it.copy(period = p, drillMainGroupId = null, trendSelected = null) }
    fun previous() = selection.update {
        it.copy(month = if (it.period == ReportPeriod.MONTH) it.month.minusMonths(1) else it.month.minusYears(1), trendSelected = null)
    }
    fun next() = selection.update {
        it.copy(month = if (it.period == ReportPeriod.MONTH) it.month.plusMonths(1) else it.month.plusYears(1), trendSelected = null)
    }
    fun selectTrend(i: Int) = selection.update { it.copy(trendSelected = if (it.trendSelected == i) null else i) }
    fun drillInto(mainGroupId: Long?) = selection.update { it.copy(drillMainGroupId = mainGroupId) }
    fun setItemQuery(q: String) = selection.update { it.copy(itemQuery = q.take(50)) }
    fun selectItem(key: String?) = selection.update { it.copy(selectedItemKey = key, pricePointSelected = null) }
    fun selectPricePoint(i: Int) = selection.update { it.copy(pricePointSelected = i) }
    fun setFamilyFilter(filter: FamilyReportFilter) =
        selection.update { it.copy(familyFilter = filter, drillMainGroupId = null, trendSelected = null) }

    /** Writes transactions as CSV to [uri]. [allTime] false = only the selected period. */
    fun exportCsv(uri: String, allTime: Boolean) {
        viewModelScope.launch {
            try {
                val range = state.value.range
                val txns = transactionRepository.observeAll().first().filter { allTime || range == null || it.occurredAt in range }
                val accounts = accountRepository.observeAccounts().first().associateBy { it.id }
                val categories = categoryRepository.observeCategories().first().associateBy { it.id }
                val csv = CsvExporter.export(txns, accounts, categories, settings.settings.first().currencyCode)
                storage.write(uri, csv.toByteArray(Charsets.UTF_8))
                messages.show("Exported ${txns.size} transactions")
            } catch (e: IOException) {
                messages.show("Export failed: ${e.message ?: "could not write the file"}")
            } catch (e: SecurityException) {
                messages.show("Export failed: permission denied")
            }
        }
    }
}
