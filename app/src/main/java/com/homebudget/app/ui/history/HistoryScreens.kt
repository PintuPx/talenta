package com.homebudget.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.homebudget.app.domain.history.AdminHistoryRow
import com.homebudget.app.domain.history.HistoryMarker
import com.homebudget.app.domain.history.HistoryRepository
import com.homebudget.app.domain.history.HistoryVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class HistoryState(
    val adminRows: List<AdminHistoryRow> = emptyList(),
    val memberFilter: String = "",
    val entryFilter: String = "",
    val dateFilter: String = "",
)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: HistoryRepository) : ViewModel() {
    private data class Filters(val member: String = "", val entry: String = "", val date: String = "")

    private val filters = MutableStateFlow(Filters())
    val state: StateFlow<HistoryState> = combine(repository.observeAll(), filters) { rows, f ->
        HistoryState(
            adminRows = rows.filter { row ->
                (f.member.isBlank() || row.member.contains(f.member, ignoreCase = true)) &&
                    (f.entry.isBlank() || row.entryLabel.contains(f.entry, ignoreCase = true) ||
                        row.entityType.contains(f.entry, ignoreCase = true)) &&
                    (f.date.isBlank() || row.dateKey.contains(f.date, ignoreCase = true))
            },
            memberFilter = f.member,
            entryFilter = f.entry,
            dateFilter = f.date,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun observeEntity(entityType: String, entityUid: String) = repository.observeEntity(entityType, entityUid)
    fun setMemberFilter(value: String) = filters.update { it.copy(member = value) }
    fun setEntryFilter(value: String) = filters.update { it.copy(entry = value) }
    fun setDateFilter(value: String) = filters.update { it.copy(date = value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    )
}

@Composable
fun EntryHistoryScreen(
    entityType: String,
    entityUid: String,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val historyFlow = androidx.compose.runtime.remember(entityType, entityUid) {
        viewModel.observeEntity(entityType, entityUid)
    }
    val entry by historyFlow.collectAsState(initial = null)
    val versions = entry?.versions.orEmpty()
    Scaffold(topBar = { HistoryTopBar("History · ${entry?.label ?: "Entry"}", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (versions.isEmpty()) item { Text("No history is available.") }
            items(versions, key = { it.id }) { version ->
                HistoryVersionCard(version)
            }
        }
    }
}

@Composable
fun AdminHistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Scaffold(topBar = { HistoryTopBar("Household history", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(state.memberFilter, viewModel::setMemberFilter, label = { Text("Filter by member") }, singleLine = true)
            OutlinedTextField(state.entryFilter, viewModel::setEntryFilter, label = { Text("Filter by entry") }, singleLine = true)
            OutlinedTextField(state.dateFilter, viewModel::setDateFilter, label = { Text("Filter by date") }, singleLine = true)
            Text("Filters apply to shared history.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.adminRows, key = { it.id }) { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.entryLabel, style = MaterialTheme.typography.titleSmall)
                            Text("${row.member} · ${row.whenLabel} · ${row.entityType.replace('_', ' ')} · version ${row.version}")
                            HistoryMarkerText(row.marker)
                            if (row.status != "APPROVED") Text(row.status.lowercase().replaceFirstChar { it.uppercase() })
                            row.changes.forEach { Text("${it.field}: ${it.before} → ${it.after}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryVersionCard(version: HistoryVersion) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (version.changes.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Version ${version.version} · ${version.member} · ${version.whenLabel}", style = MaterialTheme.typography.titleSmall)
            HistoryMarkerText(version.marker)
            version.changes.forEach { change ->
                Row {
                    Text("${change.field}: ", style = MaterialTheme.typography.labelLarge)
                    Text("${change.before} → ${change.after}")
                }
            }
            if (version.status != "APPROVED") Text(version.status.lowercase().replaceFirstChar { it.uppercase() })
            if (version.changes.isEmpty() && version.marker == HistoryMarker.NORMAL) Text("Entry created")
        }
    }
}

@Composable
private fun HistoryMarkerText(marker: HistoryMarker) {
    when (marker) {
        HistoryMarker.NORMAL -> Unit
        HistoryMarker.DELETED -> Text("Deleted", color = MaterialTheme.colorScheme.error)
        HistoryMarker.RESTORED -> Text("Restored after deletion")
    }
}

