package com.homebudget.app.ui.accounts

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.ui.common.ChoiceDialog
import com.homebudget.app.ui.common.ConfirmDialog
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.SelectField
import com.homebudget.app.ui.common.icon
import com.homebudget.app.ui.common.label
import com.homebudget.app.ui.theme.LocalFinanceColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onOpenAccount: (Long) -> Unit, vm: AccountsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val fmt = LocalMoneyFormatter.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Accounts") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::startCreate,
                // The text slot is not exposed to accessibility services in this Material3 version
                // (found by AppSmokeTest), so the label is set explicitly for TalkBack.
                modifier = Modifier.semantics { contentDescription = "Add account" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add account") },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null -> EmptyState(Icons.Filled.ErrorOutline, "Something went wrong", state.error!!, Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding)) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Total balance", style = MaterialTheme.typography.labelLarge)
                            Text(fmt.format(state.total), style = MaterialTheme.typography.headlineMedium)
                            Text("Credit-card balances owed reduce the total.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.active.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Add,
                            title = "No accounts",
                            message = "Add a cash wallet, bank account or credit card to start recording transactions.",
                        )
                    }
                }
                items(state.active, key = { it.account.id }) { row ->
                    AccountRow(row, onOpen = { onOpenAccount(row.account.id) }, onEdit = { vm.startEdit(row.account) })
                }
                if (state.archived.isNotEmpty()) {
                    item {
                        Text(
                            "Archived",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                    items(state.archived, key = { it.account.id }) { row ->
                        AccountRow(row, onOpen = { onOpenAccount(row.account.id) }, onEdit = { vm.startEdit(row.account) })
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    editor?.let { e -> AccountEditorDialog(e, vm) }
}

@Composable
private fun AccountRow(row: AccountWithBalance, onOpen: () -> Unit, onEdit: () -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val fc = LocalFinanceColors.current
    val a = row.account
    val owed = a.type == AccountType.CREDIT_CARD && row.balance < 0
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "View ${a.name} transactions", onClick = onOpen),
        leadingContent = { Icon(a.type.icon(), contentDescription = null) },
        supportingContent = {
            Text(
                buildString {
                    append(a.type.label())
                    if (!a.includeInTotal) append(" · not in total")
                },
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt.format(row.balance), color = if (row.balance < 0) fc.expense else MaterialTheme.colorScheme.onSurface)
                    if (owed) Text("owed", style = MaterialTheme.typography.labelSmall, color = fc.expense)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${a.name}") }
            }
        },
    ) { Text(a.name) }
}

@Composable
private fun AccountEditorDialog(e: AccountEditorState, vm: AccountsViewModel) {
    var pickType by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isCard = e.type == AccountType.CREDIT_CARD
    AlertDialog(
        onDismissRequest = vm::dismissEditor,
        title = { Text(if (e.isNew) "New account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = e.name,
                    onValueChange = { v -> vm.updateEditor { it.copy(name = v.take(60), nameError = null) } },
                    label = { Text("Name") },
                    isError = e.nameError != null,
                    supportingText = e.nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectField(label = "Type", value = e.type.label(), onClick = { pickType = true })
                OutlinedTextField(
                    value = e.openingText,
                    onValueChange = { v -> vm.updateEditor { it.copy(openingText = v.take(20), openingError = null) } },
                    label = { Text(if (isCard) "Amount owed when you started" else "Opening balance") },
                    isError = e.openingError != null,
                    supportingText = {
                        Text(
                            e.openingError
                                ?: if (isCard) "Enter what you owed on the card (0 if nothing). Not counted as an expense."
                                else "Balance on the day you start tracking. Not counted as income. Use - for overdraft."
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow("Include in total balance", e.includeInTotal) { v -> vm.updateEditor { it.copy(includeInTotal = v) } }
                if (!e.isNew) {
                    ToggleRow("Archived (hidden from pickers)", e.archived) { v -> vm.updateEditor { it.copy(archived = v) } }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::saveEditor) { Text("Save") } },
        dismissButton = { TextButton(onClick = vm::dismissEditor) { Text("Cancel") } },
    )
    if (pickType) {
        ChoiceDialog(
            title = "Account type",
            options = AccountType.entries,
            selected = e.type,
            label = { it.label() },
            onSelect = { t -> vm.updateEditor { it.copy(type = t) }; pickType = false },
            onDismiss = { pickType = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete account?",
            message = "Only accounts without transactions can be deleted. Accounts with history can be archived instead.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; vm.deleteEditing() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
