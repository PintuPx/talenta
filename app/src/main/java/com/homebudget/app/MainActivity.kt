package com.homebudget.app

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.homebudget.app.crash.BugReport
import com.homebudget.app.crash.CrashReporter
import com.homebudget.app.FeatureFlags
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Row
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.data.settings.AppSettings
import com.homebudget.app.data.settings.AppMode
import kotlinx.coroutines.launch
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.data.settings.ThemeMode
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.ui.common.LocalMoneyFormatter
import com.homebudget.app.ui.common.LoadingState
import com.homebudget.app.ui.family.AppModeScreen
import com.homebudget.app.ui.family.FamilyHandOverScreen
import com.homebudget.app.ui.family.FamilyRecoveryScreen
import com.homebudget.app.ui.family.FamilySetupScreen
import com.homebudget.app.ui.common.UserMessages
import com.homebudget.app.ui.navigation.AppRoot
import com.homebudget.app.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.content.ContextCompat
import com.homebudget.app.ui.wallet.LauncherIconSwitcher
import com.homebudget.app.ui.wallet.WalletSnapshotLoader
import com.homebudget.app.ui.wallet.WalletWidget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import java.util.concurrent.Executor

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainContent(
                authenticatePrivateMode = { onAuthenticated, onNoScreenLock, onFailure ->
                    authenticatePrivateMode(onAuthenticated, onNoScreenLock, onFailure)
                },
            )
        }
    }

    // REVIEW: Private mode is enabled only after device authentication or explicit confirmation.
    private fun authenticatePrivateMode(
        onAuthenticated: () -> Unit,
        onNoScreenLock: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor: Executor = ContextCompat.getMainExecutor(this)
                val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onAuthenticated()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (
                            errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ||
                            errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS
                        ) {
                            onNoScreenLock()
                        } else if (
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            onFailure("Device authentication failed. Private mode was not turned on.")
                        }
                    }
                })
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Turn on Private mode")
                        .setSubtitle("Confirm with fingerprint or your phone PIN")
                        .setAllowedAuthenticators(authenticators)
                        .build(),
                )
            }
            // With DEVICE_CREDENTIAL allowed, "no screen lock and no enrolled biometric" is reported as NONE_ENROLLED.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> onNoScreenLock()
            else -> onFailure("Device authentication is unavailable. Private mode was not turned on.")
        }
    }
}

private enum class FirstLaunchFamilyScreen { SETUP, RECOVERY, HAND_OVER }

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    val userMessages: UserMessages,
    private val recurring: RecurringRepository,
    private val walletLoader: WalletSnapshotLoader,
    private val iconSwitcher: LauncherIconSwitcher,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var posting = false

    /** When the app goes to the background: shows the launcher icon and widget for this month's wallet level. */
    fun onBackground() {
        viewModelScope.launch { settingsRepository.setPrivateMode(false) }
        viewModelScope.launch {
            val snapshot = runCatching { walletLoader.load() }.getOrNull() ?: return@launch
            runCatching { iconSwitcher.apply(snapshot.level) }
            runCatching { WalletWidget.update(appContext, snapshot) }
        }
    }

    fun turnOffPrivateMode() {
        viewModelScope.launch { settingsRepository.setPrivateMode(false) }
    }

    fun turnOnPrivateMode() {
        viewModelScope.launch { settingsRepository.setPrivateMode(true) }
    }

    fun initializeIndividualModeIfUnset() {
        viewModelScope.launch { settingsRepository.initializeIndividualModeIfUnset() }
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
private fun MainContent(
    authenticatePrivateMode: (
        onAuthenticated: () -> Unit,
        onNoScreenLock: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    vm: MainViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var firstLaunchFamilyScreen by remember { mutableStateOf<FirstLaunchFamilyScreen?>(null) }
    var confirmWithoutScreenLock by remember { mutableStateOf(false) }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    if (settings == null) {
        AppTheme(darkTheme = isSystemInDarkTheme()) { LoadingState() }
        return
    }
    val currentSettings = settings ?: return
    val activityWindow = LocalActivity.current?.window
    DisposableEffect(activityWindow, currentSettings.privateMode) {
        if (currentSettings.privateMode) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LifecycleStartEffect(vm) {
        vm.postDueRecurring()
        onStopOrDispose { vm.onBackground() }
    }
    val dark = when (currentSettings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val formatter = remember(currentSettings.currencyCode) { MoneyFormatter(currentSettings.currencyCode) }
    val requestPrivateMode = {
        if (!currentSettings.askForDeviceLock) {
            vm.turnOnPrivateMode()
        } else {
            // Function-type parameters must be called positionally: onAuthenticated, onNoScreenLock, onFailure.
            authenticatePrivateMode(
                { vm.turnOnPrivateMode() },
                { confirmWithoutScreenLock = true },
                { message -> authenticationError = message },
            )
        }
    }
    if (currentSettings.appMode == AppMode.UNSET) {
        if (!FeatureFlags.FAMILY_SHARING) {
            LaunchedEffect(currentSettings.appMode) {
                vm.initializeIndividualModeIfUnset()
            }
            AppTheme(darkTheme = dark) { LoadingState() }
            return
        }
        AppTheme(darkTheme = dark) {
            AppModeScreen(
                firstLaunch = true,
                onOpenFamilySetup = { firstLaunchFamilyScreen = FirstLaunchFamilyScreen.SETUP },
            )
        }
        return
    }
    AppTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalMoneyFormatter provides formatter) {
            when (firstLaunchFamilyScreen) {
                FirstLaunchFamilyScreen.SETUP -> FamilySetupScreen(
                    onBack = { firstLaunchFamilyScreen = null },
                    onOpenRecovery = { firstLaunchFamilyScreen = FirstLaunchFamilyScreen.RECOVERY },
                    onOpenHandOver = { firstLaunchFamilyScreen = FirstLaunchFamilyScreen.HAND_OVER },
                )
                FirstLaunchFamilyScreen.RECOVERY -> FamilyRecoveryScreen(
                    onBack = { firstLaunchFamilyScreen = FirstLaunchFamilyScreen.SETUP },
                )
                FirstLaunchFamilyScreen.HAND_OVER -> FamilyHandOverScreen(
                    onBack = { firstLaunchFamilyScreen = FirstLaunchFamilyScreen.SETUP },
                )
                null -> AppRoot(
                    userMessages = vm.userMessages,
                    privateMode = currentSettings.privateMode,
                    onTurnOffPrivateMode = vm::turnOffPrivateMode,
                    onRequestPrivateMode = requestPrivateMode,
                )
            }
            if (firstLaunchFamilyScreen == null) CrashReportPrompt()
        }
    }
    if (confirmWithoutScreenLock) {
        AlertDialog(
            onDismissRequest = { confirmWithoutScreenLock = false },
            title = { Text("Phone lock is not set up") },
            text = { Text("Set a PIN, password or fingerprint in your phone settings for better protection. Continue without device confirmation?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWithoutScreenLock = false
                    vm.turnOnPrivateMode()
                }) { Text("Continue without lock") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWithoutScreenLock = false }) { Text("Cancel") }
            },
        )
    }
    authenticationError?.let { message ->
        AlertDialog(
            onDismissRequest = { authenticationError = null },
            title = { Text("Could not confirm") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { authenticationError = null }) { Text("OK") } },
        )
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
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, issue.toUri())) }
                        CrashReporter.clear(context)
                        report = null
                    }) { Text("Report on GitHub") }
                }
                TextButton(onClick = { CrashReporter.clear(context); report = null }) { Text("Delete") }
            }
        },
    )
}
