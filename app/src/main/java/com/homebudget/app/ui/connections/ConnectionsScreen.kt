package com.homebudget.app.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

data class ConnectionInfo(val name: String, val trigger: String, val stateLabel: String)

data class ConnectionsState(
    val items: List<ConnectionInfo> = listOf(
        ConnectionInfo("Issue reporting", "When you tap Report a bug; opens your browser", "Browser hand-off only"),
        ConnectionInfo("Family sync", "Only after a family member starts syncing", "Not connected"),
        ConnectionInfo("Archive", "Only when you start an archive action", "Not connected"),
        ConnectionInfo("Update check", "Only when you request a check", "Not connected"),
    ),
)

interface ConnectionsRepository {
    fun read(): ConnectionsState
}

class InMemoryConnectionsRepository(
    private val state: ConnectionsState = ConnectionsState(),
) : ConnectionsRepository {
    override fun read(): ConnectionsState = state
}

class ConnectionsViewModel(repository: ConnectionsRepository = InMemoryConnectionsRepository()) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()
}

@Composable
fun ConnectionsScreen(viewModel: ConnectionsViewModel = remember { ConnectionsViewModel() }) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Connections", style = MaterialTheme.typography.headlineSmall)
                    Text("Placeholder information only. This screen does not open or manage connections.")
                }
            }
            items(state.items, key = { it.name }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text("When: ${item.trigger}")
                        Text(item.stateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("No connection is active from this prototype.") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionsPreview() = ConnectionsScreen()
