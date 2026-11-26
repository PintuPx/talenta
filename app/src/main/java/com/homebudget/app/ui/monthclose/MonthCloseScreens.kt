package com.homebudget.app.ui.monthclose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.AppSettings
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.finance.DateRange
import com.homebudget.app.domain.monthclose.MonthLockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class MonthCloseState(
    val month: YearMonth,
    val showReminder: Boolean = false,
    val closed: Boolean = false,
) {
    val monthLabel: String get() = month.format(MONTH_LABEL_FORMAT)

    private companion object {
        val MONTH_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
    }
}

/**
 * Month-close state for [month]. The reminder shows only when the month is still open, has at least
 * one transaction in the current space (so a fresh install is not nagged) and is not snoozed.
 */
internal fun monthCloseStateFlow(
    month: YearMonth,
    closed: Flow<Boolean>,
    hasTransactions: Flow<Boolean>,
    settings: Flow<AppSettings>,
    clockTicks: Flow<Long>,
): Flow<MonthCloseState> =
    combine(closed, hasTransactions, settings, clockTicks) { isClosed, hasAny, prefs, now ->
        MonthCloseState(
            month = month,
            showReminder = !isClosed && hasAny && now >= prefs.monthCloseReminderUntil,
            closed = isClosed,
        )
    }

@HiltViewModel
class MonthCloseViewModel @Inject constructor(
    private val repository: MonthLockRepository,
    private val settings: SettingsRepository,
    transactions: TransactionRepository,
    private val clock: Clock,
) : ViewModel() {
    private val month = YearMonth.now(clock).minusMonths(1)
    private val clockTicks = flow {
        while (true) {
            emit(clock.millis())
            delay(60_000)
        }
    }

    val state = monthCloseStateFlow(
        month = month,
        closed = repository.observeClosed(month),
        hasTransactions = transactions.observeHasAnyInRange(DateRange.month(month)),
        settings = settings.settings,
        clockTicks = clockTicks,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, MonthCloseState(month))

    fun isClosed(month: YearMonth) = repository.observeClosed(month)

    fun closeNow() {
        viewModelScope.launch {
            repository.close(month)
            settings.setMonthCloseReminderUntil(0)
        }
    }

    fun remindLater() {
        viewModelScope.launch {
            settings.setMonthCloseReminderUntil(clock.millis() + Duration.ofDays(3).toMillis())
        }
    }

    fun reopen() {
        viewModelScope.launch {
            repository.reopen(month)
            settings.setMonthCloseReminderUntil(0)
        }
    }
}

@Composable
fun MonthCloseScreen(viewModel: MonthCloseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Month close", style = MaterialTheme.typography.headlineSmall)
            Text(state.monthLabel, style = MaterialTheme.typography.titleMedium)
            if (state.closed) {
                LockedMonthNotice(monthLabel = state.monthLabel)
                OutlinedButton(onClick = viewModel::reopen) { Text("Reopen month") }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Close ${state.monthLabel} now?", style = MaterialTheme.typography.titleMedium)
                        Text("Closing is optional and does not happen automatically.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::closeNow) { Text("Close now") }
                            OutlinedButton(onClick = viewModel::remindLater) { Text("Remind me later") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonthCloseReminderBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MonthCloseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.showReminder || state.closed) return
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Close ${state.monthLabel} now?", style = MaterialTheme.typography.titleMedium)
            Text("Closing is optional and does not happen automatically.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::closeNow) { Text("Close now") }
                TextButton(onClick = viewModel::remindLater) { Text("Remind me later") }
            }
        }
    }
}

@Composable
fun LockedMonthNotice(modifier: Modifier = Modifier, monthLabel: String? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            if (monthLabel == null) "This month is closed. Only the Admin can edit."
            else "$monthLabel is closed. Only the Admin can edit.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}
