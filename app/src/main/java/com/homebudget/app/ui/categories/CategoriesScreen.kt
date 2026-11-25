package com.homebudget.app.ui.categories

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.ChoiceDialog
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.SelectField
import com.homebudget.app.ui.common.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryEditor(
    val id: Long = 0,
    val name: String = "",
    val kind: CategoryKind,
    val iconKey: String = "misc",
    val colorArgb: Long = CategoryIcons.palette.first(),
    val active: Boolean = true,
    val error: String? = null,
    /** Main group when this is a group; null for a main group. */
    val parentId: Long? = null,
    /** A main group that has groups cannot itself become a group. */
    val hasGroups: Boolean = false,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoryRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val categories: StateFlow<List<Category>?> = repository.observeCategories()
        .map<List<Category>, List<Category>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val editor = MutableStateFlow<CategoryEditor?>(null)

    fun create(kind: CategoryKind) { editor.value = CategoryEditor(kind = kind) }

    /** New group inside [parent], starting with the parent's icon and color. */
    fun createGroup(parent: Category) {
        editor.value = CategoryEditor(kind = parent.kind, iconKey = parent.iconKey, colorArgb = parent.colorArgb, parentId = parent.id)
    }

    fun edit(c: Category) {
        val hasGroups = categories.value.orEmpty().any { it.parentId == c.id }
        editor.value = CategoryEditor(c.id, c.name, c.kind, c.iconKey, c.colorArgb, c.active, parentId = c.parentId, hasGroups = hasGroups)
    }
    fun dismiss() { editor.value = null }

    fun setActive(c: Category, active: Boolean) = viewModelScope.launch {
        repository.setActive(c.id, active)
        messages.show(if (active) "${c.name} activated" else "${c.name} deactivated. Existing transactions keep it.")
    }

    fun save() {
        val e = editor.value ?: return
        if (e.name.isBlank()) { editor.value = e.copy(error = "Enter a name"); return }
        viewModelScope.launch {
            try {
                repository.save(Category(e.id, e.name, e.kind, e.iconKey, e.colorArgb, active = e.active, parentId = e.parentId))
                editor.value = null
            } catch (ex: IntegrityException) {
                editor.value = e.copy(error = ex.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val all by vm.categories.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(CategoryKind.EXPENSE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.create(tab) }) { Icon(Icons.Filled.Add, contentDescription = "Add category") }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                CategoryKind.entries.forEach { k ->
                    Tab(
                        selected = tab == k,
                        onClick = { tab = k },
                        text = { Text(if (k == CategoryKind.EXPENSE) "Expense" else "Income") },
                    )
                }
            }
            val list = all
            if (list == null) {
                LoadingState()
            } else {
                val ofKind = list.filter { it.kind == tab }
                val groups = ofKind.filter { it.parentId != null }.groupBy { it.parentId!! }
                LazyColumn {
                    ofKind.filter { it.parentId == null }.forEach { main ->
                        item(key = main.id) { CategoryRow(main, indent = false, vm) }
                        groups[main.id].orEmpty().forEach { g ->
                            item(key = g.id) { CategoryRow(g, indent = true, vm) }
                        }
                        item(key = "add-${main.id}") {
                            TextButton(
                                onClick = { vm.createGroup(main) },
                                modifier = Modifier.padding(start = 64.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Add group to ${main.name}")
                            }
                        }
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    editor?.let { e -> CategoryEditorDialog(e, all.orEmpty(), vm) }
}

@Composable
private fun CategoryRow(c: Category, indent: Boolean, vm: CategoriesViewModel) {
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Edit ${c.name}") { vm.edit(c) }.padding(start = if (indent) 32.dp else 0.dp),
        leadingContent = { CategoryBadge(CategoryIcons.of(c.iconKey), Color(c.colorArgb), null, size = if (indent) 32 else 40) },
        supportingContent = if (!c.active) ({ Text("Inactive · hidden when adding transactions") }) else null,
        trailingContent = {
            Switch(
                checked = c.active,
                onCheckedChange = { vm.setActive(c, it) },
                modifier = Modifier.semantics { contentDescription = "${c.name} active" },
            )
        },
    ) { Text(c.name) }
}

@Composable
private fun CategoryEditorDialog(e: CategoryEditor, all: List<Category>, vm: CategoriesViewModel) {
    var pickParent by remember { mutableStateOf(false) }
    val mains = all.filter { it.kind == e.kind && it.parentId == null && it.id != e.id }
    val parentName = e.parentId?.let { p -> all.firstOrNull { it.id == p }?.name }
    AlertDialog(
        onDismissRequest = vm::dismiss,
        title = {
            Text(
                when {
                    e.id != 0L -> "Edit category"
                    e.parentId != null -> "New group in ${parentName ?: "main group"}"
                    else -> "New ${if (e.kind == CategoryKind.EXPENSE) "expense" else "income"} category"
                }
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = e.name,
                    onValueChange = { v -> vm.editor.value = e.copy(name = v.take(40), error = null) },
                    label = { Text("Name") },
                    isError = e.error != null,
                    supportingText = e.error?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (e.hasGroups) {
                    Text(
                        "This is a main group with its own groups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectField(
                        label = "Main group",
                        value = parentName ?: "None (this is a main group)",
                        onClick = { pickParent = true },
                    )
                }
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcons.all.forEach { (key, icon) ->
                        val selected = key == e.iconKey
                        Box(
                            Modifier
                                .size(44.dp)
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { vm.editor.value = e.copy(iconKey = key) }
                                .semantics { this.selected = selected; contentDescription = "Icon $key" },
                            contentAlignment = Alignment.Center,
                        ) { CategoryBadge(icon, Color(e.colorArgb), null, size = 36) }
                    }
                }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcons.palette.forEachIndexed { i, argb ->
                        val selected = argb == e.colorArgb
                        Box(
                            Modifier
                                .size(40.dp)
                                .clickable(role = Role.RadioButton) { vm.editor.value = e.copy(colorArgb = argb) }
                                .semantics { this.selected = selected; contentDescription = "Color ${i + 1}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(32.dp).border(16.dp, Color(argb), CircleShape))
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::save) { Text("Save") } },
        dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
    )
    if (pickParent) {
        val none = Category(id = -1, name = "None (this is a main group)", kind = e.kind, iconKey = "misc", colorArgb = 0)
        ChoiceDialog(
            title = "Main group",
            options = listOf(none) + mains,
            selected = mains.firstOrNull { it.id == e.parentId } ?: none,
            label = { it.name },
            onSelect = { c -> vm.editor.value = e.copy(parentId = c.id.takeIf { it > 0 }, error = null); pickParent = false },
            onDismiss = { pickParent = false },
        )
    }
}
