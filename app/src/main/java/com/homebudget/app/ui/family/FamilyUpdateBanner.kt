package com.homebudget.app.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.homebudget.app.BuildConfig
import com.homebudget.app.data.settings.AppMode
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.family.FamilyMember
import com.homebudget.app.domain.family.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FamilyUpdateState(
    val member: FamilyMember? = null,
    val error: String? = null,
)

@HiltViewModel
class FamilyUpdateViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val dismissError = MutableStateFlow<String?>(null)
    val state = combine(familyRepository.observeMembers(), settingsRepository.settings, dismissError) { members, settings, error ->
        val newerMember = if (settings.appMode != AppMode.SHARED) null else {
            members.asSequence()
                .filter { it.memberId != settings.currentMemberId }
                .filter { it.appVersionCode > BuildConfig.VERSION_CODE }
                .filter { it.appVersionCode > settings.dismissedFamilyUpdateVersionCode }
                .maxByOrNull { it.appVersionCode }
        }
        FamilyUpdateState(member = newerMember, error = error)
    }.catch { error ->
        emit(FamilyUpdateState(error = error.message ?: "Family update information could not be loaded."))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUpdateState())

    fun dismiss(member: FamilyMember) {
        viewModelScope.launch {
            dismissError.value = null
            try {
                settingsRepository.dismissFamilyUpdate(member.appVersionCode)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dismissError.value = error.message ?: "The family update reminder could not be dismissed."
            }
        }
    }
}

@Composable
fun FamilyUpdateBanner(viewModel: FamilyUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    val member = state.member
    if (member != null) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val versionText = member.appVersionName.takeIf(String::isNotBlank)
                Text(
                    if (versionText == null) {
                        "${member.name} is using a newer Talenta version. Update to stay in sync."
                    } else {
                        "${member.name} is using Talenta $versionText, a newer version. Update to stay in sync."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // TODO(sync): include release notes and update URLs in member metadata.
                    TextButton(onClick = { dialogMessage = "Release notes will be available when family sync is connected." }) {
                        Text("What's new")
                    }
                    TextButton(onClick = { dialogMessage = "Update instructions will be available when family sync is connected." }) {
                        Text("How to update")
                    }
                    TextButton(onClick = { viewModel.dismiss(member) }) { Text("Later") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    } else {
        state.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    dialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogMessage = null },
            title = { Text("Family update") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { dialogMessage = null }) { Text("OK") } },
        )
    }
}
