package com.homebudget.app.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.data.repository.TransactionSort
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.ui.common.ChoiceDialog
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.MonthSelector
import com.homebudget.app.ui.common.TransactionRow
import com.homebudget.app.ui.theme.LocalFinanceColors

private enum class Picker { TYPE, ACCOUNT, CATEGORY, SORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    onAddTransaction: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onBack: (() -> Unit)?,
    vm: TransactionListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<Picker?>(null) }
    val f = state.filters

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val accountName = f.accountId?.let { id -> state.accounts.firstOrNull { it.id == id }?.name }
                    Text(if (onBack != null && accountName != null) accountName else "Transactions")
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { picker = Picker.SORT }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${f.sort.label}")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = f.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search notes, payee or category") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (f.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = f.month == null,
                    onClick = { vm.setAllTime(f.month != null) },
                    label = { Text("All time") },
                )
                FilterChip(
                    selected = f.type != null,
                    onClick = { picker = Picker.TYPE },
                    label = { Text(f.type?.let(::typeLabel) ?: "Type") },
                )
                FilterChip(
                    selected = f.accountId != null,
                    onClick = { picker = Picker.ACCOUNT },
                    label = { Text(f.accountId?.let { id -> state.accounts.firstOrNull { it.id == id }?.name } ?: "Account") },
                )
                if (f.type != TransactionType.TRANSFER) {
                    FilterChip(
                        selected = f.categoryId != null,
                        onClick = { picker = Picker.CATEGORY },
                        label = { Text(f.categoryId?.let { id -> state.categories.firstOrNull { it.id == id }?.name } ?: "Category") },
                    )
                }
                if (f.activeCount > 0 || f.query.isNotEmpty()) {
                    TextButton(onClick = vm::clearFilters) { Text("Clear") }
                }
            }
            if (f.month != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    MonthSelector(f.month, vm::previousMonth, vm::nextMonth)
                }
            }
            when {
                state.loading -> LoadingState()
                state.error != null -> EmptyState(Icons.Filled.ErrorOutline, "Something went wrong", state.error!!)
                state.items.isEmpty() -> {
                    val filtered = f.activeCount > 0 || f.query.isNotEmpty()
                    EmptyState(
                        icon = if (filtered) Icons.Filled.SearchOff else Icons.Filled.Receipt,
                        title = if (filtered) "No matching transactions" else "No transactions in this period",
                        message = if (filtered) "Try changing or clearing the filters." else "Tap + to record income, an expense or a transfer.",
                    )
                }
                else -> {
                    TotalsBar(state)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = {
                            when (it) {
                                is ListItem.Header -> "h-${it.date}"
                                is ListItem.Row -> "t-${it.model.transaction.id}"
                            }
                        }) { item ->
                            when (item) {
                                is ListItem.Header -> Text(
                                    DateFormats.dayLabel(item.date),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                                )
                                is ListItem.Row -> TransactionRow(item.model, onClick = { onOpenTransaction(item.model.transaction.id) })
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) } // room for the FAB
                    }
                }
            }
        }
    }

    when (picker) {
        Picker.TYPE -> ChoiceDialog(
            title = "Transaction type",
            options = listOf<TransactionType?>(null) + TransactionType.entries,
            selected = f.type,
            label = { it?.let(::typeLabel) ?: "All types" },
            onSelect = { vm.setType(it); picker = null },
            onDismiss = { picker = null },
        )
        Picker.ACCOUNT -> ChoiceDialog(
            title = "Account",
            options = listOf<Long?>(null) + state.accounts.map { it.id },
            selected = f.accountId,
            label = { id -> id?.let { state.accounts.first { a -> a.id == it }.name } ?: "All accounts" },
            onSelect = { vm.setAccount(it); picker = null },
            onDismiss = { picker = null },
        )
        Picker.CATEGORY -> {
            val cats = state.categories.filter {
                when (f.type) {
                    TransactionType.INCOME -> it.kind == CategoryKind.INCOME
                    TransactionType.EXPENSE -> it.kind == CategoryKind.EXPENSE
                    else -> true
                }
            }
            ChoiceDialog(
                title = "Category",
                options = listOf<Long?>(null) + cats.map { it.id },
                selected = f.categoryId,
                label = { id ->
                    id?.let { cats.first { c -> c.id == it }.let { c -> if (c.kind == CategoryKind.INCOME) "${c.name} (income)" else c.name } }
                        ?: "All categories"
                },
                onSelect = { vm.setCategory(it); picker = null },
                onDismiss = { picker = null },
            )
        }
        Picker.SORT -> ChoiceDialog(
            title = "Sort by",
            options = TransactionSort.entries,
            selected = f.sort,
            label = { it.label },
            onSelect = { vm.setSort(it); picker = null },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

@Composable
private fun TotalsBar(state: TransactionListUiState) {
    val fmt = LocalMoneyFormatter.current
    val fc = LocalFinanceColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${state.count} shown", style = MaterialTheme.typography.bodySmall)
            Text("In ${fmt.format(state.summary.income)}", style = MaterialTheme.typography.bodySmall, color = fc.income)
            Text("Out ${fmt.format(state.summary.netExpense)}", style = MaterialTheme.typography.bodySmall, color = fc.expense)
        }
        HorizontalDivider()
    }
}

fun typeLabel(t: TransactionType): String = when (t) {
    TransactionType.INCOME -> "Income"
    TransactionType.EXPENSE -> "Expense"
    TransactionType.TRANSFER -> "Transfer"
}
