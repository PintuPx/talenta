package com.homebudget.app.ui.budgets

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.domain.budget.BudgetLevel
import com.homebudget.app.domain.budget.BudgetStatus
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.CategorySheet
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.MonthSelector
import com.homebudget.app.ui.common.SelectField
import com.homebudget.app.ui.theme.LocalFinanceColors
import com.homebudget.app.ui.wallet.SetBudgetPrompt
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(
    onBack: (() -> Unit)?,
    readOnly: Boolean = false,
    vm: BudgetsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            if (!readOnly && !state.loading) {
                ExtendedFloatingActionButton(
                    onClick = { vm.startAdd() },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add budget") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null -> EmptyState(Icons.Filled.ErrorOutline, "Something went wrong", state.error!!, Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        MonthSelector(state.month, vm::previousMonth, vm::nextMonth)
                    }
                }
                state.copyFrom?.let { from ->
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("No budgets for ${state.month.format(DateFormats.month)} yet", style = MaterialTheme.typography.titleSmall)
                                Text("Use the same limits as ${from.format(DateFormats.month)}? You can change them afterwards.")
                                if (!readOnly) {
                                    FilledTonalButton(onClick = vm::copyFromEarlier) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(8.dp))
                                        Text("Copy from ${from.format(DateFormats.month)}")
                                    }
                                }
                            }
                        }
                    }
                }
                item { OverallCard(state, readOnly, onAdd = { vm.startAdd(null) }, onEdit = vm::startEdit, onUseSuggestion = vm::useSuggestion) }
                if (state.rows.isNotEmpty()) {
                    item { SectionTitle("Category budgets") }
                    items(state.rows, key = { it.status.budget.id }) { row ->
                        BudgetRowItem(row, enabled = !readOnly) { vm.startEdit(row) }
                    }
                }
                if (state.unbudgeted.isNotEmpty()) {
                    item { SectionTitle("Spending without a budget") }
                    items(state.unbudgeted, key = { "u" + (it.category?.id ?: 0) }) { u ->
                        UnbudgetedItem(u, enabled = !readOnly) { vm.startAdd(u.category?.id) }
                    }
                }
                if (state.overall == null && state.rows.isEmpty() && state.copyFrom == null) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Savings,
                            title = "Plan your month",
                            message = "Set a limit for all spending, or for categories such as Groceries. " +
                                "Spending is counted from your expenses (refunds subtract), and you'll see a warning at ${com.homebudget.app.domain.budget.BudgetRules.WARNING_PERCENT}%.",
                        )
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }

    editor?.let { e ->
        val fmt = LocalMoneyFormatter.current
        BudgetEditorDialog(
            editor = e,
            state = state,
            currencySymbol = fmt.symbol,
            decimal = fmt.fractionDigits > 0,
            onCategory = vm::setEditorCategory,
            onAmount = vm::setEditorAmount,
            onSave = { vm.saveEditor(fmt.fractionDigits) },
            onDelete = vm::deleteEditing,
            onDismiss = vm::dismissEditor,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
    )
}

/** Color, icon and words for a budget level; color is never the only cue. */
@Composable
fun levelVisual(status: BudgetStatus): Triple<Color, ImageVector, String> {
    val fc = LocalFinanceColors.current
    val fmt = LocalMoneyFormatter.current
    val u = status.usage
    return when (status.level) {
        BudgetLevel.OVER -> Triple(fc.expense, Icons.Filled.Error, "Over by ${fmt.format(u.spent - u.limit)}")
        BudgetLevel.WARNING -> Triple(fc.warning, Icons.Filled.Warning, "${fmt.format(u.remaining)} left · almost used")
        BudgetLevel.OK -> Triple(fc.income, Icons.Filled.CheckCircle, "${fmt.format(u.remaining)} left")
    }
}

@Composable
fun BudgetProgress(status: BudgetStatus, modifier: Modifier = Modifier) {
    val (color, _, _) = levelVisual(status)
    LinearProgressIndicator(
        progress = { status.usage.fractionUsed.coerceIn(0f, 1f) },
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        modifier = modifier.fillMaxWidth().height(8.dp),
        gapSize = 2.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun OverallCard(
    state: BudgetsUiState,
    readOnly: Boolean,
    onAdd: () -> Unit,
    onEdit: (BudgetRow) -> Unit,
    onUseSuggestion: () -> Unit,
) {
    val fmt = LocalMoneyFormatter.current
    val overall = state.overall
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        onClick = { if (!readOnly) { if (overall != null) onEdit(overall) else onAdd() } },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (overall == null) {
                Text("Spent ${fmt.format(state.totalSpent)}", style = MaterialTheme.typography.headlineSmall)
                if (state.month == YearMonth.now() && !readOnly) {
                    SetBudgetPrompt(
                        suggestion = state.suggestion,
                        months = state.suggestionMonths,
                        onUse = onUseSuggestion,
                        onEnter = onAdd,
                    )
                } else {
                    Text(
                        if (readOnly) "No overall budget is set." else "No overall budget. Tap to set a limit for all spending.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val s = overall.status
                val (color, icon, words) = levelVisual(s)
                Text(
                    "${fmt.format(s.usage.spent)} of ${fmt.format(s.usage.limit)}",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BudgetProgress(s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(words, style = MaterialTheme.typography.bodyMedium)
                }
                state.dailyAllowance?.let {
                    Text("About ${fmt.format(it)} a day for the rest of the month", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BudgetRowItem(row: BudgetRow, enabled: Boolean, onClick: () -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val s = row.status
    val (color, icon, words) = levelVisual(s)
    val catColor = row.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}: spent ${fmt.format(s.usage.spent)} of ${fmt.format(s.usage.limit)}. $words"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(CategoryIcons.of(row.category?.iconKey ?: "misc"), catColor, null, size = 36)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(row.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmt.format(s.usage.spent)} / ${fmt.format(s.usage.limit)}", style = MaterialTheme.typography.bodyMedium)
            }
            BudgetProgress(s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text(words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UnbudgetedItem(u: UnbudgetedSpend, enabled: Boolean, onClick: () -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val catColor = u.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = "Set a budget", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(CategoryIcons.of(u.category?.iconKey ?: "misc"), catColor, null, size = 36)
        Spacer(Modifier.size(12.dp))
        Text(u.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(fmt.format(u.amount), style = MaterialTheme.typography.bodyMedium)
        if (enabled) {
            Spacer(Modifier.size(8.dp))
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BudgetEditorDialog(
    editor: BudgetEditor,
    state: BudgetsUiState,
    currencySymbol: String,
    decimal: Boolean,
    onCategory: (Long?) -> Unit,
    onAmount: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickCategory by remember { mutableStateOf(false) }
    val byId = state.categories.associateBy { it.id }
    val categoryLabel = editor.categoryId?.let { id ->
        val c = byId[id] ?: return@let ""
        c.parentId?.let { "${c.name} · ${byId[it]?.name ?: ""}" } ?: c.name
    } ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.editingId == 0L) "Add budget" else "Edit budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("For ${state.month.format(DateFormats.month)}", style = MaterialTheme.typography.bodySmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = editor.categoryId == null,
                        onClick = { onCategory(null) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text("All spending") },
                    )
                    SegmentedButton(
                        selected = editor.categoryId != null,
                        onClick = { if (editor.categoryId == null) pickCategory = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text("Category") },
                    )
                }
                if (editor.categoryId != null) {
                    SelectField(label = "Category", value = categoryLabel, onClick = { pickCategory = true })
                }
                OutlinedTextField(
                    value = editor.amountText,
                    onValueChange = onAmount,
                    label = { Text("Monthly limit") },
                    prefix = { Text(currencySymbol) },
                    isError = editor.error != null,
                    supportingText = editor.error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                if (editor.editingId != 0L) {
                    TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (pickCategory) {
        CategorySheet(
            categories = state.categories.filter { it.active || it.id == editor.categoryId },
            selectedId = editor.categoryId,
            onSelect = { onCategory(it.id); pickCategory = false },
            onManage = null,
            onDismiss = { pickCategory = false },
            title = "Budget for",
        )
    }
}
