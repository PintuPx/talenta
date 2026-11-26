package com.homebudget.app.ui.privateMode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.flow.update

data class PrivateModeState(
    val enabled: Boolean = false,
    val lockPrompt: Boolean = false,
    val entryPrivate: Boolean = false,
    val entryConfirmation: Boolean? = null,
)

interface PrivateModeRepository {
    fun read(): PrivateModeState
    fun write(state: PrivateModeState)
}

class InMemoryPrivateModeRepository(initial: PrivateModeState = PrivateModeState()) : PrivateModeRepository {
    private var state = initial
    override fun read(): PrivateModeState = state
    override fun write(state: PrivateModeState) { this.state = state }
}

class PrivateModeViewModel(
    private val repository: PrivateModeRepository = InMemoryPrivateModeRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<PrivateModeState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) = update {
        if (enabled) it.copy(lockPrompt = true) else it.copy(enabled = false, lockPrompt = false)
    }

    fun confirmLockPlaceholder() = update { it.copy(enabled = true, lockPrompt = false) }
    fun dismissLockPlaceholder() = update { it.copy(lockPrompt = false) }
    fun requestEntryPrivacy(makePrivate: Boolean) = update { it.copy(entryConfirmation = makePrivate) }
    fun dismissEntryPrivacy() = update { it.copy(entryConfirmation = null) }
    fun confirmEntryPrivacy() = update {
        it.copy(entryPrivate = it.entryConfirmation ?: it.entryPrivate, entryConfirmation = null)
    }

    private inline fun update(transform: (PrivateModeState) -> PrivateModeState) {
        _state.update { transform(it).also(repository::write) }
    }
}

@Composable
fun PrivateModeScreen(viewModel: PrivateModeViewModel = remember { PrivateModeViewModel() }) {
    val state by viewModel.state.collectAsState()
    PrivateModeContent(
        state = state,
        onSetEnabled = viewModel::setEnabled,
        onRequestEntryPrivacy = viewModel::requestEntryPrivacy,
        onConfirmLock = viewModel::confirmLockPlaceholder,
        onDismissLock = viewModel::dismissLockPlaceholder,
        onConfirmEntry = viewModel::confirmEntryPrivacy,
        onDismissEntry = viewModel::dismissEntryPrivacy,
    )
}

@Composable
private fun PrivateModeContent(
    state: PrivateModeState,
    onSetEnabled: (Boolean) -> Unit,
    onRequestEntryPrivacy: (Boolean) -> Unit,
    onConfirmLock: () -> Unit,
    onDismissLock: () -> Unit,
    onConfirmEntry: () -> Unit,
    onDismissEntry: () -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Private mode", style = MaterialTheme.typography.headlineSmall)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (state.enabled) "Private mode is on" else "Private mode is off", style = MaterialTheme.typography.titleMedium)
                Text("Private entries stay on this phone and are not included in family totals.")
            }
            Switch(checked = state.enabled, onCheckedChange = onSetEnabled)
        }
        if (state.enabled) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "Private mode is on")
                    Text("Private mode is on. New private entries stay on this phone.")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample entry", style = MaterialTheme.typography.titleMedium)
                Text(if (state.entryPrivate) "This entry is private." else "This entry is shared.")
                Button(onClick = { onRequestEntryPrivacy(!state.entryPrivate) }) {
                    Icon(
                        if (state.entryPrivate) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        contentDescription = null,
                    )
                    Text(if (state.entryPrivate) "Make shared" else "Make private")
                }
            }
        }
    }

    if (state.lockPrompt) {
        AlertDialog(
            onDismissRequest = onDismissLock,
            title = { Text("Confirm it's you") },
            text = { Text("Fingerprint or phone PIN confirmation is a placeholder and is not connected in this prototype.") },
            confirmButton = {
                TextButton(onClick = onConfirmLock) { Text("Continue (prototype)") }
            },
            dismissButton = {
                TextButton(onClick = onDismissLock) { Text("Cancel") }
            },
        )
    }

    state.entryConfirmation?.let { makePrivate ->
        AlertDialog(
            onDismissRequest = onDismissEntry,
            title = { Text(if (makePrivate) "Make this entry private?" else "Make this entry shared?") },
            text = {
                Text(
                    if (makePrivate) {
                        "This entry will stay on this phone, won't be shared or synced, and won't count in family totals."
                    } else {
                        "This entry will be shared with family members and included in family totals."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmEntry) {
                    Text(if (makePrivate) "Make private" else "Make shared")
                }
            },
            dismissButton = { TextButton(onClick = onDismissEntry) { Text("Cancel") } },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PrivateModeOffPreview() = PrivateModePreviewContent(PrivateModeState())

@Preview(showBackground = true)
@Composable
private fun PrivateModeOnPreview() =
    PrivateModePreviewContent(PrivateModeState(enabled = true, entryPrivate = true))

@Composable
private fun PrivateModePreviewContent(state: PrivateModeState) =
    PrivateModeContent(
        state = state,
        onSetEnabled = {},
        onRequestEntryPrivacy = {},
        onConfirmLock = {},
        onDismissLock = {},
        onConfirmEntry = {},
        onDismissEntry = {},
    )
