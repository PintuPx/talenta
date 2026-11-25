package com.homebudget.app.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.model.pathLabel
import com.homebudget.app.domain.recurring.Recurrence
import com.homebudget.app.domain.recurring.RepeatOption
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecurringRow(
    val rule: RecurringRule,
    val title: String,
    val subtitle: String,
    val iconKey: String?,
    val colorArgb: Long?,
)

data class RecurringUiState(val loading: Boolean = true, val rows: List<RecurringRow> = emptyList())

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurring: RecurringRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
) : ViewModel() {
    val state: StateFlow<RecurringUiState> = combine(
        recurring.observeRules(), accounts.observeAccounts(), categories.observeCategories(),
    ) { rules, accs, cats ->
        val accById = accs.associateBy { it.id }
        val catById = cats.associateBy { it.id }
        RecurringUiState(
            loading = false,
            rows = rules.map { r ->
                val cat = r.categoryId?.let(catById::get)
                val title = when (r.type) {
                    TransactionType.TRANSFER -> "Transfer to ${accById[r.toAccountId]?.name ?: "account"}"
                    else -> r.payee.ifBlank { catById.pathLabel(r.categoryId) ?: "Transaction" }
                }
                val repeat = RepeatOption.label(r.frequency, r.interval)
                val next = if (r.active) "next ${Recurrence.nextDue(r).toLocalDate().format(DateFormats.date)}" else "paused"
                RecurringRow(r, title, "$repeat · ${accById[r.accountId]?.name ?: ""} · $next", cat?.iconKey, cat?.colorArgb)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    fun setActive(id: Long, active: Boolean) = viewModelScope.launch { recurring.setActive(id, active) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    readOnly: Boolean = false,
    vm: RecurringViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fmt = LocalMoneyFormatter.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repeating transactions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            if (!readOnly) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.rows.isEmpty() -> EmptyState(
                icon = Icons.Filled.Repeat,
                title = "Nothing repeats yet",
                message = "Add rent, salary, subscriptions or EMIs once and they are recorded automatically each time they are due " +
                    "(when you open the app). You can also choose Repeat when adding a transaction.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.padding(padding)) {
                items(state.rows, key = { it.rule.id }) { row ->
                    val r = row.rule
                    val sign = when {
                        r.type == TransactionType.INCOME || (r.type == TransactionType.EXPENSE && r.isRefund) -> "+"
                        r.type == TransactionType.EXPENSE -> "−"
                        else -> ""
                    }
                    ListItem(
                        modifier = Modifier.clickable(enabled = !readOnly) { onOpen(r.id) },
                        leadingContent = {
                            CategoryBadge(
                                CategoryIcons.of(row.iconKey ?: "misc"),
                                row.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary,
                                null,
                            )
                        },
                        headlineContent = { Text(row.title) },
                        supportingContent = { Text(row.subtitle) },
                        trailingContent = {
                            Column {
                                Text("$sign${fmt.format(r.amount)}", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = r.active,
                                    onCheckedChange = { vm.setActive(r.id, it) },
                                    enabled = !readOnly,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Active"
                                        stateDescription = if (r.active) "Active" else "Paused"
                                    },
                                )
                            }
                        },
                    )
                }
                item {
                    Text(
                        "Paused transactions are not added. When resumed, missed dates are skipped (not added later).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
