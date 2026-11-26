package com.homebudget.app.ui.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaceFieldState(
    val place: String = "",
    val suggestions: List<String> = listOf("Big Bazaar, MG Road", "Central Market", "Home"),
    val locationMessage: String? = null,
)

interface PlaceFieldRepository {
    fun initialState(): PlaceFieldState
}

class InMemoryPlaceFieldRepository(
    private val state: PlaceFieldState = PlaceFieldState(),
) : PlaceFieldRepository {
    override fun initialState(): PlaceFieldState = state
}

class PlaceFieldViewModel(
    repository: PlaceFieldRepository = InMemoryPlaceFieldRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(repository.initialState())
    val state: StateFlow<PlaceFieldState> = _state.asStateFlow()

    fun setPlace(place: String) {
        _state.value = _state.value.copy(place = place, locationMessage = null)
    }

    fun useSuggestion(place: String) {
        setPlace(place)
    }

    fun showLocationPlaceholder() {
        _state.value = _state.value.copy(
            locationMessage = "Location suggestions are not available in this prototype.",
        )
    }
}

@Composable
fun PlaceFieldScreen(viewModel: PlaceFieldViewModel = remember { PlaceFieldViewModel() }) {
    val state by viewModel.state.collectAsState()
    val suggestions = state.suggestions.filter {
        state.place.isNotBlank() && it.contains(state.place, ignoreCase = true)
    }
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Transaction place", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = state.place,
                onValueChange = viewModel::setPlace,
                label = { Text("Place (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            suggestions.forEach { place ->
                TextButton(onClick = { viewModel.useSuggestion(place) }) {
                    Text(place)
                }
            }
            Button(onClick = viewModel::showLocationPlaceholder) {
                Text("Use my location")
            }
            state.locationMessage?.let { Text(it) }
            Text("Sample form only; place suggestions are not saved.")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceFieldPreview() = PlaceFieldScreen()
