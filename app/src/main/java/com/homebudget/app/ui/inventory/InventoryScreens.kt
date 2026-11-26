package com.homebudget.app.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StockItem(val id: String, val name: String, val quantityLabel: String, val lowStock: Boolean)
enum class RequestStatus { PENDING, APPROVED, BOUGHT }
data class PurchaseRequest(val id: String, val itemName: String, val status: RequestStatus)

data class InventoryState(
    val items: List<StockItem> = listOf(
        StockItem("stock-a", "Staples", "Low", true),
        StockItem("stock-b", "Household item", "In stock", false),
    ),
    val requests: List<PurchaseRequest> = listOf(
        PurchaseRequest("request-a", "Staples", RequestStatus.PENDING),
        PurchaseRequest("request-b", "Household item", RequestStatus.APPROVED),
    ),
)

interface InventoryRepository {
    fun read(): InventoryState
    fun write(state: InventoryState)
}

class InMemoryInventoryRepository(initial: InventoryState = InventoryState()) : InventoryRepository {
    private var state = initial
    override fun read(): InventoryState = state
    override fun write(state: InventoryState) { this.state = state }
}

class InventoryViewModel(
    private val repository: InventoryRepository = InMemoryInventoryRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<InventoryState> = _state.asStateFlow()

    fun approve(id: String) = update { state ->
        state.copy(requests = state.requests.map { if (it.id == id) it.copy(status = RequestStatus.APPROVED) else it })
    }

    fun markBought(id: String) = update { state ->
        val request = state.requests.firstOrNull { it.id == id } ?: return@update state
        val exists = state.items.any { it.name.equals(request.itemName, ignoreCase = true) }
        state.copy(
            items = if (exists) state.items else state.items + StockItem("stock-${state.items.size + 1}", request.itemName, "Bought", false),
            requests = state.requests.map { if (it.id == id) it.copy(status = RequestStatus.BOUGHT) else it },
        )
    }

    private inline fun update(transform: (InventoryState) -> InventoryState) {
        _state.update { transform(it).also(repository::write) }
    }
}

@Composable
fun InventoryScreen(viewModel: InventoryViewModel = remember { InventoryViewModel() }) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inventory", style = MaterialTheme.typography.headlineSmall)
                    Text("Sample stock list · changes are not saved.")
                    state.items.forEach { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                    Text(item.quantityLabel)
                                }
                                if (item.lowStock) {
                                    Text(
                                        "Low stock",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    Text("Purchase requests", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(state.requests, key = { it.id }) { request ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(request.itemName, style = MaterialTheme.typography.titleSmall)
                        Text("Status: ${request.status.label()}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (request.status == RequestStatus.PENDING) {
                                TextButton(onClick = { viewModel.approve(request.id) }) { Text("Approve") }
                            }
                            if (request.status == RequestStatus.APPROVED) {
                                Button(onClick = { viewModel.markBought(request.id) }) { Text("Mark bought") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShoppingListScreen(viewModel: InventoryViewModel = remember { InventoryViewModel() }) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shopping requests", style = MaterialTheme.typography.headlineSmall)
        state.requests.forEach { request ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(request.itemName)
                        Text(request.status.label())
                    }
                    if (request.status == RequestStatus.APPROVED) {
                        Button(onClick = { viewModel.markBought(request.id) }) { Text("Mark bought") }
                    }
                }
            }
        }
    }
}

private fun RequestStatus.label() = when (this) {
    RequestStatus.PENDING -> "Pending"
    RequestStatus.APPROVED -> "Approved"
    RequestStatus.BOUGHT -> "Bought"
}

@Preview(showBackground = true)
@Composable
private fun InventoryPreview() = InventoryScreen()

@Preview(showBackground = true)
@Composable
private fun ShoppingListPreview() = ShoppingListScreen()
