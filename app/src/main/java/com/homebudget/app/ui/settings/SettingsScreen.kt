package com.homebudget.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PrivacyTip
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.BugReport
import com.homebudget.app.crash.BugReport
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.app.BuildConfig
import com.homebudget.app.data.settings.ThemeMode
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.ui.common.ChoiceDialog
import com.homebudget.app.ui.common.ConfirmDialog
import com.homebudget.app.ui.common.LoadingState
import java.time.LocalDate
import java.util.Currency

private enum class SettingsDialog { CURRENCY, THEME, EXPORT_WARNING, CLEAR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageCategories: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBudgets: () -> Unit = {},
    onOpenRecurring: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(it.toString()) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.inspectBackup(it.toString()) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        val s = settings
        if (s == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Section("General")
            SettingRow(Icons.Filled.Paid, "Currency", currencyLabel(s.currencyCode)) { dialog = SettingsDialog.CURRENCY }
            SettingRow(Icons.Filled.DarkMode, "Theme", s.themeMode.label) { dialog = SettingsDialog.THEME }
            SettingRow(Icons.Filled.Category, "Categories", "Add, edit or deactivate categories", onManageCategories)

            Section("Planning")
            SettingRow(Icons.Filled.Savings, "Budgets", "Monthly limits for all spending or per category", onOpenBudgets)
            SettingRow(Icons.Filled.Repeat, "Repeating transactions", "Rent, salary, subscriptions and EMIs", onOpenRecurring)

            Section("Backup & restore")
            SettingRow(Icons.Filled.FileUpload, "Export backup", "Save all data to a file you choose") {
                dialog = SettingsDialog.EXPORT_WARNING
            }
            SettingRow(Icons.Filled.FileDownload, "Restore from backup", "Replace current data with a backup file") {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            }

            Section("Privacy & about")
            SettingRow(Icons.Filled.PrivacyTip, "Privacy", "How your data is handled", onOpenPrivacy)
            ListItem(
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
            ) { Text("Version") }
            val context = LocalContext.current
            BugReport.issueUrl(BuildConfig.ISSUES_URL, "Bug in ${BuildConfig.VERSION_NAME}", BugReport.body(BuildConfig.VERSION_NAME, null))?.let { url ->
                SettingRow(Icons.Filled.BugReport, "Report a bug", "Opens the project's GitHub issues page") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }

            Section("Danger zone")
            SettingRow(Icons.Filled.DeleteForever, "Clear all data", "Delete every transaction, account, category and bill") {
                dialog = SettingsDialog.CLEAR
            }
        }
    }

    when (dialog) {
        SettingsDialog.CURRENCY -> ChoiceDialog(
            title = "Currency",
            options = MoneyFormatter.SUPPORTED,
            selected = settings?.currencyCode,
            label = ::currencyLabel,
            onSelect = { vm.setCurrency(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries,
            selected = settings?.themeMode,
            label = { it.label },
            onSelect = { vm.setTheme(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.EXPORT_WARNING -> ConfirmDialog(
            title = "Export backup",
            message = "The backup file contains all your financial records, including bill items, in readable (unencrypted) form. " +
                "Store it somewhere private and only share it with people you trust.\n\n" +
                "Bill photos are not included in the backup; they stay on this device.",
            confirmLabel = "Choose location",
            destructive = false,
            onConfirm = {
                dialog = null
                exportLauncher.launch("talenta-backup-${LocalDate.now()}.json")
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CLEAR -> {
            var understood by remember { mutableStateOf(false) }
            ConfirmDialog(
                title = "Clear all data?",
                message = "This permanently deletes all transactions, accounts, categories, budgets, repeating transactions, bills and bill photos on this device. " +
                    "Default categories and a Cash account will be recreated. This cannot be undone. " +
                    "Export a backup first if you may need this data.",
                confirmLabel = "Delete everything",
                confirmEnabled = understood,
                onConfirm = { dialog = null; vm.clearAllData() },
                onDismiss = { dialog = null },
                extraContent = {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { understood = !understood },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = null)
                        Text("I understand this cannot be undone", modifier = Modifier.padding(start = 8.dp))
                    }
                },
            )
        }
        null -> Unit
    }

    pending?.let { p ->
        ConfirmDialog(
            title = "Restore this backup?",
            message = "The backup is valid and contains ${p.file.transactions.size} transactions, " +
                "${p.file.accounts.size} accounts and ${p.file.categories.size} categories (currency ${p.file.currencyCode}).\n\n" +
                "Restoring REPLACES all current data on this device. Restoring the same file again will not create duplicates.",
            confirmLabel = "Replace and restore",
            onConfirm = vm::confirmRestore,
            onDismiss = vm::cancelRestore,
        )
    }
}

private fun currencyLabel(code: String): String =
    runCatching { "$code · ${Currency.getInstance(code).displayName}" }.getOrDefault(code)

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(subtitle) },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            listOf(
                "Your data stays on this device" to
                    "All transactions, accounts, categories, budgets, repeating transactions, bills, bill photos and settings are stored only in this app's private " +
                    "storage on your device. No account or registration is needed.",
                "Bill photos and text reading" to
                    "Bill photos are taken with your camera app or chosen with the system photo picker, so the app needs no camera " +
                    "or storage permission. Each photo is copied into the app's private storage (re-saved without its original " +
                    "metadata, such as location). The text on the bill is read on your device by Google ML Kit's built-in text " +
                    "recognition model; the photo is never uploaded. ML Kit's usage-statistics component has been removed from the app.",
                "No internet access" to
                    "The app does not request the Internet permission, so it cannot send your data anywhere. " +
                    "There is no advertising, analytics or crash-reporting software in the app.",
                "No Android cloud backup" to
                    "Automatic Android cloud backup is turned off for this app. Your data is only copied off the device when you " +
                    "export a backup file yourself.",
                "Backup and export files" to
                    "Backups (JSON) and transaction exports (CSV) are created only when you ask, in a place you choose. " +
                    "They are not encrypted: anyone who can open the file can read your financial records. " +
                    "Keep them private. Bill photos are not included in backups.",
                "Deleting your data" to
                    "Delete a bill to remove it, its photo and the expenses created from it. Use Settings → Clear all data, " +
                    "or uninstall the app, to delete all data from this device.",
                "Storage encryption" to
                    "The app's database is protected by Android's app sandbox and the device's own storage encryption. " +
                    "The app does not add its own database encryption in this version.",
            ).forEach { (h, b) ->
                Text(h, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                Text(b, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
