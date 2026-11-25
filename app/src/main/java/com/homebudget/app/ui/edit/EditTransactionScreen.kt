package com.homebudget.app.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Repeat
import com.homebudget.app.domain.recurring.RepeatOption
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.validation.TxField
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.CategorySheet
import com.homebudget.app.ui.common.ChoiceDialog
import com.homebudget.app.ui.common.ConfirmDialog
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.SelectField
import com.homebudget.app.ui.common.icon
import com.homebudget.app.ui.theme.LocalFinanceColors
import com.homebudget.app.ui.transactions.typeLabel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    onDone: () -> Unit,
    onDuplicate: (Long) -> Unit,
    onManageCategories: () -> Unit,
    onOpenBill: (Long) -> Unit,
    onOpenRecurring: (Long) -> Unit = {},
    vm: EditTransactionViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.done.collect { onDone() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.isRule -> "Repeating transaction"
                            state.isEdit -> "Edit transaction"
                            else -> "New transaction"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.isRule) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete repeating transaction")
                        }
                    } else if (state.isEdit) {
                        IconButton(onClick = { onDuplicate(state.editingId) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate transaction")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete transaction")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound -> EmptyState(
                Icons.Filled.ErrorOutline, "Transaction not found", "It may have been deleted.",
                Modifier.padding(padding),
                action = { TextButton(onClick = onDone) { Text("Go back") } },
            )
            else -> EditTransactionForm(
                state = state,
                actions = EditFormActions(
                    onType = vm::setType, onAmount = vm::setAmount, onAccount = vm::setAccount,
                    onToAccount = vm::setToAccount, onCategory = vm::setCategory, onRefund = vm::setRefund,
                    onDate = vm::setDate, onTime = vm::setTime, onNote = vm::setNote, onPayee = vm::setPayee,
                    onSave = vm::save, onManageCategories = onManageCategories, onOpenBill = onOpenBill,
                    onRepeat = vm::setRepeat, onOpenRecurring = onOpenRecurring,
                ),
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = if (state.isRule) "Delete repeating transaction?" else "Delete transaction?",
            message = if (state.isRule) "No more transactions will be added. Transactions it already added are kept."
            else "This transaction will be removed and balances recalculated. You can undo right after deleting.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

data class EditFormActions(
    val onType: (TransactionType) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onAccount: (Long) -> Unit = {},
    val onToAccount: (Long) -> Unit = {},
    val onCategory: (Long) -> Unit = {},
    val onRefund: (Boolean) -> Unit = {},
    val onDate: (java.time.LocalDate) -> Unit = {},
    val onTime: (LocalTime) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onPayee: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onManageCategories: () -> Unit = {},
    val onOpenBill: (Long) -> Unit = {},
    val onRepeat: (RepeatOption?) -> Unit = {},
    val onOpenRecurring: (Long) -> Unit = {},
)

private enum class FormDialog { ACCOUNT, TO_ACCOUNT, CATEGORY, DATE, TIME, REPEAT }

/** Stateless form, so it can be UI-tested without Hilt or a database. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionForm(state: EditFormState, actions: EditFormActions, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<FormDialog?>(null) }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TransactionType.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = state.type == t,
                    onClick = { actions.onType(t) },
                    shape = SegmentedButtonDefaults.itemShape(i, TransactionType.entries.size),
                    label = { Text(typeLabel(t)) },
                )
            }
        }

        OutlinedTextField(
            value = state.amountText,
            onValueChange = actions.onAmount,
            label = { Text("Amount") },
            prefix = { Text(state.currencySymbol) },
            isError = state.errors[TxField.AMOUNT] != null,
            supportingText = state.errors[TxField.AMOUNT]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (state.fractionDigits > 0) KeyboardType.Decimal else KeyboardType.Number),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().testTag("amount"),
        )

        if (state.type != TransactionType.TRANSFER) {
            val cat = state.category
            SelectField(
                label = "Category",
                value = state.categoryLabel,
                onClick = { dialog = FormDialog.CATEGORY },
                error = state.errors[TxField.CATEGORY],
                leading = cat?.let { { CategoryBadge(CategoryIcons.of(it.iconKey), Color(it.colorArgb), null, size = 28) } },
                modifier = Modifier.testTag("category"),
            )
        }

        SelectField(
            label = if (state.type == TransactionType.TRANSFER) "From account" else "Account",
            value = state.account?.name ?: "",
            onClick = { dialog = FormDialog.ACCOUNT },
            error = state.errors[TxField.ACCOUNT],
            leading = state.account?.let { { Icon(it.type.icon(), contentDescription = null) } },
        )

        if (state.type == TransactionType.TRANSFER) {
            SelectField(
                label = "To account",
                value = state.toAccount?.name ?: "",
                onClick = { dialog = FormDialog.TO_ACCOUNT },
                error = state.errors[TxField.TO_ACCOUNT],
                leading = state.toAccount?.let { { Icon(it.type.icon(), contentDescription = null) } },
            )
            val hint = when {
                state.toAccount?.type == AccountType.CREDIT_CARD ->
                    "Credit-card payment: recorded as a transfer. The purchases were already counted as expenses, so this is not counted again."
                else -> "Transfers move money between your accounts and are never counted as income or expense."
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.type == TransactionType.EXPENSE) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { actions.onRefund(!state.isRefund) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("This is a refund", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Money returned to you for a purchase. It increases the account balance and reduces spending in this category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.isRefund, onCheckedChange = null)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectField(
                label = if (state.repeat != null) (if (state.isRule) "Next date" else "First date") else "Date",
                value = state.date.format(DateFormats.date),
                onClick = { dialog = FormDialog.DATE },
                leading = { Icon(Icons.Filled.CalendarToday, contentDescription = null) },
                showArrow = false,
                modifier = Modifier.weight(1.2f),
            )
            SelectField(
                label = "Time",
                value = state.time.format(DateFormats.time),
                onClick = { dialog = FormDialog.TIME },
                leading = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                showArrow = false,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.canRepeat) {
            SelectField(
                label = "Repeat",
                value = state.repeat?.label ?: "Does not repeat",
                onClick = { dialog = FormDialog.REPEAT },
                leading = { Icon(Icons.Filled.Repeat, contentDescription = null) },
                modifier = Modifier.testTag("repeat"),
            )
            if (state.repeat != null) {
                Text(
                    "Each time it is due, the transaction is added automatically when you open the app. " +
                        "Manage repeating transactions in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.type != TransactionType.TRANSFER) {
            OutlinedTextField(
                value = state.payee,
                onValueChange = actions.onPayee,
                label = { Text(if (state.type == TransactionType.INCOME) "Payer (optional)" else "Payee / merchant (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.note,
            onValueChange = actions.onNote,
            label = { Text("Note (optional)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        state.billId?.let { billId ->
            AssistChip(
                onClick = { actions.onOpenBill(billId) },
                label = { Text("Created from a bill · open bill") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Text(
                "Changes made here are not copied back to the bill. To change the split, edit the bill and update its transactions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.sourceRecurringId?.let { ruleId ->
            AssistChip(
                onClick = { actions.onOpenRecurring(ruleId) },
                label = { Text("Added by a repeating transaction · manage") },
                leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }

        state.generalError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        // Save is green for income and red for expense (the type is also shown in the selector above
        // and in the button text, so color is never the only cue). Transfers keep the primary color.
        val fc = LocalFinanceColors.current
        val saveColors = when (state.type) {
            TransactionType.INCOME -> ButtonDefaults.buttonColors(containerColor = fc.income, contentColor = fc.onIncome)
            TransactionType.EXPENSE -> ButtonDefaults.buttonColors(containerColor = fc.expense, contentColor = fc.onExpense)
            TransactionType.TRANSFER -> ButtonDefaults.buttonColors()
        }
        Button(
            onClick = actions.onSave,
            enabled = !state.saving,
            colors = saveColors,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save"),
        ) {
            Text(
                when {
                    state.isRule -> "Save repeating transaction"
                    state.isEdit -> "Save changes"
                    state.repeat != null -> "Save and repeat"
                    state.type == TransactionType.INCOME -> "Save income"
                    state.type == TransactionType.EXPENSE -> "Save expense"
                    else -> "Save transfer"
                }
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }

    when (dialog) {
        FormDialog.ACCOUNT -> ChoiceDialog(
            title = if (state.type == TransactionType.TRANSFER) "From account" else "Account",
            options = state.selectableAccounts,
            selected = state.account,
            label = { it.name },
            onSelect = { actions.onAccount(it.id); dialog = null },
            onDismiss = { dialog = null },
        )
        FormDialog.TO_ACCOUNT -> ChoiceDialog(
            title = "To account",
            options = state.selectableAccounts.filter { it.id != state.accountId },
            selected = state.toAccount,
            label = { it.name },
            onSelect = { actions.onToAccount(it.id); dialog = null },
            onDismiss = { dialog = null },
        )
        FormDialog.CATEGORY -> CategorySheet(
            categories = state.selectableCategories,
            selectedId = state.categoryId,
            onSelect = { actions.onCategory(it.id); dialog = null },
            onManage = { dialog = null; actions.onManageCategories() },
            onDismiss = { dialog = null },
        )
        FormDialog.DATE -> {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let {
                            actions.onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            ) { DatePicker(state = pickerState) }
        }
        FormDialog.TIME -> {
            val timeState = rememberTimePickerState(initialHour = state.time.hour, initialMinute = state.time.minute)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Time") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = { actions.onTime(LocalTime.of(timeState.hour, timeState.minute)); dialog = null }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        FormDialog.REPEAT -> ChoiceDialog(
            title = "Repeat",
            options = (if (state.isRule) emptyList() else listOf<RepeatOption?>(null)) + RepeatOption.entries,
            selected = state.repeat,
            label = { it?.label ?: "Does not repeat" },
            onSelect = { actions.onRepeat(it); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

