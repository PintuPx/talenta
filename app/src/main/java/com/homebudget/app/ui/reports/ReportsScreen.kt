package com.homebudget.app.ui.reports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.domain.report.ItemPriceHistory
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.ChartLegend
import com.homebudget.app.ui.common.ChartSeries
import com.homebudget.app.ui.common.ConfirmDialog
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.GroupedBarChart
import com.homebudget.app.ui.common.LineChart
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.theme.LocalFinanceColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val shortMonth = DateTimeFormatter.ofPattern("MMM")
private val shortDate = DateTimeFormatter.ofPattern("d MMM yy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onOpenBudgets: () -> Unit,
    vm: ReportsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var exportAsk by remember { mutableStateOf(false) }
    var exportAll by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(it.toString(), exportAll) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
                actions = {
                    TextButton(onClick = onOpenBudgets) { Text("Budgets") }
                    IconButton(onClick = { exportAsk = true }) { Icon(Icons.Filled.FileDownload, contentDescription = "Export CSV") }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null -> EmptyState(Icons.Filled.ErrorOutline, "Something went wrong", state.error!!, Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { PeriodBar(state, vm) }
                item { SummaryCard(state) }
                item { TrendCard(state, vm::selectTrend) }
                item { BreakdownCard(state, vm::drillInto) }
                item { PriceCard(state, vm) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (exportAsk) {
        val periodLabel = periodLabel(state)
        ConfirmDialog(
            title = "Export transactions (CSV)",
            message = "Creates a spreadsheet file with your transactions (date, amount, account, category, payee, note). " +
                "It is not encrypted, so keep it private.",
            confirmLabel = "This period ($periodLabel)",
            destructive = false,
            onConfirm = {
                exportAsk = false; exportAll = false
                exportLauncher.launch("talenta-transactions-${periodLabel.replace(' ', '-')}.csv")
            },
            onDismiss = { exportAsk = false },
            extraContent = {
                TextButton(onClick = {
                    exportAsk = false; exportAll = true
                    exportLauncher.launch("talenta-transactions-all-${LocalDate.now()}.csv")
                }) { Text("All transactions") }
            },
        )
    }
}

private fun periodLabel(state: ReportsUiState): String = when (state.selection.period) {
    ReportPeriod.MONTH -> state.selection.month.format(DateFormats.month)
    ReportPeriod.YEAR -> state.selection.month.year.toString()
}

@Composable
private fun PeriodBar(state: ReportsUiState, vm: ReportsViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReportPeriod.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = state.selection.period == p,
                    onClick = { vm.setPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, ReportPeriod.entries.size),
                    label = { Text(if (p == ReportPeriod.MONTH) "Month" else "Year") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::previous) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period") }
            Text(periodLabel(state), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = vm::next) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period") }
        }
    }
}

@Composable
private fun ReportCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun SummaryCard(state: ReportsUiState) {
    val fmt = LocalMoneyFormatter.current
    val fc = LocalFinanceColors.current
    val s = state.summary
    ReportCard("Summary") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Income", fmt.format(s.income), Modifier.weight(1f))
            Stat("Expenses", fmt.format(s.netExpense), Modifier.weight(1f))
            Stat(if (s.netSavings >= 0) "Saved" else "Overspent", fmt.format(kotlin.math.abs(s.netSavings)), Modifier.weight(1f))
        }
        state.savingsRate?.let { rate ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Savings, contentDescription = null, tint = if (rate >= 0) fc.income else fc.expense, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Savings rate: $rate% of income", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val prev = state.previousExpense
        val periodWord = if (state.selection.period == ReportPeriod.MONTH) "last month" else "last year"
        if (prev > 0 && s.netExpense >= 0) {
            val change = ((s.netExpense - prev) * 100 / prev).toInt()
            val up = change > 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null,
                    tint = if (up) fc.expense else fc.income,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    when {
                        change == 0 -> "Spending is about the same as $periodWord (${fmt.format(prev)})"
                        up -> "Spending is $change% higher than $periodWord (${fmt.format(prev)})"
                        else -> "Spending is ${-change}% lower than $periodWord (${fmt.format(prev)})"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.averageMonthlyExpense > 0) {
            Text(
                "Average monthly spending in the chart below: ${fmt.format(state.averageMonthlyExpense)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.periodTransactionCount == 0) {
            Text("No transactions in this period.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrendCard(state: ReportsUiState, onSelect: (Int) -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val fc = LocalFinanceColors.current
    val trend = state.trend
    val series = listOf(
        ChartSeries("Income", fc.income, trend.map { it.income }),
        ChartSeries("Expenses", fc.expense, trend.map { it.expense.coerceAtLeast(0) }),
    )
    val title = if (state.selection.period == ReportPeriod.MONTH) "Last 6 months" else "Month by month"
    ReportCard(title) {
        ChartLegend(series)
        GroupedBarChart(
            labels = trend.map { it.month.format(shortMonth) },
            series = series,
            selected = state.selection.trendSelected,
            onSelect = onSelect,
            description = "Income and expenses by month. " + trend.joinToString("; ") {
                "${it.month.format(DateFormats.month)}: income ${fmt.format(it.income)}, expenses ${fmt.format(it.expense)}"
            },
        )
        val sel = state.selection.trendSelected?.let { trend.getOrNull(it) }
        if (sel != null) {
            Text(
                "${sel.month.format(DateFormats.month)} · income ${fmt.format(sel.income)} · expenses ${fmt.format(sel.expense)} · " +
                    (if (sel.savings >= 0) "saved ${fmt.format(sel.savings)}" else "overspent ${fmt.format(-sel.savings)}"),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("Tap a month to see its numbers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BreakdownCard(state: ReportsUiState, onDrill: (Long?) -> Unit) {
    val fmt = LocalMoneyFormatter.current
    ReportCard(if (state.drillLabel != null) "Spending in ${state.drillLabel}" else "Spending by category") {
        if (state.drillLabel != null) {
            TextButton(onClick = { onDrill(null) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("All categories")
            }
        }
        if (state.breakdown.isEmpty()) {
            Text("No expenses in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.breakdown.forEach { item ->
            val color = item.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = item.hasGroups, onClickLabel = "Show groups") { onDrill(item.categoryId) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${item.label}, ${fmt.format(item.amount)}, ${com.homebudget.app.ui.common.shareSpoken(item.share)}" +
                            if (item.hasGroups) ". Double tap to see its groups" else ""
                    },
            ) {
                CategoryBadge(CategoryIcons.of(item.category?.iconKey ?: "misc"), color, null, size = 32)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(item.label + if (item.hasGroups) " ›" else "", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${fmt.format(item.amount)} · ${com.homebudget.app.ui.common.shareLabel(item.share)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { item.share },
                        color = color,
                        trackColor = color.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceCard(state: ReportsUiState, vm: ReportsViewModel) {
    val fmt = LocalMoneyFormatter.current
    val selected = state.selectedItem
    ReportCard("Item prices") {
        if (selected != null) {
            PriceHistoryView(selected, state.selection.pricePointSelected, vm::selectPricePoint)
            TextButton(onClick = { vm.selectItem(null) }) { Text("Back to items") }
            return@ReportCard
        }
        Text(
            "Price history of items from your approved bills. Search for an item such as sugar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.selection.itemQuery,
            onValueChange = vm::setItemQuery,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("Search items") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.items.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (state.selection.itemQuery.isBlank()) "Approve a bill to start tracking item prices." else "No matching items.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.items.take(12).forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.selectItem(item.key) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.points.size} purchase${if (item.points.size == 1) "" else "s"} · last ${item.points.last().date.format(shortDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(fmt.format(item.latest), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Line chart of one item's price over purchase dates, with the numbers as text too. */
@Composable
fun PriceHistoryView(item: ItemPriceHistory, selected: Int?, onSelect: (Int) -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val color = MaterialTheme.colorScheme.primary
    Text(item.label, style = MaterialTheme.typography.titleSmall)
    LineChart(
        values = item.points.map { it.amount },
        color = color,
        selected = selected,
        onSelect = onSelect,
        description = "Price of ${item.label} over time: " + item.points.joinToString("; ") {
            "${it.date.format(DateFormats.date)} ${fmt.format(it.amount)}"
        },
    )
    Row(Modifier.fillMaxWidth()) {
        Text(item.points.first().date.format(shortDate), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        if (item.points.size > 1) Text(item.points.last().date.format(shortDate), style = MaterialTheme.typography.labelSmall)
    }
    val point = selected?.let { item.points.getOrNull(it) }
    if (point != null) {
        Text(
            "${point.date.format(DateFormats.date)} · ${fmt.format(point.amount)}" +
                (if (point.merchant.isNotBlank()) " · ${point.merchant}" else "") + " · \"${point.description}\"",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        "Latest ${fmt.format(item.latest)} · lowest ${fmt.format(item.lowest)} · highest ${fmt.format(item.highest)} · average ${fmt.format(item.average)}" +
            (item.changePercent?.let { c -> " · ${if (c >= 0) "+" else ""}$c% since first purchase" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Amounts are what you paid for the line on each bill, so a bigger pack also shows as a higher price.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
