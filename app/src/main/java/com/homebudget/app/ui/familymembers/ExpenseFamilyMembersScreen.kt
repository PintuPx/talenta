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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.family.ExpensePerson
import com.homebudget.app.domain.family.ExpensePersonTag
import com.homebudget.app.domain.family.ExpenseTag
import com.homebudget.app.data.repository.IntegrityException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExpenseFamilyState(
    val loading: Boolean = true,
    val people: List<ExpensePerson> = emptyList(),
    val tags: List<ExpenseTag> = emptyList(),
    val links: List<ExpensePersonTag> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class ExpenseFamilyMembersViewModel @Inject constructor(
    private val repository: ExpenseFamilyRepository,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val state: StateFlow<ExpenseFamilyState> = combine(
        repository.observeActivePeople(),
        repository.observeActiveTags(),
        repository.observeTagLinks(),
        _message,
    ) { people, tags, links, message ->
        ExpenseFamilyState(loading = false, people = people, tags = tags, links = links, message = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpenseFamilyState())

    fun addPerson(name: String) {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > 50) {
            _message.value = "Enter a member name up to 50 characters."
            return
        }
        if (state.value.people.any { it.name.equals(clean, ignoreCase = true) }) {
            _message.value = "A member with that name already exists."
            return
        }
        runRepositoryAction { repository.addPerson(clean) }
    }

    fun addTag(name: String) {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > 50 || clean.equals("All", ignoreCase = true)) {
            _message.value = "Enter a tag name up to 50 characters. All is built in."
            return
        }
        if (state.value.tags.any { it.name.equals(clean, ignoreCase = true) }) {
            _message.value = "A tag with that name already exists."
            return
        }
        runRepositoryAction { repository.addTag(clean) }
    }

    fun deactivatePerson(uid: String) = runRepositoryAction { repository.deactivatePerson(uid) }
    fun deactivateTag(uid: String) = runRepositoryAction { repository.deactivateTag(uid) }
    fun setTagMember(tagUid: String, personUid: String, assigned: Boolean) =
        runRepositoryAction { repository.setTagMember(tagUid, personUid, assigned) }

    fun clearMessage() {
        _message.value = null
    }

    private fun runRepositoryAction(action: suspend () -> Any?) {
        viewModelScope.launch {
            try {
                action()
                _message.value = "Change saved. Reviewer changes need Admin approval."
            } catch (e: IntegrityException) {
                _message.value = e.message ?: "Could not save this change."
            }
        }
    }
}

@Composable
fun ExpenseFamilyMembersScreen(
    onBack: () -> Unit,
    viewModel: ExpenseFamilyMembersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var personName by remember { mutableStateOf("") }
    var tagName by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Family members and tags", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                personName,
                { personName = it },
                label = { Text("Member name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { viewModel.addPerson(personName); personName = "" }) { Text("Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                tagName,
                { tagName = it },
                label = { Text("Tag name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { viewModel.addTag(tagName); tagName = "" }) { Text("Add tag") }
        }
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
        }
        if (state.loading) {
            Text("Loading family members…")
        } else {
            Text("Tags · All includes every active member", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.tags, key = { it.uid }) { tag ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(tag.name, style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { viewModel.deactivateTag(tag.uid) }) { Text("Remove tag") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.people.forEach { person ->
                                    val assigned = state.links.any {
                                        it.tagUid == tag.uid && it.personUid == person.uid
                                    }
                                    FilterChip(
                                        selected = assigned,
                                        onClick = { viewModel.setTagMember(tag.uid, person.uid, !assigned) },
                                        label = { Text(person.name) },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Members", style = MaterialTheme.typography.titleMedium)
                            state.people.forEach { person ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(person.name)
                                    TextButton(onClick = { viewModel.deactivatePerson(person.uid) }) { Text("Remove") }
                                }
                            }
                            if (state.people.isEmpty()) Text("Add a member to assign transactions.")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExpenseFamilyMembersPreview() {
    val sample = listOf(
        ExpensePerson("person-1", "Alex", true),
        ExpensePerson("person-2", "Sam", true),
    )
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Family members and tags", style = MaterialTheme.typography.headlineSmall)
        Text("All includes every active member")
        sample.forEach { Text(it.name) }
    }
}
