package com.homebudget.app.ui.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.domain.model.Category
import com.homebudget.app.ui.common.CategoryBadge
import com.homebudget.app.ui.common.CategoryIcons
import com.homebudget.app.ui.common.CategorySheet
import com.homebudget.app.ui.common.ChoiceDialog
import com.homebudget.app.ui.common.ConfirmDialog
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.SelectField
import com.homebudget.app.ui.common.icon
import com.homebudget.app.ui.theme.LocalFinanceColors
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

private sealed interface ReviewDialog {
    data object Account : ReviewDialog
    data object Date : ReviewDialog
    data object Time : ReviewDialog
    data class Category(val lineKey: Long) : ReviewDialog
    data object ApplyToUnassigned : ReviewDialog
    data object Delete : ReviewDialog
    data object ReRead : ReviewDialog
    data object Discard : ReviewDialog
    data object Photo : ReviewDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillReviewScreen(
    onDone: () -> Unit,
    onManageCategories: () -> Unit,
    vm: BillReviewViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ReviewDialog?>(null) }
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    BackHandler(enabled = state.dirty) { dialog = ReviewDialog.Discard }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isPosted) "Bill" else "Review bill") },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) dialog = ReviewDialog.Discard else onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.loading && !state.notFound) {
                        IconButton(onClick = { dialog = ReviewDialog.Delete }) { Icon(Icons.Filled.Delete, contentDescription = "Delete bill") }
                    }
                },
            )
        },
        bottomBar = { if (!state.loading && !state.notFound) ActionBar(state, vm) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound -> EmptyState(
                Icons.Filled.ErrorOutline, "Bill not found", "It may have been deleted.", Modifier.padding(padding),
                action = { TextButton(onClick = onDone) { Text("Go back") } },
            )
            else -> ReviewContent(state, vm, onDialog = { dialog = it }, modifier = Modifier.padding(padding))
        }
    }

    when (val d = dialog) {
        ReviewDialog.Account -> ChoiceDialog(
            title = "Paid from",
            options = state.selectableAccounts,
            selected = state.account,
            label = { it.name },
            onSelect = { vm.setAccount(it.id); dialog = null },
            onDismiss = { dialog = null },
        )
        ReviewDialog.Date -> {
            val picker = rememberDatePickerState(initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        picker.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            ) { DatePicker(state = picker) }
        }
        ReviewDialog.Time -> {
            val picker = rememberTimePickerState(initialHour = state.time.hour, initialMinute = state.time.minute)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Time") },
                text = { TimePicker(state = picker) },
                confirmButton = { TextButton(onClick = { vm.setTime(LocalTime.of(picker.hour, picker.minute)); dialog = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        is ReviewDialog.Category -> CategorySheet(
            categories = state.selectableCategories,
            selectedId = state.lines.firstOrNull { it.key == d.lineKey }?.categoryId,
            onSelect = { vm.setCategory(d.lineKey, it.id); dialog = null },
            onManage = { dialog = null; onManageCategories() },
            onDismiss = { dialog = null },
            title = "Choose group or main group",
        )
        ReviewDialog.ApplyToUnassigned -> CategorySheet(
            categories = state.selectableCategories,
            selectedId = null,
            onSelect = { vm.applyToUnassigned(it.id); dialog = null },
            onManage = null,
            onDismiss = { dialog = null },
            title = "Group for all unassigned lines",
        )
        ReviewDialog.Delete -> ConfirmDialog(
            title = "Delete bill?",
            message = if (state.transactionCount > 0) {
                "The bill, its photo and the ${state.transactionCount} expense transactions created from it will be deleted. This cannot be undone."
            } else {
                "The bill and its photo will be deleted. This cannot be undone."
            },
            confirmLabel = "Delete",
            onConfirm = { dialog = null; vm.delete() },
            onDismiss = { dialog = null },
        )
        ReviewDialog.ReRead -> ConfirmDialog(
            title = "Read the bill again?",
            message = "The current line items will be replaced by what is read from the photo.",
            confirmLabel = "Read again",
            destructive = false,
            onConfirm = { dialog = null; vm.readText() },
            onDismiss = { dialog = null },
        )
        ReviewDialog.Discard -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Discard changes?") },
            text = { Text(if (state.isPosted) "Your changes have not been applied to the expenses." else "Your changes to this bill have not been saved.") },
            confirmButton = {
                if (!state.isPosted) {
                    TextButton(onClick = { dialog = null; vm.saveDraft() }) { Text("Save draft") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { dialog = null }) { Text("Keep editing") }
                    TextButton(onClick = { dialog = null; onDone() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
        ReviewDialog.Photo -> state.imageFile?.let { PhotoViewer(it, vm.images) { dialog = null } }
        null -> Unit
    }
}

@Composable
private fun ReviewContent(state: BillReviewState, vm: BillReviewViewModel, onDialog: (ReviewDialog) -> Unit, modifier: Modifier) {
    val categories = state.categoriesById
    LazyColumn(
        modifier.imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PhotoCard(state, vm.images, onOpenPhoto = { onDialog(ReviewDialog.Photo) }, onRead = {
            if (state.lines.isEmpty()) vm.readText() else onDialog(ReviewDialog.ReRead)
        }) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.merchant,
                    onValueChange = vm::setMerchant,
                    label = { Text("Shop / merchant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField(
                        label = "Date", value = state.date.format(DateFormats.date), onClick = { onDialog(ReviewDialog.Date) },
                        leading = { Icon(Icons.Filled.CalendarToday, contentDescription = null) }, showArrow = false,
                        modifier = Modifier.weight(1.2f),
                    )
                    SelectField(
                        label = "Time", value = state.time.format(DateFormats.time), onClick = { onDialog(ReviewDialog.Time) },
                        leading = { Icon(Icons.Filled.Schedule, contentDescription = null) }, showArrow = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    SelectField(
                        label = "Paid from", value = state.account?.name ?: "", onClick = { onDialog(ReviewDialog.Account) },
                        leading = state.account?.let { a -> { Icon(a.type.icon(), contentDescription = null) } },
                        error = if (state.showProblems && state.accountId == null) "Required" else null,
                        modifier = Modifier.weight(1.2f),
                    )
                    OutlinedTextField(
                        value = state.totalText,
                        onValueChange = vm::setTotal,
                        label = { Text("Bill total") },
                        prefix = { Text(state.currencySymbol) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Line items (${state.lines.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (state.lines.any { it.included && it.categoryId == null }) {
                    TextButton(onClick = { onDialog(ReviewDialog.ApplyToUnassigned) }) { Text("Fill blanks") }
                }
                TextButton(onClick = { vm.addLine() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add line")
                }
            }
        }

        if (state.lines.isEmpty()) {
            item {
                Text(
                    if (state.imageFile == null) "Add each item on the bill with its amount and group."
                    else "No items yet. Read the photo, or add the items yourself.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.lines, key = { it.key }) { line ->
            LineCard(
                line = line,
                categories = categories,
                currencySymbol = state.currencySymbol,
                amountError = if (state.showProblems || line.amountText.isNotEmpty()) state.amountError(line) else null,
                categoryMissing = state.showProblems && line.included && line.categoryId == null,
                onDescription = { vm.setDescription(line.key, it) },
                onAmount = { vm.setAmount(line.key, it) },
                onIncluded = { vm.setIncluded(line.key, it) },
                onPickCategory = { onDialog(ReviewDialog.Category(line.key)) },
                onRemove = { vm.removeLine(line.key) },
            )
        }

        item { TotalsCard(state, onAddDifference = vm::addDifferenceLine) }

        if (state.split.isNotEmpty()) item { SplitCard(state) }

        if (state.showProblems && state.problems.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Before approving:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        state.problems.take(8).forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer) }
                        if (state.problems.size > 8) Text("…and ${state.problems.size - 8} more", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        state.ocrText?.takeIf { it.isNotBlank() }?.let { text ->
            item { OcrTextSection(text) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PhotoCard(state: BillReviewState, images: BillImageStore, onOpenPhoto: () -> Unit, onRead: () -> Unit) {
    if (state.imageFile == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BillThumbnail(
                state.imageFile, images, 72,
                Modifier.clickable(onClickLabel = "View bill photo", onClick = onOpenPhoto),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.reading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Reading the bill on your device…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        state.readMessage ?: if (state.ocrText == null) "Not read yet" else "Tap the photo to compare it with the items",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onRead, enabled = !state.reading) {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.ocrText == null) "Read text" else "Read again")
                    }
                }
            }
        }
    }
}

/** One bill line: Item | Amount, then Group and Main group (e.g. Veggie · ₹40 → Vegetables → Groceries). */
@Composable
private fun LineCard(
    line: BillLineUi,
    categories: Map<Long, Category>,
    currencySymbol: String,
    amountError: String?,
    categoryMissing: Boolean,
    onDescription: (String) -> Unit,
    onAmount: (String) -> Unit,
    onIncluded: (Boolean) -> Unit,
    onPickCategory: () -> Unit,
    onRemove: () -> Unit,
) {
    val category = line.categoryId?.let(categories::get)
    val main = category?.parentId?.let(categories::get) ?: category
    val group = category?.takeIf { it.parentId != null }
    val contentAlpha = if (line.included) 1f else 0.5f
    Card(
        modifier = Modifier.testTag("bill-line"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp).graphicsLayer { alpha = contentAlpha }) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = line.included,
                    onCheckedChange = onIncluded,
                    modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "Include ${line.description.ifBlank { "line" }}" },
                )
                OutlinedTextField(
                    value = line.description,
                    onValueChange = onDescription,
                    label = { Text("Item") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = line.amountText,
                    onValueChange = onAmount,
                    label = { Text("Amount") },
                    prefix = { Text(currencySymbol) },
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(116.dp),
                )
                IconButton(onClick = onRemove, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${line.description.ifBlank { "line" }}")
                }
            }
            Row(Modifier.padding(start = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    label = "Group",
                    value = group?.name ?: if (main != null) "—" else "",
                    onClick = onPickCategory,
                    leading = group?.let { g -> { CategoryBadge(CategoryIcons.of(g.iconKey), Color(g.colorArgb), null, size = 24) } },
                    error = if (categoryMissing) "Choose a group" else null,
                    showArrow = false,
                    modifier = Modifier.weight(1f),
                )
                SelectField(
                    label = "Main group",
                    value = main?.name ?: "",
                    onClick = onPickCategory,
                    showArrow = false,
                    modifier = Modifier.weight(1f),
                )
            }
            if (line.suggested && category != null) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(4.dp))
                    Text("Suggested. Tap Group to change it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!line.included) {
                Text("Excluded: this line will not be added", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            }
        }
    }
}

@Composable
private fun TotalsCard(state: BillReviewState, onAddDifference: () -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val fc = LocalFinanceColors.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AmountRow("Items total", fmt.format(state.itemsTotal))
            state.total?.let { AmountRow("Bill total", fmt.format(it)) }
            val diff = state.difference
            when {
                diff == null || diff == 0L -> if (diff == 0L) Text("Items match the bill total ✓", style = MaterialTheme.typography.bodySmall, color = fc.income)
                diff > 0 -> {
                    Text(
                        "${fmt.format(diff)} of the bill total is not in any line (often tax or charges).",
                        style = MaterialTheme.typography.bodySmall, color = fc.warning,
                    )
                    TextButton(onClick = onAddDifference) { Text("Add ${fmt.format(diff)} as a line") }
                }
                else -> Text(
                    "Items add up to ${fmt.format(-diff)} more than the bill total (a discount, or a misread amount). Check the lines.",
                    style = MaterialTheme.typography.bodySmall, color = fc.warning,
                )
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
    }
}

/** How the bill is split: each main group, with its groups underneath. */
@Composable
private fun SplitCard(state: BillReviewState) {
    val fmt = LocalMoneyFormatter.current
    val categories = state.categoriesById
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Split", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Approving adds ${state.postingCount} ${if (state.postingCount == 1) "expense" else "expenses"}, one per group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.split.forEach { group ->
                val main = categories[group.mainGroupId]
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    main?.let { CategoryBadge(CategoryIcons.of(it.iconKey), Color(it.colorArgb), null, size = 28) }
                    Spacer(Modifier.size(8.dp))
                    AmountRow(main?.name ?: "Unknown", fmt.format(group.amount), bold = true)
                }
                group.lines.forEach { line ->
                    val c = categories[line.categoryId]
                    val name = if (line.categoryId == group.mainGroupId) "${c?.name ?: "Unknown"} (no group)" else c?.name ?: "Unknown"
                    Row(Modifier.padding(start = 36.dp)) {
                        Text(
                            "$name · ${line.itemCount} ${if (line.itemCount == 1) "item" else "items"}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(fmt.format(line.amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val unassigned = state.lines.count { it.included && it.categoryId == null }
            if (unassigned > 0) {
                Text("$unassigned ${if (unassigned == 1) "line has" else "lines have"} no group yet", style = MaterialTheme.typography.bodySmall, color = LocalFinanceColors.current.warning)
            }
        }
    }
}

@Composable
private fun OcrTextSection(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide text read from the bill" else "Show text read from the bill") }
        if (expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
            )
        }
    }
}

@Composable
private fun ActionBar(state: BillReviewState, vm: BillReviewViewModel) {
    val fc = LocalFinanceColors.current
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.isPosted) {
                OutlinedButton(onClick = { vm.saveDraft() }, enabled = !state.saving, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Save draft", maxLines = 1)
                }
            }
            val n = state.postingCount
            Button(
                onClick = vm::approve,
                enabled = !state.saving && !state.reading,
                colors = ButtonDefaults.buttonColors(containerColor = fc.expense, contentColor = fc.onExpense),
                modifier = Modifier.weight(if (state.isPosted) 1f else 1.4f).height(48.dp).testTag("approve"),
            ) {
                Text(
                    when {
                        state.isPosted -> "Update expenses ($n)"
                        n == 0 -> "Approve & add"
                        else -> "Approve & add $n"
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Full-screen bill photo with pinch-to-zoom and pan. */
@Composable
private fun PhotoViewer(imageFile: String, images: BillImageStore, onClose: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, imageFile) {
        value = images.loadBitmap(imageFile, BillImageStore.MAX_EDGE)?.asImageBitmap()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let {
                Image(
                    it,
                    contentDescription = "Bill photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(transform)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)) {
                Icon(Icons.Filled.Close, contentDescription = "Close photo", tint = Color.White)
            }
        }
    }
}
