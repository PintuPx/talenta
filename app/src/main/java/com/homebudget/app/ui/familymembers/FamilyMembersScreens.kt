package com.homebudget.app.ui.familymembers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.homebudget.app.domain.family.equalShares
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FamilyPerson(val id: String, val name: String, val tags: Set<String> = emptySet())

data class FamilyMembersState(
    val members: List<FamilyPerson> = emptyList(),
    val tags: List<String> = listOf("All"),
    val selectedMemberIds: Set<String> = emptySet(),
    val totalMinor: Long = 10_001,
)

interface FamilyMembersRepository {
    fun read(): FamilyMembersState
    fun write(state: FamilyMembersState)
}

class InMemoryFamilyMembersRepository(
    initial: FamilyMembersState = FamilyMembersState(
        members = listOf(
            FamilyPerson("person-a", "Member A", setOf("Parents")),
            FamilyPerson("person-b", "Member B", setOf("Kids")),
        ),
        tags = listOf("All", "Parents", "Kids"),
        selectedMemberIds = setOf("person-a", "person-b"),
    ),
) : FamilyMembersRepository {
    private var state = initial
    override fun read(): FamilyMembersState = state
    override fun write(state: FamilyMembersState) { this.state = state }
}

class FamilyMembersViewModel(
    private val repository: FamilyMembersRepository = InMemoryFamilyMembersRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read())
    val state: StateFlow<FamilyMembersState> = _state.asStateFlow()

    fun addMember(name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank() || _state.value.members.any { it.name.equals(clean, ignoreCase = true) }) return false
        update { it.copy(members = it.members + FamilyPerson("person-${it.members.size + 1}", clean)) }
        return true
    }

    fun addTag(name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank() || clean.equals("All", ignoreCase = true) ||
            _state.value.tags.any { it.equals(clean, ignoreCase = true) }
        ) return false
        update { it.copy(tags = it.tags + clean) }
        return true
    }

    fun removeMember(memberId: String) = update { state ->
        state.copy(
            members = state.members.filterNot { it.id == memberId },
            selectedMemberIds = state.selectedMemberIds - memberId,
        )
    }

    fun removeTag(tag: String) {
        if (tag == "All") return
        update { state ->
            state.copy(
                tags = state.tags.filterNot { it == tag },
                members = state.members.map { it.copy(tags = it.tags - tag) },
            )
        }
    }

    fun assignTag(memberId: String, tag: String, assigned: Boolean) = update { state ->
        state.copy(
            members = state.members.map { member ->
                if (member.id != memberId) member
                else member.copy(tags = if (assigned) member.tags + tag else member.tags - tag)
            }
        )
    }

    fun toggleTag(tag: String) = update { state ->
        val taggedIds = if (tag == "All") state.members.mapTo(HashSet()) { it.id }
        else state.members.filter { tag in it.tags }.mapTo(HashSet()) { it.id }
        val allSelected = taggedIds.isNotEmpty() && state.selectedMemberIds.containsAll(taggedIds)
        val selected = if (allSelected) state.selectedMemberIds - taggedIds else state.selectedMemberIds + taggedIds
        state.copy(selectedMemberIds = selected)
    }

    fun toggleMember(id: String) = update { state ->
        val selected = if (id in state.selectedMemberIds) state.selectedMemberIds - id else state.selectedMemberIds + id
        state.copy(selectedMemberIds = selected)
    }

    fun setTotalMinor(value: Long) = update { it.copy(totalMinor = value.coerceAtLeast(0)) }

    private inline fun update(transform: (FamilyMembersState) -> FamilyMembersState) {
        _state.update { transform(it).also(repository::write) }
    }
}

@Composable
fun FamilyMembersScreen(viewModel: FamilyMembersViewModel = remember { FamilyMembersViewModel() }) {
    val state by viewModel.state.collectAsState()
    var newMember by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Family members", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newMember, { newMember = it }, label = { Text("Member name") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                message = if (viewModel.addMember(newMember)) null else "Enter a unique name."
                if (message == null) newMember = ""
            }) { Text("Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newTag, { newTag = it }, label = { Text("Tag name") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                message = if (viewModel.addTag(newTag)) null else "Enter a unique tag name."
                if (message == null) newTag = ""
            }) { Text("Add tag") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Tags · All includes every member", style = MaterialTheme.typography.titleMedium)
        state.tags.forEach { tag ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tag, modifier = Modifier.weight(1f))
                if (tag != "All") TextButton(onClick = { viewModel.removeTag(tag) }) { Text("Remove tag") }
                state.members.forEach { member ->
                    FilterChip(
                        selected = tag in member.tags || (tag == "All" && state.members.isNotEmpty()),
                        onClick = { if (tag != "All") viewModel.assignTag(member.id, tag, tag !in member.tags) },
                        label = { Text(member.name) },
                    )
                }
            }
        }
        state.members.forEach { member ->
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(member.name, style = MaterialTheme.typography.titleSmall)
                        Text(member.tags.ifEmpty { setOf("No tags") }.joinToString())
                    }
                    TextButton(onClick = { viewModel.removeMember(member.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
fun ExpenseMemberPickerScreen(viewModel: FamilyMembersViewModel = remember { FamilyMembersViewModel() }) {
    val state by viewModel.state.collectAsState()
    val selected = state.members.filter { it.id in state.selectedMemberIds }
    val shares = equalShares(state.totalMinor, selected.size)
    Scaffold { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Who is this for?", style = MaterialTheme.typography.headlineSmall)
                    Text("Select a tag or people. You can untick any person afterward.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tags.forEach { tag ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.toggleTag(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
            }
            items(state.members, key = { it.id }) { member ->
                FilterChip(
                    selected = member.id in state.selectedMemberIds,
                    onClick = { viewModel.toggleMember(member.id) },
                    label = { Text(member.name) },
                )
            }
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Equal split preview", style = MaterialTheme.typography.titleMedium)
                        selected.forEachIndexed { index, member ->
                            Text("${member.name}: ${shares[index]} minor units")
                        }
                        Text("Shares add up to ${shares.sum()} minor units.")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FamilyMembersPreview() = FamilyMembersScreen()

@Preview(showBackground = true)
@Composable
private fun ExpenseMemberPickerPreview() = ExpenseMemberPickerScreen()
