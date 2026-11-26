package com.homebudget.app.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser
import com.homebudget.app.ui.common.LocalMoneyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class InvestmentState(
    val accountTypeSelected: Boolean = false,
    val showValueEditor: Boolean = false,
    val valueText: String = "",
    val currentValueMinor: Long = 1_250_000,
    val investedMinor: Long = 1_100_000,
    val investedThisMonthMinor: Long = 100_000,
    val error: String? = null,
)

interface InvestmentRepository {
    fun read(): InvestmentState
    fun write(state: InvestmentState)
}

class InMemoryInvestmentRepository(initial: InvestmentState = InvestmentState()) : InvestmentRepository {
    private var state = initial
    override fun read(): InvestmentState = state
    override fun write(state: InvestmentState) { this.state = state }
}

class InvestmentViewModel(
    private val repository: InvestmentRepository = InMemoryInvestmentRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<InvestmentState> = _state.asStateFlow()

    fun selectInvestmentAccount(selected: Boolean) = update { it.copy(accountTypeSelected = selected) }
    fun openValueEditor() = update { it.copy(showValueEditor = true, valueText = "", error = null) }
    fun closeValueEditor() = update { it.copy(showValueEditor = false, error = null) }
    fun setValueText(value: String) = update { it.copy(valueText = value.take(20), error = null) }
    fun saveValue(fractionDigits: Int) {
        when (val parsed = AmountParser.parse(_state.value.valueText, fractionDigits)) {
            is AmountParseResult.Valid -> update {
                it.copy(
                    currentValueMinor = parsed.minor,
                    showValueEditor = false,
                    error = null,
                )
            }
            else -> update { it.copy(error = "Enter a valid positive amount.") }
        }
    }

    private inline fun update(transform: (InvestmentState) -> InvestmentState) {
        _state.update { transform(it).also(repository::write) }
    }
}

@Composable
fun InvestmentScreen(viewModel: InvestmentViewModel = remember { InvestmentViewModel() }) {
    val state by viewModel.state.collectAsState()
    InvestmentContent(
        state = state,
        onSelectAccountType = viewModel::selectInvestmentAccount,
        onOpenValueEditor = viewModel::openValueEditor,
        onCloseValueEditor = viewModel::closeValueEditor,
        onValueTextChange = viewModel::setValueText,
        onSaveValue = viewModel::saveValue,
    )
}

@Composable
private fun InvestmentContent(
    state: InvestmentState,
    onSelectAccountType: (Boolean) -> Unit,
    onOpenValueEditor: () -> Unit,
    onCloseValueEditor: () -> Unit,
    onValueTextChange: (String) -> Unit,
    onSaveValue: (Int) -> Unit,
) {
    val formatter = LocalMoneyFormatter.current
    val gainLoss = com.homebudget.app.domain.finance.FinanceRules.investmentGain(state.currentValueMinor, state.investedMinor)
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Investments", style = MaterialTheme.typography.headlineSmall)
            FilterChip(
                selected = state.accountTypeSelected,
                onClick = { onSelectAccountType(!state.accountTypeSelected) },
                label = { Text("Investment account type") },
            )
            if (state.accountTypeSelected) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Investment account", style = MaterialTheme.typography.titleMedium)
                        Text("Adding money is a transfer and is not counted as spending.")
                        Text("Current value: ${formatter.format(state.currentValueMinor)}")
                        Text(
                            "${if (gainLoss >= 0) "Gain" else "Loss"}: ${formatter.format(kotlin.math.abs(gainLoss))}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onOpenValueEditor) { Text("Update current value") }
                    }
                }
            } else {
                Text("Choose Investment in the account form to see this preview.")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Invested this month", style = MaterialTheme.typography.titleMedium)
                    Text(formatter.format(state.investedThisMonthMinor))
                    Text("Prototype values only; no account or transaction is changed.")
                }
            }
        }
    }
    if (state.showValueEditor) {
        AlertDialog(
            onDismissRequest = onCloseValueEditor,
            title = { Text("Update current value") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.valueText,
                        onValueChange = onValueTextChange,
                        label = { Text("Current value") },
                        singleLine = true,
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSaveValue(formatter.fractionDigits) }) { Text("Save preview") }
            },
            dismissButton = { TextButton(onClick = onCloseValueEditor) { Text("Cancel") } },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun InvestmentPreview() =
    InvestmentContent(
        state = InvestmentState(accountTypeSelected = true),
        onSelectAccountType = {},
        onOpenValueEditor = {},
        onCloseValueEditor = {},
        onValueTextChange = {},
        onSaveValue = {},
    )
