package com.homebudget.app.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.SpaceTransitionRepository
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.AppSettings
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.family.FamilyRepository
import com.homebudget.app.domain.family.FamilyRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppModeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val spaceTransitions: SpaceTransitionRepository,
    private val family: FamilyRepository,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)
    val state: StateFlow<AppModeUiState> = combine(settings.settings, error, family.observeMembers()) { current, message, members ->
        val isAdmin = members.any { it.memberId == current.currentMemberId && it.role == FamilyRole.ADMIN }
        AppModeUiState(current, message, isAdmin)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppModeUiState())

    fun chooseIndividual() = changeMode {
        settings.setPrivateMode(false)
        settings.setAppMode(AppMode.PERSONAL)
    }

    fun enterFamily(shareExisting: Boolean, onComplete: () -> Unit = {}) = changeMode(onComplete) {
        if (!shareExisting) spaceTransitions.makeExistingSharedRowsPrivate()
        settings.setPrivateMode(!shareExisting)
        settings.setAppMode(AppMode.SHARED)
    }

    fun switchToIndividual() = changeMode {
        val current = family.currentMember()
        check(current?.role == FamilyRole.ADMIN) { "Only the Admin can switch Family mode to Individual." }
        settings.setPrivateMode(false)
        settings.setAppMode(AppMode.PERSONAL)
    }

    private fun changeMode(onComplete: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = null
            try {
                action()
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "The mode could not be changed."
            }
        }
    }
}

data class AppModeUiState(
    val settings: AppSettings? = null,
    val error: String? = null,
    val isAdmin: Boolean = false,
)

@Composable
fun AppModeScreen(
    firstLaunch: Boolean,
    onBack: (() -> Unit)? = null,
    onOpenFamilySetup: () -> Unit = {},
    viewModel: AppModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chooseFamily by remember { mutableStateOf(false) }
    var confirmIndividual by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (firstLaunch) "How will you use Talenta?" else "Mode",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (firstLaunch) {
                Text("Choose Individual to keep using Talenta on this phone, or Family to prepare a shared household.")
                Button(onClick = viewModel::chooseIndividual, modifier = Modifier.fillMaxWidth()) {
                    Text("Individual")
                }
                OutlinedButton(onClick = { chooseFamily = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Family")
                }
            } else {
                Text("Current mode: ${state.settings?.appMode?.label ?: AppMode.UNSET.label}")
                Text("Family syncing is not connected yet; Family mode currently stays on this phone.")
                when (state.settings?.appMode) {
                    AppMode.PERSONAL -> OutlinedButton(onClick = { chooseFamily = true }) { Text("Switch to Family") }
                    AppMode.SHARED -> {
                        OutlinedButton(onClick = onOpenFamilySetup) { Text("Manage family") }
                        if (state.isAdmin) {
                            OutlinedButton(onClick = { confirmIndividual = true }) { Text("Switch to Individual") }
                        }
                    }
                    else -> Unit
                }
                onBack?.let { TextButton(onClick = it) { Text("Back") } }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (chooseFamily) {
        AlertDialog(
            onDismissRequest = { chooseFamily = false },
            title = { Text("Set up Family mode?") },
            text = { Text("Share existing entries with the family, or keep them private on this phone?") },
            confirmButton = {
                TextButton(onClick = {
                    chooseFamily = false
                    viewModel.enterFamily(shareExisting = true, onComplete = onOpenFamilySetup)
                }) {
                    Text("Share existing")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    chooseFamily = false
                    viewModel.enterFamily(shareExisting = false, onComplete = onOpenFamilySetup)
                }) {
                    Text("Keep private")
                }
            },
        )
    }

    if (confirmIndividual) {
        AlertDialog(
            onDismissRequest = { confirmIndividual = false },
            title = { Text("Switch to Individual?") },
            text = { Text("Family sharing will stop. This prototype has no Drive connection to revoke.") },
            confirmButton = {
                TextButton(onClick = { confirmIndividual = false; viewModel.switchToIndividual() }) {
                    Text("Switch mode")
                }
            },
            dismissButton = { TextButton(onClick = { confirmIndividual = false }) { Text("Cancel") } },
        )
    }
}
