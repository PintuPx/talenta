package com.homebudget.app.ui.adminreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.app.domain.review.AdminReviewRepository
import com.homebudget.app.domain.review.AdminReviewRequest
import com.homebudget.app.domain.review.SkippedBulkChangeNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdminReviewState(
    val requests: List<AdminReviewRequest> = emptyList(),
    val skippedBulkChanges: List<SkippedBulkChangeNotice> = emptyList(),
    val selectedValues: Map<String, String> = emptyMap(),
    val customValues: Map<String, String> = emptyMap(),
    val rejectingEntryId: String? = null,
    val rejectionReason: String = "",
    val busyEntryIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AdminReviewViewModel @Inject constructor(
    private val repository: AdminReviewRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AdminReviewState())
    val state: StateFlow<AdminReviewState> = _state.asStateFlow()
    private var observeJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            combine(repository.observePending(), repository.observeSkippedBulkChanges()) { requests, notices ->
                requests to notices
            }
                .catch { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(isLoading = false, error = error.message ?: "Could not load pending changes.")
                    }
                }
                .collect { (requests, notices) ->
                    _state.update {
                        it.copy(requests = requests, skippedBulkChanges = notices, isLoading = false, error = null)
                    }
                }
        }
    }

    fun applySkippedBulkChange(noticeId: String) = runSkippedChangeAction(noticeId) {
        repository.applySkippedBulkChange(noticeId)
    }

    fun ignoreSkippedBulkChange(noticeId: String) = runSkippedChangeAction(noticeId) {
        repository.ignoreSkippedBulkChange(noticeId)
    }

    fun choose(entryId: String, fieldKey: String, value: String) = _state.update {
        val stateKey = key(entryId, fieldKey)
        it.copy(
            selectedValues = it.selectedValues + (stateKey to value),
            customValues = it.customValues - stateKey,
        )
    }

    fun setCustom(entryId: String, fieldKey: String, value: String) = _state.update {
        it.copy(customValues = it.customValues + (key(entryId, fieldKey) to value))
    }

    fun approve(entryId: String) {
        val current = _state.value
        val fields = current.requests.filter { it.entryId == entryId }
            .flatMap { it.changes }
            .distinctBy { it.fieldKey }
        val resolutions = fields.mapNotNull { change ->
            val stateKey = key(entryId, change.fieldKey)
            val value = current.customValues[stateKey]?.takeIf(String::isNotBlank)
                ?: current.selectedValues[stateKey]
            value?.let { change.fieldKey to it }
        }.toMap()
        runEntryAction(entryId) { repository.approve(entryId, resolutions) }
    }

    fun startReject(entryId: String) = _state.update {
        it.copy(rejectingEntryId = entryId, rejectionReason = "")
    }

    fun setRejectionReason(value: String) = _state.update {
        it.copy(rejectionReason = value.take(300))
    }

    fun cancelReject() = _state.update {
        it.copy(rejectingEntryId = null, rejectionReason = "")
    }

    fun confirmReject() {
        val current = _state.value
        val entryId = current.rejectingEntryId ?: return
        runEntryAction(entryId) {
            repository.reject(entryId, current.rejectionReason.takeIf(String::isNotBlank))
            _state.update { it.copy(rejectingEntryId = null, rejectionReason = "") }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun runEntryAction(entryId: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update {
                it.copy(error = null, busyEntryIds = it.busyEntryIds + entryId)
            }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = error.message ?: "The review action could not be completed.")
                }
            } finally {
                _state.update { it.copy(busyEntryIds = it.busyEntryIds - entryId) }
            }
        }
    }

    private fun runSkippedChangeAction(noticeId: String, action: suspend () -> Unit) {
        val busyId = "skipped:$noticeId"
        viewModelScope.launch {
            _state.update { it.copy(error = null, busyEntryIds = it.busyEntryIds + busyId) }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "The skipped change could not be completed.") }
            } finally {
                _state.update { it.copy(busyEntryIds = it.busyEntryIds - busyId) }
            }
        }
    }

    private fun key(entryId: String, fieldKey: String) = "$entryId:$fieldKey"
}

@Composable
fun AdminReviewScreen(viewModel: AdminReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AdminReviewContent(state, viewModel)
}

@Composable
private fun AdminReviewContent(state: AdminReviewState, viewModel: AdminReviewViewModel?) {
    val grouped = state.requests.groupBy { it.entryId }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Review changes",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            state.error?.let { message ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel?.dismissError() }) { Text("Dismiss") }
                        if (state.requests.isEmpty()) {
                            OutlinedButton(onClick = { viewModel?.refresh() }) { Text("Retry") }
                        }
                    }
                }
            }
            items(state.skippedBulkChanges, key = { "skipped:${it.id}" }) { notice ->
                val busy = "skipped:${notice.id}" in state.busyEntryIds
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${notice.memberName}'s change ${notice.itemDescription}: " +
                                "${notice.previousCategoryName} → ${notice.categoryName} skipped " +
                                "${notice.skippedEntries} entries in closed months.",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel?.applySkippedBulkChange(notice.id) },
                                enabled = !busy,
                            ) {
                                Text("Apply to them")
                            }
                            TextButton(
                                onClick = { viewModel?.ignoreSkippedBulkChange(notice.id) },
                                enabled = !busy,
                            ) {
                                Text("Ignore")
                            }
                        }
                    }
                }
            }
            if (state.isLoading && state.requests.isEmpty()) {
                item { Text("Loading pending changes...") }
            } else if (grouped.isEmpty() && state.error == null) {
                item { Text("No changes to review.") }
            }
            items(grouped.entries.toList(), key = { it.key }) { (entryId, requests) ->
                val changes = requests.flatMap { it.changes }
                val conflicts = changes.groupBy { it.fieldKey }
                    .filterValues { options -> options.map { it.newValue }.distinct().size > 1 }
                val deleted = requests.any { it.currentDeleted }
                val deletionRequested = requests.any { it.deletionRequested }
                val busy = entryId in state.busyEntryIds
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(requests.first().entryLabel, style = MaterialTheme.typography.titleMedium)
                        if (deleted) {
                            Text(
                                "This entry was deleted. Approving these changes restores it.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (deletionRequested) {
                            Text("A household member requested that this entry be deleted.")
                        }
                        requests.forEach { request ->
                            Text("Change from ${request.memberLabel} · based on version ${request.baseVersion}")
                            if (request.baseVersion != request.latestVersion) {
                                Text(
                                    "This change is based on version ${request.baseVersion}; the current version is ${request.latestVersion}. " +
                                        "The differences below are against the current version.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            request.changes.forEach { change ->
                                Text("${change.field}: ${change.oldValue} → ${change.newValue}")
                            }
                        }
                        conflicts.forEach { (fieldKey, options) ->
                            val field = options.first().field
                            Text("$field has different proposed values.", style = MaterialTheme.typography.titleSmall)
                            options.distinctBy { it.newValue }.forEach { option ->
                                val choiceKey = "$entryId:$fieldKey"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.selectedValues[choiceKey] == option.newValue,
                                        onClick = { viewModel?.choose(entryId, fieldKey, option.newValue) },
                                        enabled = !busy,
                                    )
                                    Text(
                                        "${option.newValue} · ${
                                            requests.firstOrNull {
                                                it.changes.any { change ->
                                                    change.fieldKey == fieldKey && change.newValue == option.newValue
                                                }
                                            }?.memberLabel.orEmpty()
                                        }",
                                    )
                                }
                            }
                            if (!fieldKey.endsWith("Uid") && fieldKey != "memberId") {
                                OutlinedTextField(
                                    value = state.customValues["$entryId:$fieldKey"].orEmpty(),
                                    onValueChange = { viewModel?.setCustom(entryId, fieldKey, it) },
                                    label = { Text("Or enter $field") },
                                    singleLine = true,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val approveLabel = when {
                                deleted -> "Restore and approve"
                                deletionRequested -> "Approve deletion"
                                conflicts.isNotEmpty() -> "Approve selected"
                                changes.size == 2 -> "Approve both"
                                changes.size > 2 -> "Approve changes"
                                else -> "Approve change"
                            }
                            val resolved = conflicts.keys.all { fieldKey ->
                                state.selectedValues.containsKey("$entryId:$fieldKey") ||
                                    !state.customValues["$entryId:$fieldKey"].isNullOrBlank()
                            }
                            Button(
                                onClick = { viewModel?.approve(entryId) },
                                enabled = !busy && resolved,
                            ) { Text(if (busy) "Saving..." else approveLabel) }
                            TextButton(
                                onClick = { viewModel?.startReject(entryId) },
                                enabled = !busy,
                            ) { Text("Reject") }
                        }
                    }
                }
            }
        }
    }

    state.rejectingEntryId?.let { entryId ->
        AlertDialog(
            onDismissRequest = { viewModel?.cancelReject() },
            title = { Text("Reject changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The pending changes for this entry will be rejected.")
                    OutlinedTextField(
                        value = state.rejectionReason,
                        onValueChange = { viewModel?.setRejectionReason(it) },
                        label = { Text("Reason (optional)") },
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel?.confirmReject() }, enabled = entryId !in state.busyEntryIds) {
                    Text("Reject")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel?.cancelReject() }) { Text("Cancel") }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdminReviewPreview() {
    AdminReviewContent(AdminReviewState(isLoading = false), viewModel = null)
}
