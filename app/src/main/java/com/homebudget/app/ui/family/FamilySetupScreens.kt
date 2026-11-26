package com.homebudget.app.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.domain.family.FamilyMember
import com.homebudget.app.domain.family.FamilyRepository
import com.homebudget.app.domain.family.FamilyRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FamilyMemberDraft(
    val memberId: String = "",
    val name: String = "",
    val email: String = "",
    val role: FamilyRole = FamilyRole.REVIEWER,
    val appVersionCode: Int = 0,
    val appVersionName: String = "",
    val syncFormat: Int = 1,
    val lastSeenAt: Long? = null,
)

data class FamilySetupState(
    val members: List<FamilyMemberDraft> = emptyList(),
    val currentMemberId: String? = null,
    val isAdmin: Boolean = false,
    val canManageRoles: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class FamilySetupViewModel @Inject constructor(
    private val repository: FamilyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FamilySetupState())
    val state: StateFlow<FamilySetupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val current = repository.currentMember()
                _state.update {
                    it.copy(
                        currentMemberId = current?.memberId,
                        isAdmin = current?.role == FamilyRole.ADMIN,
                        canManageRoles = current == null || current.role == FamilyRole.ADMIN,
                    )
                }
                repository.observeMembers()
                .catch { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(error = error.message ?: "Could not load family members.") }
                }
                .collect { members ->
                    _state.update { state ->
                        val currentId = state.currentMemberId ?: members.firstOrNull {
                            it.role == FamilyRole.ADMIN
                        }?.memberId
                        val currentRole = members.firstOrNull { it.memberId == currentId }?.role
                        state.copy(
                            members = members.map { it.toDraft() },
                            currentMemberId = currentId,
                            isAdmin = currentRole == FamilyRole.ADMIN,
                            canManageRoles = currentRole == FamilyRole.ADMIN || currentId == null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Could not load family members.") }
            }
        }
    }

    fun addMember() = _state.update { state ->
        val role = if (state.members.none { it.role == FamilyRole.ADMIN }) FamilyRole.ADMIN else FamilyRole.REVIEWER
        state.copy(members = state.members + FamilyMemberDraft(role = role), error = null, message = null)
    }

    fun updateMember(index: Int, name: String? = null, email: String? = null, role: FamilyRole? = null) =
        _state.update { state ->
            if (index !in state.members.indices) return@update state
            val rows = state.members.toMutableList()
            val current = rows[index]
            if (role == FamilyRole.ADMIN) {
                rows.indices.forEach { i ->
                    if (rows[i].role == FamilyRole.ADMIN) rows[i] = rows[i].copy(role = FamilyRole.REVIEWER)
                }
            } else if (current.role == FamilyRole.ADMIN && role != null &&
                rows.indices.none { it != index && rows[it].role == FamilyRole.ADMIN }
            ) {
                return@update state.copy(error = "A family must have exactly one Admin.")
            }
            rows[index] = current.copy(
                name = name ?: current.name,
                email = email ?: current.email,
                role = role ?: current.role,
            )
            state.copy(members = rows, error = null, message = null)
        }

    fun removeMember(index: Int) = _state.update { state ->
        if (index !in state.members.indices) return@update state
        val row = state.members[index]
        if (row.memberId == state.currentMemberId) {
            return@update state.copy(error = "Use Leave family to remove this phone.")
        }
        state.copy(members = state.members.toMutableList().also { it.removeAt(index) }, error = null)
    }

    fun saveMembers() {
        val drafts = _state.value.members
        runAction {
            repository.saveMembers(drafts.map { it.toDomain() })
            val current = repository.currentMember()
            _state.update {
                it.copy(
                    currentMemberId = current?.memberId,
                    message = "Family members saved on this phone. Sync is not connected.",
                )
            }
        }
    }

    fun handOverAdmin(memberId: String) = runAction {
        repository.handOverAdmin(memberId)
        _state.update { it.copy(message = "Admin role updated on this phone. Other phones are not synced.") }
    }

    fun leaveFamily() = runAction {
        repository.leaveFamily()
        _state.update { it.copy(message = "This phone left Family mode. Sync is not connected.") }
    }

    fun recoverFamily() {
        // TODO(sync): authenticate, fetch the encrypted family snapshot and rebuild local data.
        _state.update {
            it.copy(error = "Family recovery is not connected. No data was changed.")
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, message = null) }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "The family change could not be saved.") }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private fun FamilyMember.toDraft() = FamilyMemberDraft(
        memberId = memberId,
        name = name,
        email = email,
        role = role,
        appVersionCode = appVersionCode,
        appVersionName = appVersionName,
        syncFormat = syncFormat,
        lastSeenAt = lastSeenAt,
    )

    private fun FamilyMemberDraft.toDomain() = FamilyMember(
        memberId = memberId,
        email = email,
        name = name,
        role = role,
        active = true,
        appVersionCode = appVersionCode,
        appVersionName = appVersionName,
        syncFormat = syncFormat,
        lastSeenAt = lastSeenAt,
    )
}

@Composable
fun FamilyModeChoiceScreen(onChoose: (Boolean) -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("How will you use Talenta?", style = MaterialTheme.typography.headlineSmall)
        Text("Choose Individual to keep using Talenta on this phone, or Family to prepare a shared household.")
        Button(onClick = { onChoose(false) }, modifier = Modifier.fillMaxWidth()) { Text("Individual") }
        Button(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth()) { Text("Family") }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FamilySetupScreen(
    onBack: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenHandOver: () -> Unit,
    viewModel: FamilySetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family members") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Manage family roles and members stored on this phone. Drive and sync are not connected.")
            state.members.forEachIndexed { index, member ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = member.name,
                        onValueChange = { viewModel.updateMember(index, name = it) },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !state.saving && state.canManageRoles,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = member.email,
                        onValueChange = { viewModel.updateMember(index, email = it) },
                        label = { Text("Gmail") },
                        singleLine = true,
                        enabled = !state.saving && state.canManageRoles,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FamilyRole.entries.forEach { role ->
                            FilterChip(
                                selected = member.role == role,
                                onClick = { viewModel.updateMember(index, role = role) },
                                label = { Text(role.label) },
                                enabled = !state.saving && state.canManageRoles,
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.removeMember(index) },
                        enabled = !state.saving && state.canManageRoles,
                    ) {
                        Text("Remove member")
                    }
                }
            }
            if (state.members.count { it.role == FamilyRole.ADMIN } != 1) {
                Text("Choose exactly one Admin before saving.", color = MaterialTheme.colorScheme.error)
            }
            TextButton(
                onClick = viewModel::addMember,
                enabled = !state.saving && state.canManageRoles,
            ) { Text("Add family member") }
            Button(
                onClick = viewModel::saveMembers,
                enabled = !state.saving && state.canManageRoles &&
                    state.members.count { it.role == FamilyRole.ADMIN } == 1,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.saving) "Saving..." else "Save family members") }
            if (state.isAdmin) {
                TextButton(onClick = onOpenHandOver, enabled = !state.saving) { Text("Hand over Admin role") }
            }
            TextButton(onClick = onOpenRecovery, enabled = !state.saving) { Text("Recover family") }
            TextButton(onClick = { confirmLeave = true }, enabled = !state.saving) { Text("Leave family") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirmLeave) {
        LeaveFamilyDialog(
            onConfirm = { confirmLeave = false; viewModel.leaveFamily() },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FamilyRecoveryScreen(
    onBack: () -> Unit,
    viewModel: FamilySetupViewModel = hiltViewModel(),
) {
    var passphrase by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover your family") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recovery needs the family sync engine. No sign-in, passphrase handling or Drive access is connected.")
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Family passphrase") },
                singleLine = true,
            )
            Button(onClick = viewModel::recoverFamily, enabled = passphrase.isNotBlank()) {
                Text("Continue")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FamilyHandOverScreen(
    onBack: () -> Unit,
    viewModel: FamilySetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hand over Admin role") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The role change is saved on this phone only; other members will not receive it until sync is connected.")
            if (!state.isAdmin) {
                Text("Only the current Admin can hand over the role.", color = MaterialTheme.colorScheme.error)
            }
            state.members.filter { it.memberId != state.currentMemberId }.forEach { member ->
                FilterChip(
                    selected = selectedMemberId == member.memberId,
                    onClick = { selectedMemberId = member.memberId },
                    label = { Text("${member.name} · ${member.email}") },
                    enabled = state.isAdmin && !state.saving,
                )
            }
            Button(
                onClick = { selectedMemberId?.let(viewModel::handOverAdmin) },
                enabled = state.isAdmin && selectedMemberId != null && !state.saving,
            ) { Text(if (state.saving) "Saving..." else "Hand over Admin role") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun LeaveFamilyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave family?") },
        text = {
            Text("This phone will stop using Family mode. Other members and their data are unchanged. Sync is not connected.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Leave family") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun FamilyModeChoicePreview() = FamilyModeChoiceScreen(onChoose = {})

@Preview(showBackground = true)
@Composable
private fun LeaveFamilyPreview() = LeaveFamilyDialog(onConfirm = {}, onDismiss = {})
