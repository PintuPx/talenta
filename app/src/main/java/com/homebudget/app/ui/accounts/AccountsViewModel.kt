package com.homebudget.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.validation.TransactionValidator
import com.homebudget.app.ui.common.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountWithBalance(val account: Account, val balance: Long)

data class AccountsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val active: List<AccountWithBalance> = emptyList(),
    val archived: List<AccountWithBalance> = emptyList(),
    val total: Long = 0,
)

/**
 * Editor state. For credit cards the user enters the amount OWED as a positive number
 * ([openingText]); it is stored as a negative opening balance.
 */
data class AccountEditorState(
    val id: Long = 0,
    val name: String = "",
    val type: AccountType = AccountType.SAVINGS,
    val openingText: String = "",
    val includeInTotal: Boolean = true,
    val archived: Boolean = false,
    val nameError: String? = null,
    val openingError: String? = null,
) {
    val isNew: Boolean get() = id == 0L
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val userMessages: UserMessages,
) : ViewModel() {

    val state: StateFlow<AccountsUiState> = combine(repository.observeAccounts(), repository.observeBalances()) { accounts, balances ->
        val rows = accounts.map { AccountWithBalance(it, balances[it.id] ?: it.openingBalance) }
        AccountsUiState(
            loading = false,
            active = rows.filter { !it.account.archived },
            archived = rows.filter { it.account.archived },
            total = FinanceRules.totalBalance(accounts, balances),
        )
    }
        .catch { emit(AccountsUiState(loading = false, error = "Could not load accounts")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    private val _editor = MutableStateFlow<AccountEditorState?>(null)
    val editor: StateFlow<AccountEditorState?> = _editor.asStateFlow()

    fun startCreate() { _editor.value = AccountEditorState() }

    fun startEdit(a: Account) {
        val shown = if (a.type == AccountType.CREDIT_CARD) -a.openingBalance else a.openingBalance
        _editor.value = AccountEditorState(
            id = a.id, name = a.name, type = a.type,
            openingText = if (shown == 0L) "" else Money.toEditableSignedText(shown),
            includeInTotal = a.includeInTotal, archived = a.archived,
        )
    }

    fun dismissEditor() { _editor.value = null }
    fun updateEditor(transform: (AccountEditorState) -> AccountEditorState) = _editor.update { it?.let(transform) }

    fun saveEditor() {
        val e = _editor.value ?: return
        val nameError = if (e.name.isBlank()) "Enter a name" else null
        val parsed = AmountParser.parseSigned(e.openingText)
        val openingError = (parsed as? AmountParseResult.Valid)?.let { null } ?: TransactionValidator.amountError(parsed, 2)
        if (nameError != null || openingError != null) {
            _editor.value = e.copy(nameError = nameError, openingError = openingError)
            return
        }
        val entered = (parsed as AmountParseResult.Valid).minor
        val opening = if (e.type == AccountType.CREDIT_CARD) -entered else entered
        viewModelScope.launch {
            try {
                repository.save(Account(e.id, e.name, e.type, opening, e.includeInTotal, e.archived))
                _editor.value = null
                userMessages.show(if (e.isNew) "Account added" else "Account updated")
            } catch (ex: IntegrityException) {
                _editor.value = e.copy(nameError = ex.message)
            }
        }
    }

    fun deleteEditing() {
        val e = _editor.value ?: return
        viewModelScope.launch {
            if (repository.deleteIfUnused(e.id)) {
                _editor.value = null
                userMessages.show("Account deleted")
            } else {
                userMessages.show("This account has transactions. Archive it instead to hide it.")
            }
        }
    }
}
