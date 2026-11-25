package com.homebudget.app.ui.bills

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.bill.BillRepository
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.domain.model.Bill
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.ui.common.DateFormats
import com.homebudget.app.ui.common.EmptyState
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(
    private val bills: BillRepository,
    private val accounts: AccountRepository,
    val images: BillImageStore,
    private val messages: UserMessages,
) : ViewModel() {
    val list: StateFlow<List<Bill>?> = bills.observeBills()
        .map<List<Bill>, List<Bill>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val busy = MutableStateFlow(false)

    private val _openBill = Channel<Long>(Channel.BUFFERED)
    val openBill = _openBill.receiveAsFlow()

    fun newCaptureUri(): Uri = images.newCaptureUri()

    /** Copies the photo into private storage, creates a draft bill and opens it for review. */
    fun createFromPhoto(uri: Uri) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val file = images.import(uri)
                _openBill.send(bills.createDraft(file, defaultAccountId()))
            } catch (e: IOException) {
                messages.show(e.message ?: "The photo could not be saved")
            } catch (e: SecurityException) {
                messages.show("Permission to read the photo was denied")
            } finally {
                busy.value = false
            }
        }
    }

    fun createManual() {
        viewModelScope.launch { _openBill.send(bills.createDraft(null, defaultAccountId())) }
    }

    fun showMessage(text: String) = messages.show(text)

    private suspend fun defaultAccountId(): Long? = accounts.observeAccounts().first().firstOrNull { !it.archived }?.id
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    onBack: () -> Unit,
    onOpenBill: (Long) -> Unit,
    startWithAdd: Boolean = false,
    vm: BillsViewModel = hiltViewModel(),
) {
    val list by vm.list.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(startWithAdd) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { vm.openBill.collect(onOpenBill) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture
        pendingCapture = null
        if (saved && uri != null) vm.createFromPhoto(uri.toUri())
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.createFromPhoto(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bills") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add bill") },
                modifier = Modifier.semantics { contentDescription = "Add bill" },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val bills = list
            when {
                bills == null -> LoadingState()
                bills.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    title = "No bills yet",
                    message = "Take a photo of a shop bill. The app reads the items on your device, suggests a group for " +
                        "each one, and adds them as expenses once you approve.",
                )
                else -> LazyColumn {
                    items(bills, key = { it.id }) { bill -> BillRow(bill, vm.images) { onOpenBill(bill.id) } }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Add a bill", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SheetOption(Icons.Filled.PhotoCamera, "Take a photo", "Opens your camera app") {
                    showAdd = false
                    val uri = vm.newCaptureUri()
                    pendingCapture = uri.toString()
                    try {
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        pendingCapture = null
                        vm.showMessage("No camera app was found. Choose a photo instead.")
                    }
                }
                SheetOption(Icons.Filled.PhotoLibrary, "Choose a photo", "Pick a bill photo you already have") {
                    showAdd = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SheetOption(Icons.Filled.EditNote, "Enter manually", "Type the items yourself, without a photo") {
                    showAdd = false
                    vm.createManual()
                }
                Text(
                    "Photos stay on this device. Text is read on the device; nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(subtitle) },
    ) { Text(title) }
}

@Composable
private fun BillRow(bill: Bill, images: BillImageStore, onClick: () -> Unit) {
    val fmt = LocalMoneyFormatter.current
    val title = bill.merchant.ifBlank { "Bill" }
    val status = if (bill.status == BillStatus.DRAFT) "Draft · needs review" else "Added to expenses"
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Open bill", onClick = onClick),
        leadingContent = { BillThumbnail(bill.imageFile, images, 48) },
        supportingContent = {
            Text("${bill.billDate.toLocalDate().format(DateFormats.date)} · $status", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { bill.total?.let { Text(fmt.format(it), style = MaterialTheme.typography.bodyLarge) } },
    ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Small square preview of a bill photo, or a receipt icon when there is none. */
@Composable
fun BillThumbnail(imageFile: String?, images: BillImageStore, sizeDp: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, imageFile) {
        value = imageFile?.let { images.loadBitmap(it, sizeDp * 3)?.asImageBitmap() }
    }
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp))
        } else {
            Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
