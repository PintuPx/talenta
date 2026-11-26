package com.homebudget.app.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import com.homebudget.app.FeatureFlags
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.monthclose.MonthLockRepository
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.money.MoneyFormatter
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
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

data class RecurringRow(
    val rule: RecurringRule,
    val title: String,
    val subtitle: String,
    val iconKey: String?,
    val colorArgb: Long?,
)

data class DueBillRow(
    val rule: RecurringRule,
    val title: String,
    val occurrence: LocalDateTime,
    val monthClosed: Boolean,
)

data class PaidBillRow(val title: String, val transaction: Transaction)

data class RecurringUiState(
    val loading: Boolean = true,
    val rows: List<RecurringRow> = emptyList(),
    val dueBills: List<DueBillRow> = emptyList(),
    val paidBills: List<PaidBillRow> = emptyList(),
    val selectedDueRuleId: Long? = null,
    val amountText: String = "",
    val dueError: String? = null,
    val isAdmin: Boolean = false,
    val canRecordDue: Boolean = true,
    val savingDue: Boolean = false,
)

private data class RecurringDueDialogState(
    val ruleId: Long?,
    val amountText: String,
    val error: String?,
    val isAdmin: Boolean,
    val canRecordDue: Boolean,
    val saving: Boolean,
)

private data class RecurringPermissions(val isAdmin: Boolean, val canRecordDue: Boolean, val saving: Boolean)

private data class RecurringSources(
    val rules: List<RecurringRule>,
    val transactions: List<Transaction>,
    val accounts: List<com.homebudget.app.domain.model.Account>,
    val categories: List<com.homebudget.app.domain.model.Category>,
)

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurring: RecurringRepository,
    transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val monthLocks: MonthLockRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _dialogRuleId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    private val _amountText = kotlinx.coroutines.flow.MutableStateFlow("")
    private val _dueError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val _isAdmin = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _canRecordDue = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val _savingDue = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val baseState = combine(
        combine(
            recurring.observeRules(), transactions.observeAll(), accounts.observeAccounts(), categories.observeCategories(),
        ) { rules, txs, accs, cats -> RecurringSources(rules, txs, accs, cats) },
        monthLocks.observeLocks(),
    ) { data, locks ->
        val rules = data.rules
        val txs = data.transactions
        val accs = data.accounts
        val cats = data.categories
        val accById = accs.associateBy { it.id }
        val catById = cats.associateBy { it.id }
        val ruleById = rules.associateBy { it.id }
        val lockedMonths = locks.mapTo(HashSet()) { it.month }
        val now = LocalDateTime.now(clock)
        // Not gated by CHANGING_BILL_AMOUNTS: postDue() leaves fixed occurrences in a closed month (and any
        // amount-changes rule restored from a backup) for the user to resolve here, so hiding this list
        // would stop those rules from ever posting again.
        val dueBills = rules.mapNotNull { rule ->
            if (!rule.active) return@mapNotNull null
            val occurrence = Recurrence.nextDue(rule)
            if (occurrence > now) return@mapNotNull null
            val monthClosed = YearMonth.from(occurrence) in lockedMonths
            if (!rule.amountChanges && !monthClosed) return@mapNotNull null
            val title = rule.payee.ifBlank { catById.pathLabel(rule.categoryId) ?: "Repeating bill" }
            DueBillRow(rule, title, occurrence, monthClosed)
        }.sortedBy { it.occurrence }
        val paidBills = if (!FeatureFlags.CHANGING_BILL_AMOUNTS) emptyList() else txs.asSequence()
            .filter { tx -> tx.recurringId?.let { ruleById[it]?.amountChanges } == true }
            .sortedByDescending { it.occurredAt }
            .take(20)
            .map { tx ->
                val rule = tx.recurringId?.let(ruleById::get)
                PaidBillRow(rule?.payee?.ifBlank { catById.pathLabel(rule.categoryId) ?: "Repeating bill" } ?: "Repeating bill", tx)
            }
            .toList()
        RecurringUiState(
            loading = false,
            dueBills = dueBills,
            paidBills = paidBills,
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
    }

    private val permissions = combine(_isAdmin, _canRecordDue, _savingDue) { isAdmin, canRecordDue, saving ->
        RecurringPermissions(isAdmin, canRecordDue, saving)
    }

    private val dialogState = combine(
        _dialogRuleId,
        _amountText,
        _dueError,
        permissions,
    ) { selectedDueRuleId, amountText, dueError, permissions ->
        RecurringDueDialogState(
            selectedDueRuleId,
            amountText,
            dueError,
            permissions.isAdmin,
            permissions.canRecordDue,
            permissions.saving,
        )
    }

    val state: StateFlow<RecurringUiState> = combine(baseState, dialogState) { base, dialog ->
        base.copy(
            selectedDueRuleId = dialog.ruleId,
            amountText = dialog.amountText,
            dueError = dialog.error,
            isAdmin = dialog.isAdmin,
            canRecordDue = dialog.canRecordDue,
            savingDue = dialog.saving,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    init {
        viewModelScope.launch {
            _isAdmin.value = recurring.isCurrentUserAdmin()
        }
        viewModelScope.launch {
            recurring.observeCanRecordDueInCurrentSpace().collect { _canRecordDue.value = it }
        }
    }

    fun setActive(id: Long, active: Boolean) = viewModelScope.launch { recurring.setActive(id, active) }

    fun beginDue(ruleId: Long) {
        val rule = state.value.dueBills.firstOrNull { it.rule.id == ruleId }?.rule ?: return
        _dialogRuleId.value = ruleId
        _amountText.value = Money.toEditableText(rule.amount)
        _dueError.value = null
    }

    fun setAmountText(value: String) { _amountText.value = value.take(20); _dueError.value = null }
    fun dismissDue() {
        if (_savingDue.value) return
        _dialogRuleId.value = null
        _dueError.value = null
    }

    fun confirmDue(useCurrentMonth: Boolean = false) = viewModelScope.launch {
        if (_savingDue.value) return@launch
        val due = state.value.dueBills.firstOrNull { it.rule.id == _dialogRuleId.value } ?: return@launch
        _savingDue.value = true
        try {
            val amount = if (due.rule.amountChanges) {
                val fractionDigits = MoneyFormatter(settings.settings.first().currencyCode).fractionDigits
                when (val parsed = AmountParser.parse(_amountText.value, fractionDigits)) {
                    is AmountParseResult.Valid -> parsed.minor
                    else -> {
                        _dueError.value = "Enter a valid positive amount."
                        return@launch
                    }
                }
            } else {
                null
            }
            recurring.postDueOccurrence(due.rule.id, amount, useCurrentMonth)
            _dialogRuleId.value = null
            _dueError.value = null
        } catch (e: IntegrityException) {
            _dueError.value = e.message ?: "The bill could not be recorded."
        } finally {
            _savingDue.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onScanBill: () -> Unit = {},
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
                if (state.dueBills.isNotEmpty()) {
                    item { Text("Bills due", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
                    items(state.dueBills, key = { "due-${it.rule.id}" }) { due ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(due.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Due ${due.occurrence.toLocalDate().format(DateFormats.date)}" +
                                        if (due.monthClosed) " · month closed" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (due.rule.amountChanges) "Enter the amount; it will not be recorded until you confirm."
                                    else "This fixed amount was not posted because its month is closed.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!state.canRecordDue) {
                                    Text("Only the Family Admin can record shared repeating payments.", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { vm.beginDue(due.rule.id) },
                                        enabled = !readOnly && state.canRecordDue && !state.savingDue,
                                    ) {
                                        Text(if (due.rule.amountChanges) "Enter amount" else "Record payment")
                                    }
                                    if (due.rule.amountChanges) {
                                        TextButton(onClick = onScanBill, enabled = !readOnly && state.canRecordDue && !state.savingDue) {
                                            Text("Scan bill")
                                        }
                                    }
                                }
                                if (due.rule.amountChanges) {
                                    Text(
                                        "Bill scanning creates separate expenses. Do not approve those expenses if you will record this payment here.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.paidBills.isNotEmpty()) {
                    item { Text("Paid bills", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
                    items(state.paidBills, key = { "paid-${it.transaction.id}" }) { paid ->
                        ListItem(
                            headlineContent = { Text(paid.title) },
                            supportingContent = { Text("Paid ${paid.transaction.occurredAt.toLocalDate().format(DateFormats.date)}") },
                            trailingContent = { Text(fmt.format(paid.transaction.amount)) },
                        )
                    }
                }
                item { Text("Repeating rules", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
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
                        supportingContent = {
                            Text(row.subtitle + if (FeatureFlags.CHANGING_BILL_AMOUNTS && r.amountChanges) " · amount entered when due" else "")
                        },
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
        state.selectedDueRuleId?.let { selectedId ->
            val due = state.dueBills.firstOrNull { it.rule.id == selectedId }
            if (due != null) {
                AlertDialog(
                    onDismissRequest = vm::dismissDue,
                    title = { Text(if (due.rule.amountChanges) "${due.title} due" else "Record ${due.title}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (due.rule.amountChanges) {
                                OutlinedTextField(
                                    value = state.amountText,
                                    onValueChange = vm::setAmountText,
                                    label = { Text("Amount") },
                                    enabled = !state.savingDue,
                                    singleLine = true,
                                )
                            } else {
                                Text("Record ${fmt.format(due.rule.amount)} for this occurrence?")
                            }
                            if (due.monthClosed) {
                                Text(
                                    if (state.isAdmin) "This month is closed. Reopening it lets you keep the due date."
                                    else "This month is closed. Only the Admin can reopen it; you can record this in the current month instead.",
                                )
                            }
                            state.dueError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    confirmButton = {
                        if (due.monthClosed) {
                            Row {
                                if (state.isAdmin) {
                                    TextButton(onClick = { vm.confirmDue(useCurrentMonth = false) }, enabled = !state.savingDue) {
                                        Text("Add to due month")
                                    }
                                }
                                TextButton(onClick = { vm.confirmDue(useCurrentMonth = true) }, enabled = !state.savingDue) {
                                    Text("Add to current month")
                                }
                            }
                        } else {
                            TextButton(onClick = { vm.confirmDue() }, enabled = !state.savingDue) { Text("Confirm") }
                        }
                    },
                    dismissButton = { TextButton(onClick = vm::dismissDue, enabled = !state.savingDue) { Text("Cancel") } },
                )
            }
        }
    }
}
