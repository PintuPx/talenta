package com.homebudget.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.backup.BackupRepository
import com.homebudget.app.data.repository.DataMaintenanceRepository
import com.homebudget.app.data.settings.AppSettings
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.data.settings.ThemeMode
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupFile
import com.homebudget.app.ui.common.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** A validated backup waiting for the user to confirm the restore. */
data class PendingRestore(val file: BackupFile)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val maintenance: DataMaintenanceRepository,
    private val messages: UserMessages,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    fun setCurrency(code: String) = viewModelScope.launch { settingsRepository.setCurrency(code) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun export(uri: String) = runBusy {
        try {
            val count = backupRepository.exportTo(uri)
            messages.show("Backup saved ($count transactions)")
        } catch (e: IOException) {
            messages.show("Backup failed: ${e.message ?: "could not write the file"}")
        } catch (e: SecurityException) {
            messages.show("Backup failed: permission denied")
        }
    }

    /** Step 1: read and validate. Nothing is changed until [confirmRestore]. */
    fun inspectBackup(uri: String) = runBusy {
        when (val r = backupRepository.inspect(uri)) {
            is BackupDecodeResult.Valid -> _pendingRestore.value = PendingRestore(r.file)
            is BackupDecodeResult.Invalid -> messages.show("Cannot restore: ${r.reason}")
        }
    }

    fun cancelRestore() { _pendingRestore.value = null }

    fun confirmRestore() {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        runBusy {
            try {
                val s = backupRepository.restore(pending.file)
                messages.show("Restored ${s.transactions} transactions, ${s.accounts} accounts")
            } catch (e: Exception) {
                messages.show("Restore failed. Your existing data was not changed.")
            }
        }
    }

    fun clearAllData() = runBusy {
        maintenance.clearAllData()
        messages.show("All data deleted")
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { block() } finally { _busy.value = false }
        }
    }
}
