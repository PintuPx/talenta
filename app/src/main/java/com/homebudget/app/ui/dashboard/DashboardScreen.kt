package com.homebudget.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.R
import com.homebudget.app.domain.budget.BudgetRules
import com.homebudget.app.domain.budget.BudgetStatus
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.ui.wallet.SetBudgetPrompt
import com.homebudget.app.ui.wallet.WalletImage
import java.time.YearMonth
import com.homebudget.app.ui.budgets.BudgetProgress
import com.homebudget.app.ui.budgets.levelVisual
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.MonthSelector
import com.homebudget.app.ui.common.TransactionRow
import com.homebudget.app.ui.monthclose.MonthCloseReminderBanner
import com.homebudget.app.ui.theme.LocalFinanceColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAddTransaction: (TransactionType) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onOpenAccounts: () -> Unit,
    onScanBill: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenBudgets: () -> Unit = {},
    onOpenMonthClose: () -> Unit = {},
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null -> EmptyState(
                icon = Icons.Filled.ErrorOutline,
                title = "Something went wrong",
                message = state.error!!,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    BalanceCard(state.totalBalance, onOpenAccounts, Modifier.padding(horizontal = 16.dp))
                }
                item {
                    MonthCloseReminderBanner(onOpenMonthClose, Modifier.padding(horizontal = 16.dp))
                }
                item {
                    QuickActions(onAddTransaction, Modifier.padding(horizontal = 16.dp))
                }
                item {
                    BillActions(onScanBill, onOpenBills, Modifier.padding(horizontal = 16.dp))
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        MonthSelector(state.month, vm::previousMonth, vm::nextMonth)
                    }
                }
                item { SummaryRow(state, Modifier.padding(horizontal = 16.dp)) }
                item { BudgetCard(state, onOpenBudgets, vm::useSuggestion, Modifier.padding(horizontal = 16.dp)) }
                item { BreakdownCard(state.breakdown, Modifier.padding(horizontal = 16.dp)) }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recent transactions",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        if (state.hasTransactions) TextButton(onClick = onSeeAllTransactions) { Text("See all") }
                    }
                }
                if (!state.hasTransactions) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Receipt,
                            title = "No transactions yet",
                            message = "Record your first income or expense to see your balance, savings and spending here.",
                            action = {
                                FilledTonalButton(onClick = { onAddTransaction(TransactionType.EXPENSE) }) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Add transaction")
                                }
                            },
                        )
                    }
                } else {
                    items(state.recent, key = { it.transaction.id }) { row ->
                        TransactionRow(row, onClick = { onOpenTransaction(row.transaction.id) })
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun BalanceCard(total: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fmt = LocalMoneyFormatter.current
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Total balance", style = MaterialTheme.typography.labelLarge)
            Text(
                fmt.format(total),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Across accounts included in total · tap to view accounts",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun QuickActions(onAdd: (TransactionType) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction("Income", Icons.Filled.Add, Modifier.weight(1f)) { onAdd(TransactionType.INCOME) }
        QuickAction("Expense", Icons.Filled.Remove, Modifier.weight(1f)) { onAdd(TransactionType.EXPENSE) }
        QuickAction("Transfer", Icons.Filled.SwapHoriz, Modifier.weight(1f)) { onAdd(TransactionType.TRANSFER) }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    // Compact padding so three labels fit on narrow phones (default 24dp padding truncated "Expense").
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Add $label" },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun BillActions(onScan: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Scan bill", maxLines = 1)
        }
        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Bills", maxLines = 1)
        }
    }
}

@Composable
private fun SummaryRow(state: DashboardUiState, modifier: Modifier = Modifier) {
    val fc = LocalFinanceColors.current
    val fmt = LocalMoneyFormatter.current
    val s = state.summary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard("Income", fmt.format(s.income), Icons.AutoMirrored.Filled.TrendingUp, fc.income, Modifier.weight(1f))
            SummaryCard("Expenses", fmt.format(s.netExpense), Icons.AutoMirrored.Filled.TrendingDown, fc.expense, Modifier.weight(1f))
        }
        SummaryCard(
            label = if (s.netSavings >= 0) "Net savings" else "Overspent (net)",
            value = fmt.formatSigned(s.netSavings),
            icon = Icons.Filled.Savings,
            tint = if (s.netSavings >= 0) fc.income else fc.expense,
            modifier = Modifier.fillMaxWidth(),
            footnote = if (s.refunds > 0) "Expenses are net of ${fmt.format(s.refunds)} in refunds" else null,
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.size(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (footnote != null) Text(footnote, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BreakdownCard(items: List<BreakdownItem>, modifier: Modifier = Modifier) {
    val fmt = LocalMoneyFormatter.current
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Spending by category", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (items.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PieChart, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text("No expenses this month", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items.forEach { item ->
                val color = item.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
                val name = item.category?.name ?: "Uncategorised"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "$name, ${fmt.format(item.amount)}, ${com.homebudget.app.ui.common.shareSpoken(item.share)} of spending"
                    },
                ) {
                    CategoryBadge(CategoryIcons.of(item.category?.iconKey ?: "misc"), color, null, size = 32)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${fmt.format(item.amount)} · ${com.homebudget.app.ui.common.shareLabel(item.share)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { item.share },
                            color = color,
                            trackColor = color.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetCard(state: DashboardUiState, onClick: () -> Unit, onUseSuggestion: () -> Unit, modifier: Modifier = Modifier) {
    val fmt = LocalMoneyFormatter.current
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Budget", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val effective = state.effectiveBudget
            val usage = state.effectiveUsage
            if (effective == null && state.categoryBudgetCount == 0) {
                if (state.month == YearMonth.now()) {
                    SetBudgetPrompt(suggestion = null, months = 0, onUse = null, onEnter = onClick)
                } else {
                    Text("No budget for this month. Tap to set monthly limits.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            if (effective != null && usage != null) {
                // Saved overall budget, or (labelled) an estimate that is not saved until the user accepts it.
                val status = state.overallBudget ?: BudgetStatus(
                    BudgetLimit(month = state.month, categoryId = null, limit = effective.limit), usage, BudgetRules.level(usage),
                )
                val (color, icon, words) = levelVisual(status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WalletImage(state.walletLevel)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (effective.estimated) {
                            Text("Estimated from your spending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(state.walletLevel.spoken, style = MaterialTheme.typography.titleSmall)
                        Text("${fmt.format(usage.spent)} of ${fmt.format(usage.limit)}", style = MaterialTheme.typography.bodyLarge)
                        BudgetProgress(status)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(words, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (effective.estimated) {
                    SetBudgetPrompt(suggestion = state.suggestion, months = state.suggestionMonths, onUse = onUseSuggestion, onEnter = onClick)
                }
            }
            if (state.categoryBudgetCount > 0) {
                val n = state.budgetsNeedingAttention
                Text(
                    if (n == 0) "All ${state.categoryBudgetCount} category budgets on track"
                    else "$n of ${state.categoryBudgetCount} category budgets almost used or over",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n == 0) MaterialTheme.colorScheme.onSurfaceVariant else LocalFinanceColors.current.warning,
                )
            }
        }
    }
}
