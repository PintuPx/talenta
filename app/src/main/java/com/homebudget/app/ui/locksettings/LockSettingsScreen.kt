package com.homebudget.app.ui.locksettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

data class LockSettingsState(val askForDeviceLock: Boolean = true)

interface LockSettingsRepository {
    fun initialState(): LockSettingsState
}

class InMemoryLockSettingsRepository(
    private val state: LockSettingsState = LockSettingsState(),
) : LockSettingsRepository {
    override fun initialState(): LockSettingsState = state
}

class LockSettingsViewModel(
    repository: LockSettingsRepository = InMemoryLockSettingsRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.initialState())
    val state: StateFlow<LockSettingsState> = _state.asStateFlow()

    fun setAskForDeviceLock(enabled: Boolean) {
        _state.value = _state.value.copy(askForDeviceLock = enabled)
    }
}

@Composable
fun LockSettingsScreen(viewModel: LockSettingsViewModel = remember { LockSettingsViewModel() }) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Lock settings", style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Ask for fingerprint or phone PIN when turning on Private mode",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("On by default. This prototype does not show a biometric prompt.")
                }
                Switch(
                    checked = state.askForDeviceLock,
                    onCheckedChange = viewModel::setAskForDeviceLock,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LockSettingsPreview() = LockSettingsScreen()
