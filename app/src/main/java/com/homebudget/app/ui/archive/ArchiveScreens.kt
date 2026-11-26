package com.homebudget.app.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ArchiveEntry(val year: Int, val label: String, val itemCount: Int)

data class ArchiveState(
    val yearsKept: String = "2",
    val photoAgeYears: String = "1",
    val reminderVisible: Boolean = true,
    val reminderDelayDays: Int? = null,
    val archiveEntries: List<ArchiveEntry> = listOf(ArchiveEntry(2023, "2023 archive", 42)),
    val selectedYear: Int? = null,
)

interface ArchiveRepository {
    fun read(): ArchiveState
    fun write(state: ArchiveState)
}

class InMemoryArchiveRepository(initial: ArchiveState = ArchiveState()) : ArchiveRepository {
    private var state = initial
    override fun read(): ArchiveState = state
    override fun write(state: ArchiveState) { this.state = state }
}

class ArchiveViewModel(
    private val repository: ArchiveRepository = InMemoryArchiveRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<ArchiveState> = _state.asStateFlow()

    fun setYearsKept(value: String) = update { it.copy(yearsKept = value.filter(Char::isDigit).take(2)) }
    fun setPhotoAge(value: String) = update { it.copy(photoAgeYears = value.filter(Char::isDigit).take(2)) }
    fun archiveNow() = update { it.copy(reminderVisible = false) }
    fun remindLater(days: Int) = update { it.copy(reminderVisible = false, reminderDelayDays = days) }
    fun openArchive(year: Int) = update { it.copy(selectedYear = year) }
    fun closeArchive() = update { it.copy(selectedYear = null) }

    private inline fun update(transform: (ArchiveState) -> ArchiveState) {
        _state.update { transform(it).also(repository::write) }
    }
}

@Composable
fun ArchiveScreen(viewModel: ArchiveViewModel = remember { ArchiveViewModel() }) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Archive", style = MaterialTheme.typography.headlineSmall)
            if (state.reminderVisible) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("2024 is ready to archive", style = MaterialTheme.typography.titleMedium)
                        Text("Archiving is manual. This prototype does not move or delete data.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::archiveNow) { Text("Archive now (prototype)") }
                            TextButton(onClick = { viewModel.remindLater(7) }) { Text("Remind me later") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(false, { viewModel.remindLater(7) }, label = { Text("1 week") })
                            FilterChip(false, { viewModel.remindLater(30) }, label = { Text("1 month") })
                        }
                    }
                }
            }
            Text("Retention settings", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.yearsKept,
                onValueChange = viewModel::setYearsKept,
                label = { Text("Years kept on this phone (default 2)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.photoAgeYears,
                onValueChange = viewModel::setPhotoAge,
                label = { Text("Bill photo age before archive (default 1 year)") },
                singleLine = true,
            )
            Text("Read-only archives", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.archiveEntries, key = { it.year }) { entry ->
                    Card {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(entry.label, style = MaterialTheme.typography.titleSmall)
                                Text("${entry.itemCount} archived records · read-only")
                            }
                            TextButton(onClick = { viewModel.openArchive(entry.year) }) { Text("View") }
                        }
                    }
                }
            }
        }
    }
    state.selectedYear?.let { year ->
        ArchiveViewerScreen(year = year, entries = state.archiveEntries, onClose = viewModel::closeArchive)
    }
}

@Composable
fun ArchiveViewerScreen(year: Int, entries: List<ArchiveEntry>, onClose: () -> Unit) {
    val entry = entries.firstOrNull { it.year == year }
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$year archive", style = MaterialTheme.typography.headlineSmall)
            Text("Read-only preview. Archived records cannot be changed here.")
            Text(entry?.let { "${it.itemCount} records in this archive." } ?: "Archive is not available.")
            TextButton(onClick = onClose) { Text("Back to archives") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ArchivePreview() = ArchiveScreen()
