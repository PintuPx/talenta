package com.homebudget.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homebudget.app.domain.model.Category

/**
 * Two-level category picker. Main groups are shown first; tapping one that has groups opens its
 * groups plus an "All <main group>" option to use the main group itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Category) -> Unit,
    onManage: (() -> Unit)?,
    onDismiss: () -> Unit,
    title: String = "Choose category",
) {
    val mains = categories.filter { it.parentId == null }
    val groupsByParent = categories.filter { it.parentId != null }.groupBy { it.parentId!! }
    val selectedParent = categories.firstOrNull { it.id == selectedId }?.parentId
    var openMain by remember { mutableStateOf(selectedParent?.let { p -> mains.firstOrNull { it.id == p } }) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val main = openMain
            if (main != null) {
                IconButton(onClick = { openMain = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to main groups")
                }
                Text(main.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            if (onManage != null) TextButton(onClick = onManage) { Text("Manage") }
        }
        if (categories.isEmpty()) {
            Text("No active categories of this type. Use Manage to add one.", modifier = Modifier.padding(16.dp))
        }
        val main = openMain
        val entries: List<Pair<Category, String>> = if (main == null) {
            mains.map { c -> c to (if (groupsByParent[c.id].isNullOrEmpty()) c.name else "${c.name} ›") }
        } else {
            listOf(main to "All ${main.name}") + (groupsByParent[main.id] ?: emptyList()).map { it to it.name }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 88.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(entries, key = { it.first.id }) { (c, label) ->
                val hasGroups = main == null && !groupsByParent[c.id].isNullOrEmpty()
                val selected = c.id == selectedId || (main == null && c.id == selectedParent)
                Column(
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = if (hasGroups) "Show groups in ${c.name}" else "Select $label") {
                            if (hasGroups) openMain = c else onSelect(c)
                        }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CategoryBadge(CategoryIcons.of(c.iconKey), Color(c.colorArgb), null, size = if (selected) 52 else 44)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        (if (selected) "✓ " else "") + label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
