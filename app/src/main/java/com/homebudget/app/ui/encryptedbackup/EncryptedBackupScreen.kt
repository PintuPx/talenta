package com.homebudget.app.ui.encryptedbackup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackupDialog {
    PASSWORD,
    UNPROTECTED_WARNING,
}

data class EncryptedBackupState(
    val dialog: BackupDialog? = null,
    val password: String = "",
    val confirmation: String = "",
    val message: String? = null,
)

interface EncryptedBackupRepository {
    fun initialState(): EncryptedBackupState
}

class InMemoryEncryptedBackupRepository(
    private val state: EncryptedBackupState = EncryptedBackupState(),
) : EncryptedBackupRepository {
    override fun initialState(): EncryptedBackupState = state
}

class EncryptedBackupViewModel(
    repository: EncryptedBackupRepository = InMemoryEncryptedBackupRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.initialState())
    val state: StateFlow<EncryptedBackupState> = _state.asStateFlow()

    fun openPasswordDialog() {
        _state.value = _state.value.copy(
            dialog = BackupDialog.PASSWORD,
            password = "",
            confirmation = "",
            message = null,
        )
    }

    fun openUnprotectedWarning() {
        _state.value = _state.value.copy(
            dialog = BackupDialog.UNPROTECTED_WARNING,
            password = "",
            confirmation = "",
            message = null,
        )
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(password = value, message = null)
    }

    fun setConfirmation(value: String) {
        _state.value = _state.value.copy(confirmation = value, message = null)
    }

    fun confirmPasswordSave() {
        val current = _state.value
        when {
            current.password.isBlank() -> _state.value = current.copy(message = "Enter a password.")
            current.password != current.confirmation ->
                _state.value = current.copy(message = "Passwords do not match.")
            else -> _state.value = current.copy(
                dialog = null,
                password = "",
                confirmation = "",
                message = "Sample only: no backup was created.",
            )
        }
    }

    fun confirmUnprotectedSave() {
        _state.value = _state.value.copy(
            dialog = null,
            message = "Sample only: no backup was created.",
        )
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(
            dialog = null,
            password = "",
            confirmation = "",
        )
    }
}

private fun passwordStrength(password: String): String = when {
    password.isEmpty() -> "Password strength: enter a password"
    password.length < 8 -> "Password strength: weak"
    password.length < 12 -> "Password strength: fair"
    else -> "Password strength: stronger"
}

@Composable
fun EncryptedBackupScreen(
    viewModel: EncryptedBackupViewModel = remember { EncryptedBackupViewModel() },
) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Encrypted backup", style = MaterialTheme.typography.headlineSmall)
            Text("Prototype only. No backup file or encryption is created.")
            Button(onClick = viewModel::openPasswordDialog) {
                Text("Save with password")
            }
            TextButton(onClick = viewModel::openUnprotectedWarning) {
                Text("Save without password")
            }
            state.message?.let { Text(it) }
        }
    }

    when (state.dialog) {
        BackupDialog.PASSWORD -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("Set a backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Text(passwordStrength(state.password))
                    OutlinedTextField(
                        value = state.confirmation,
                        onValueChange = viewModel::setConfirmation,
                        label = { Text("Confirm password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Text("If you lose this password, the backup can't be opened.")
                    state.message?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPasswordSave) { Text("Save backup") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") }
            },
        )
        BackupDialog.UNPROTECTED_WARNING -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("Save without a password?") },
            text = { Text("Anyone who gets this backup file could read its contents.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUnprotectedSave) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") }
            },
        )
        null -> Unit
    }
}

@Preview(showBackground = true)
@Composable
private fun EncryptedBackupPreview() = EncryptedBackupScreen()
