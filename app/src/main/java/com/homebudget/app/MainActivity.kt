package com.homebudget.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.homebudget.app.crash.BugReport
import com.homebudget.app.crash.CrashReporter
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.data.settings.AppSettings
import kotlinx.coroutines.launch
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.data.settings.ThemeMode
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.UserMessages
import com.homebudget.app.ui.navigation.AppRoot
import com.homebudget.app.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.homebudget.app.ui.wallet.LauncherIconSwitcher
import com.homebudget.app.ui.wallet.WalletSnapshotLoader
import com.homebudget.app.ui.wallet.WalletWidget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MainContent() }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    val userMessages: UserMessages,
    private val recurring: RecurringRepository,
    private val walletLoader: WalletSnapshotLoader,
    private val iconSwitcher: LauncherIconSwitcher,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var posting = false

    /** When the app goes to the background: shows the launcher icon and widget for this month's wallet level. */
    fun onBackground() {
        viewModelScope.launch {
            val snapshot = runCatching { walletLoader.load() }.getOrNull() ?: return@launch
            runCatching { iconSwitcher.apply(snapshot.level) }
            runCatching { WalletWidget.update(appContext, snapshot) }
        }
    }

    /** Adds repeating transactions that have become due. Called whenever the app comes to the foreground. */
    fun postDueRecurring() {
        if (posting) return
        posting = true
        viewModelScope.launch {
            try {
                val n = recurring.postDue()
                if (n > 0) userMessages.show("Added $n repeating transaction${if (n == 1) "" else "s"}")
            } finally {
                posting = false
            }
        }
    }
}

@Composable
private fun MainContent(vm: MainViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.postDueRecurring()
        onStopOrDispose { vm.onBackground() }
    }
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val formatter = remember(settings.currencyCode) { MoneyFormatter(settings.currencyCode) }
    AppTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalMoneyFormatter provides formatter) {
            AppRoot(userMessages = vm.userMessages)
            CrashReportPrompt()
        }
    }
}

/** After a crash, offers to share the private crash report (see [CrashReporter]). Nothing is sent automatically. */
@Composable
private fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pending(context)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { },
        title = { Text("The app closed unexpectedly") },
        text = {
            Text(
                "A crash report was saved on this phone. It contains the error and code locations, the app and Android version " +
                    "and the phone model, but no amounts, notes or other data you entered. Please report it so it can be fixed."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Talenta crash report")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Share crash report"))
                CrashReporter.clear(context)
                report = null
            }) { Text("Share") }
        },
        dismissButton = {
            Row {
                val issue = BugReport.issueUrl(BuildConfig.ISSUES_URL, "Crash in ${BuildConfig.VERSION_NAME}", BugReport.body(BuildConfig.VERSION_NAME, text))
                if (issue != null) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issue))) }
                        CrashReporter.clear(context)
                        report = null
                    }) { Text("Report on GitHub") }
                }
                TextButton(onClick = { CrashReporter.clear(context); report = null }) { Text("Delete") }
            }
        },
    )
}
