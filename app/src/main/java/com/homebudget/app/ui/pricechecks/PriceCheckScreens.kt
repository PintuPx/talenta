package com.homebudget.app.ui.pricechecks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser
import com.homebudget.app.domain.report.ItemPriceHistory
import com.homebudget.app.domain.report.PriceHistory
import com.homebudget.app.domain.report.PurchasedLine
import com.homebudget.app.ui.common.LineChart
import com.homebudget.app.ui.common.LocalMoneyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

data class PriceCheck(
    val item: String,
    val priceMinor: Long,
    val quantity: String,
    val unit: String,
    val shop: String,
    val date: String,
)

data class PriceCheckState(
    val item: String = "Sample item",
    val showSheet: Boolean = false,
    val priceText: String = "",
    val quantity: String = "",
    val unit: String = "",
    val shop: String = "",
    val date: String = LocalDate.now().toString(),
    val checks: List<PriceCheck> = emptyList(),
    val error: String? = null,
)

interface PriceCheckRepository {
    fun read(): PriceCheckState
    fun write(state: PriceCheckState)
}

class InMemoryPriceCheckRepository(initial: PriceCheckState = PriceCheckState()) : PriceCheckRepository {
    private var state = initial
    override fun read(): PriceCheckState = state
    override fun write(state: PriceCheckState) { this.state = state }
}

interface ItemPriceHistorySource {
    fun forItem(itemName: String): ItemPriceHistory?
}

class InMemoryItemPriceHistorySource : ItemPriceHistorySource {
    private val items = PriceHistory.build(
        listOf(
            PurchasedLine("Sample item", 4_800, LocalDate.of(2026, 9, 1), "Shop A"),
            PurchasedLine("Sample item", 5_000, LocalDate.of(2026, 10, 1), "Shop B"),
            PurchasedLine("Sample item", 4_950, LocalDate.of(2026, 11, 1), "Shop A"),
        )
    ).associateBy { it.key }

    override fun forItem(itemName: String): ItemPriceHistory? =
        items.values.firstOrNull { it.label.equals(itemName, ignoreCase = true) }
}

class PriceCheckViewModel(
    private val repository: PriceCheckRepository = InMemoryPriceCheckRepository(),
    private val priceHistory: ItemPriceHistorySource = InMemoryItemPriceHistorySource(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<PriceCheckState> = _state.asStateFlow()
    fun historyForSelectedItem(): ItemPriceHistory? = priceHistory.forItem(_state.value.item)

    fun openSheet() = update { it.copy(showSheet = true, error = null) }
    fun closeSheet() = update { it.copy(showSheet = false, error = null) }
    fun setPrice(value: String) = update { it.copy(priceText = value.take(20), error = null) }
    fun setQuantity(value: String) = update { it.copy(quantity = value.take(20)) }
    fun setUnit(value: String) = update { it.copy(unit = value.take(20)) }
    fun setShop(value: String) = update { it.copy(shop = value.take(100)) }
    fun setDate(value: String) = update { it.copy(date = value.take(20)) }

    fun saveCheck() {
        val state = _state.value
        when (val parsed = AmountParser.parse(state.priceText, 2)) {
            is AmountParseResult.Valid -> update {
                it.copy(
                    checks = it.checks + PriceCheck(it.item, parsed.minor, it.quantity, it.unit, it.shop, it.date),
                    showSheet = false,
                    priceText = "",
                    quantity = "",
                    unit = "",
                    shop = "",
                    error = null,
                )
            }
            else -> update { it.copy(error = "Enter a valid positive price.") }
        }
    }

    private inline fun update(transform: (PriceCheckState) -> PriceCheckState) {
        _state.update { transform(it).also(repository::write) }
    }
}

@Composable
fun PriceCheckScreen(viewModel: PriceCheckViewModel = remember { PriceCheckViewModel() }) {
    val state by viewModel.state.collectAsState()
    val formatter = LocalMoneyFormatter.current
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sample item", style = MaterialTheme.typography.headlineSmall)
            MiniItemPriceChart(viewModel.historyForSelectedItem())
            Button(onClick = viewModel::openSheet) { Text("+ Add price seen") }
            Text("A price check is not a transaction and never changes spending or balances.")
            state.checks.forEach { check ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${check.item} · ${formatter.format(check.priceMinor)}")
                        Text("${check.quantity} ${check.unit} · ${check.shop} · ${check.date}")
                        Text("Price seen only · no transaction created", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (state.showSheet) AddPriceSeenSheet(state, viewModel)
}

@Composable
private fun MiniItemPriceChart(history: ItemPriceHistory?) {
    val formatter = LocalMoneyFormatter.current
    if (history == null) {
        Text("No purchase history for this item yet.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Recent purchase prices", style = MaterialTheme.typography.titleSmall)
        LineChart(
            values = history.points.map { it.amount },
            color = MaterialTheme.colorScheme.primary,
            selected = null,
            onSelect = {},
            description = "Recent purchase line amounts: ${history.points.joinToString { formatter.format(it.amount) }}",
            height = 100.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Latest ${formatter.format(history.latest)}", style = MaterialTheme.typography.bodySmall)
            Text("High ${formatter.format(history.highest)}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Line amounts from approved bills; chart is read-only.", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPriceSeenSheet(state: PriceCheckState, viewModel: PriceCheckViewModel) {
    ModalBottomSheet(onDismissRequest = viewModel::closeSheet) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add price seen", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(state.priceText, viewModel::setPrice, label = { Text("Price") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.quantity, viewModel::setQuantity, label = { Text("Quantity") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(state.unit, viewModel::setUnit, label = { Text("Unit") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(state.shop, viewModel::setShop, label = { Text("Shop") }, singleLine = true)
            OutlinedTextField(state.date, viewModel::setDate, label = { Text("Date") }, singleLine = true)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::saveCheck) { Text("Save price check") }
                TextButton(onClick = viewModel::closeSheet) { Text("Cancel") }
            }
            Text("This creates a price check only, never a transaction.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PriceCheckPreview() = PriceCheckScreen()
