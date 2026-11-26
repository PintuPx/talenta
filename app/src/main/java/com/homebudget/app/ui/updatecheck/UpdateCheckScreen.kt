package com.homebudget.app.ui.updatecheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UpdateCheckState(
    val enabled: Boolean = false,
    val showNewVersionDialog: Boolean = false,
)

interface UpdateCheckRepository {
    fun initialState(): UpdateCheckState
}

class InMemoryUpdateCheckRepository(
    private val state: UpdateCheckState = UpdateCheckState(),
) : UpdateCheckRepository {
    override fun initialState(): UpdateCheckState = state
}

class UpdateCheckViewModel(
    repository: UpdateCheckRepository = InMemoryUpdateCheckRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.initialState())
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun showSampleResult() {
        if (_state.value.enabled) {
            _state.value = _state.value.copy(showNewVersionDialog = true)
        }
    }

    fun dismissResult() {
        _state.value = _state.value.copy(showNewVersionDialog = false)
    }
}

@Composable
fun UpdateCheckScreen(viewModel: UpdateCheckViewModel = remember { UpdateCheckViewModel() }) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Check for updates", style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates", style = MaterialTheme.typography.titleMedium)
                    Text("When enabled, update checks connect to GitHub. Off by default.")
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
            }
            Text("This prototype does not connect to GitHub or download updates.")
            if (state.enabled) {
                Button(onClick = viewModel::showSampleResult) {
                    Text("Check now")
                }
            }
        }
    }
    if (state.showNewVersionDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissResult,
            title = { Text("New version available") },
            text = { Text("Example only: Talenta 0.2.0. No update was checked or downloaded.") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissResult) { Text("OK") }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UpdateCheckPreview() = UpdateCheckScreen()
