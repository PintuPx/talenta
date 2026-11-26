package com.homebudget.app.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.family.ExpensePerson
import com.homebudget.app.domain.family.ExpensePersonTag
import com.homebudget.app.domain.family.ExpenseTag
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.domain.monthclose.MonthLockRepository
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.recurring.Recurrence
import com.homebudget.app.domain.recurring.RepeatOption
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.domain.validation.TransactionDraft
import com.homebudget.app.domain.validation.TransactionValidator
import com.homebudget.app.domain.validation.TxField
import com.homebudget.app.domain.validation.ValidationResult
import com.homebudget.app.ui.common.UserMessage
import com.homebudget.app.ui.common.UserMessages
import com.homebudget.app.ui.navigation.EditTransactionRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject

const val ALL_FAMILY_TAG_UID = "all"

data class EditFormState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val editingId: Long = 0,
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val isRefund: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
    val note: String = "",
    val payee: String = "",
    val payeeSuggestions: List<String> = emptyList(),
    val errors: Map<TxField, String> = emptyMap(),
    val generalError: String? = null,
    val saving: Boolean = false,
    val fractionDigits: Int = 2,
    val currencySymbol: String = "",
    val allAccounts: List<Account> = emptyList(),
    val allCategories: List<Category> = emptyList(),
    val familyPeople: List<ExpensePerson> = emptyList(),
    val familyTags: List<ExpenseTag> = emptyList(),
    val familyTagLinks: List<ExpensePersonTag> = emptyList(),
    val selectedFamilyMemberUids: Set<String> = emptySet(),
    /** Bill this transaction was created from; kept when the transaction is edited. */
    val billId: Long? = null,
    /** Repeating rule that added this transaction (shown as a link), if any. */
    val sourceRecurringId: Long? = null,
    /** > 0 when this form edits a repeating transaction rule rather than a transaction. */
    val recurringId: Long = 0,
    val monthLocked: Boolean = false,
    /** Repeat choice; null = does not repeat. Always set when editing a rule. */
    val repeat: RepeatOption? = null,
    val amountChanges: Boolean = false,
) {
    val isRule: Boolean get() = recurringId != 0L
    val isEdit: Boolean get() = editingId != 0L || isRule

    /** Repeat is offered for new transactions and for rules, not when editing a recorded transaction or a bill line. */
    val canRepeat: Boolean get() = isRule || (editingId == 0L && billId == null)
    val account: Account? get() = allAccounts.firstOrNull { it.id == accountId }
    val toAccount: Account? get() = allAccounts.firstOrNull { it.id == toAccountId }
    val category: Category? get() = allCategories.firstOrNull { it.id == categoryId }

    /** Accounts offered in pickers: active ones plus any archived account already selected. */
    val selectableAccounts: List<Account>
        get() = allAccounts.filter { !it.archived || it.id == accountId || it.id == toAccountId }
            .filter { type == TransactionType.TRANSFER || it.type != AccountType.INVESTMENT || it.id == accountId }

    /**
     * Categories offered for the current type: active main groups and active groups of active main
     * groups, of the right kind, plus whatever is currently selected.
     */
    val selectableCategories: List<Category>
        get() {
            val kind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            val byId = allCategories.associateBy { it.id }
            return allCategories.filter { c ->
                val parentActive = c.parentId?.let { byId[it]?.active } ?: true
                c.kind == kind && ((c.active && parentActive) || c.id == categoryId || c.id == category?.parentId)
            }
        }

    /** "Vegetables · Groceries" for a group, otherwise the category name. */
    val categoryLabel: String
        get() {
            val c = category ?: return ""
            val parent = c.parentId?.let { p -> allCategories.firstOrNull { it.id == p } } ?: return c.name
            return "${c.name} · ${parent.name}"
        }
}

internal fun suggestPayees(payees: List<String>, query: String): List<String> {
    val normalizedQuery = query.trim()
    return payees
        .filter { payee ->
            payee.isNotBlank() &&
                (normalizedQuery.isEmpty() || payee.contains(normalizedQuery, ignoreCase = true)) &&
                !payee.equals(normalizedQuery, ignoreCase = true)
        }
        .distinctBy { it.lowercase() }
        .take(5)
}

@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactions: TransactionRepository,
    private val recurring: RecurringRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val userMessages: UserMessages,
    private val monthLocks: MonthLockRepository,
    private val expenseFamily: ExpenseFamilyRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EditTransactionRoute>()
    private val _state = MutableStateFlow(EditFormState())
    val state: StateFlow<EditFormState> = _state.asStateFlow()
    private var allPayees: List<String> = emptyList()

    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()

    init {
        viewModelScope.launch {
            _state.map { YearMonth.from(it.date) }
                .distinctUntilChanged()
                .flatMapLatest { month -> monthLocks.observeClosed(month) }
                .collect { closed -> _state.update { it.copy(monthLocked = closed) } }
        }
        viewModelScope.launch {
            combine(accounts.observeAccounts(), categories.observeCategories()) { a, c -> a to c }
                .collect { (a, c) -> _state.update { it.copy(allAccounts = a, allCategories = c) } }
        }
        viewModelScope.launch {
            combine(expenseFamily.observeActivePeople(), expenseFamily.observeActiveTags()) { people, tags ->
                people to tags
            }.combine(expenseFamily.observeTagLinks()) { (people, tags), links ->
                Triple(people, tags, links)
            }.collect { (people, tags, links) ->
                _state.update { it.copy(familyPeople = people, familyTags = tags, familyTagLinks = links) }
            }
        }
        viewModelScope.launch {
            transactions.observePayees().collect { payees ->
                allPayees = payees
                _state.update { it.copy(payeeSuggestions = suggestPayees(payees, it.payee)) }
            }
        }
        viewModelScope.launch { load(accounts) }
    }

    private suspend fun load(accounts: AccountRepository) {
        val fmt = MoneyFormatter(settings.settings.first().currencyCode)
        val accountList = accounts.observeAccounts().first()
        val defaultAccount = accountList.firstOrNull {
            !it.archived && it.type != AccountType.INVESTMENT
        }?.id
        if (route.recurringId > 0) {
            val rule = recurring.get(route.recurringId)
            if (rule == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return
            }
            val next = Recurrence.nextDue(rule)
            _state.update {
                it.copy(
                    loading = false, fractionDigits = fmt.fractionDigits, currencySymbol = fmt.symbol,
                    recurringId = rule.id, repeat = RepeatOption.of(rule.frequency, rule.interval) ?: RepeatOption.MONTHLY,
                    type = rule.type, amountText = Money.toEditableText(rule.amount), accountId = rule.accountId,
                    toAccountId = rule.toAccountId, categoryId = rule.categoryId, isRefund = rule.isRefund,
                    date = next.toLocalDate(), time = next.toLocalTime(), note = rule.note, payee = rule.payee,
                    amountChanges = rule.amountChanges,
                )
            }
            return
        }
        val sourceId = if (route.id > 0) route.id else route.duplicateOf
        val source = if (sourceId > 0) transactions.get(sourceId) else null
        if (sourceId > 0 && source == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val sourceMemberUids = source?.let { tx ->
            transactions.memberShares(tx.id).mapTo(linkedSetOf()) { it.personUid }
        }.orEmpty()
        _state.update { s ->
            val base = s.copy(loading = false, fractionDigits = fmt.fractionDigits, currencySymbol = fmt.symbol)
            if (source == null) {
                val type = runCatching { TransactionType.valueOf(route.type) }.getOrDefault(TransactionType.EXPENSE)
                base.copy(type = type, accountId = defaultAccount, repeat = if (route.repeat) RepeatOption.MONTHLY else null)
            } else {
                val isDuplicate = route.id == 0L
                base.copy(
                    editingId = if (isDuplicate) 0 else source.id,
                    type = source.type,
                    amountText = Money.toEditableText(source.amount),
                    accountId = source.accountId,
                    toAccountId = source.toAccountId,
                    categoryId = source.categoryId,
                    isRefund = source.isRefund,
                    date = if (isDuplicate) LocalDate.now() else source.occurredAt.toLocalDate(),
                    time = if (isDuplicate) LocalTime.now().truncatedTo(ChronoUnit.MINUTES) else source.occurredAt.toLocalTime(),
                    note = source.note,
                    payee = source.payee,
                    selectedFamilyMemberUids = sourceMemberUids,
                    billId = if (isDuplicate) null else source.billId,
                    sourceRecurringId = if (isDuplicate) null else source.recurringId,
                )
            }
        }
    }

    suspend fun historyUid(): String? =
        _state.value.editingId.takeIf { it > 0 }?.let { transactions.uid(it) }

    fun setType(t: TransactionType) = _state.update { s ->
        val kind = if (t == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val keepCategory = t != TransactionType.TRANSFER && s.category?.kind == kind
        val accountId = if (t != TransactionType.TRANSFER && s.account?.type == AccountType.INVESTMENT) {
            s.allAccounts.firstOrNull { !it.archived && it.type != AccountType.INVESTMENT }?.id
        } else {
            s.accountId
        }
        s.copy(
            type = t,
            accountId = accountId,
            categoryId = if (keepCategory) s.categoryId else null,
            isRefund = t == TransactionType.EXPENSE && s.isRefund,
            toAccountId = if (t == TransactionType.TRANSFER) s.toAccountId else null,
            selectedFamilyMemberUids = if (t == TransactionType.TRANSFER) emptySet() else s.selectedFamilyMemberUids,
            amountChanges = t == TransactionType.EXPENSE && s.amountChanges,
            errors = emptyMap(),
            generalError = null,
        )
    }

    fun setAmount(text: String) = _state.update { it.copy(amountText = text.take(20), errors = it.errors - TxField.AMOUNT) }
    fun setAccount(id: Long) = _state.update { it.copy(accountId = id, errors = it.errors - TxField.ACCOUNT - TxField.TO_ACCOUNT) }
    fun setToAccount(id: Long) = _state.update { it.copy(toAccountId = id, errors = it.errors - TxField.TO_ACCOUNT) }
    fun setCategory(id: Long) = _state.update { it.copy(categoryId = id, errors = it.errors - TxField.CATEGORY) }
    fun setRefund(r: Boolean) = _state.update {
        it.copy(isRefund = r, amountChanges = if (r) false else it.amountChanges)
    }
    fun setDate(d: LocalDate) = _state.update { it.copy(date = d) }
    fun setTime(t: LocalTime) = _state.update { it.copy(time = t) }
    fun setNote(n: String) = _state.update { it.copy(note = n.take(500)) }
    fun setPayee(p: String) = _state.update {
        val payee = p.take(100)
        it.copy(payee = payee, payeeSuggestions = suggestPayees(allPayees, payee))
    }
    fun toggleFamilyMember(uid: String) = _state.update { state ->
        val selected = if (uid in state.selectedFamilyMemberUids) {
            state.selectedFamilyMemberUids - uid
        } else {
            state.selectedFamilyMemberUids + uid
        }
        state.copy(selectedFamilyMemberUids = selected)
    }

    fun toggleFamilyTag(tagUid: String) = _state.update { state ->
        val memberUids = if (tagUid == ALL_FAMILY_TAG_UID) {
            state.familyPeople.mapTo(linkedSetOf()) { it.uid }
        } else {
            state.familyTagLinks.filter { it.tagUid == tagUid }.mapTo(linkedSetOf()) { it.personUid }
        }
        val selected = if (memberUids.isNotEmpty() && state.selectedFamilyMemberUids.containsAll(memberUids)) {
            state.selectedFamilyMemberUids - memberUids
        } else {
            state.selectedFamilyMemberUids + memberUids
        }
        state.copy(selectedFamilyMemberUids = selected)
    }
    fun setRepeat(r: RepeatOption?) = _state.update { s ->
        if (s.isRule && r == null) s else s.copy(repeat = r, amountChanges = r != null && s.amountChanges)
    }
    fun setAmountChanges(value: Boolean) = _state.update {
        it.copy(amountChanges = value && it.repeat != null && it.type == TransactionType.EXPENSE && !it.isRefund)
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val draft = TransactionDraft(s.type, s.amountText, s.account, s.toAccount, s.category)
        when (val r = TransactionValidator.validate(draft, s.fractionDigits)) {
            is ValidationResult.Invalid -> _state.update { it.copy(errors = r.errors.associate { e -> e.field to e.message }) }
            is ValidationResult.Valid -> viewModelScope.launch {
                _state.update { it.copy(saving = true, generalError = null) }
                try {
                    val repeat = s.repeat
                    if (repeat != null && s.canRepeat) {
                        val rule = RecurringRule(
                            id = s.recurringId,
                            type = s.type,
                            amount = r.amountMinor,
                            accountId = s.accountId!!,
                            toAccountId = if (s.type == TransactionType.TRANSFER) s.toAccountId else null,
                            categoryId = if (s.type == TransactionType.TRANSFER) null else s.categoryId,
                            isRefund = s.type == TransactionType.EXPENSE && s.isRefund,
                            note = s.note,
                            payee = s.payee,
                            frequency = repeat.frequency,
                            interval = repeat.interval,
                            startAt = LocalDateTime.of(s.date, s.time),
                            amountChanges = s.amountChanges,
                        )
                        val id = recurring.save(rule)
                        val next = recurring.get(id)?.let { Recurrence.nextDue(it) }
                        userMessages.show(
                            (if (s.isRule) "Repeating transaction updated" else "Saved · ${repeat.label.lowercase()}") +
                                (next?.let { " · next on ${it.toLocalDate().format(com.homebudget.app.ui.common.DateFormats.date)}" } ?: "")
                        )
                        _done.send(Unit)
                        return@launch
                    }
                    transactions.save(
                        Transaction(
                            id = s.editingId,
                            type = s.type,
                            amount = r.amountMinor,
                            accountId = s.accountId!!,
                            toAccountId = if (s.type == TransactionType.TRANSFER) s.toAccountId else null,
                            categoryId = if (s.type == TransactionType.TRANSFER) null else s.categoryId,
                            isRefund = s.type == TransactionType.EXPENSE && s.isRefund,
                            occurredAt = LocalDateTime.of(s.date, s.time),
                            note = s.note,
                            payee = s.payee,
                            billId = s.billId,
                            recurringId = s.sourceRecurringId,
                        ),
                        memberUids = if (s.type == TransactionType.TRANSFER) emptySet() else s.selectedFamilyMemberUids,
                    )
                    userMessages.show(if (s.isEdit) "Transaction updated" else "Transaction saved")
                    _done.send(Unit)
                } catch (e: IntegrityException) {
                    _state.update { it.copy(generalError = e.message) }
                } finally {
                    _state.update { it.copy(saving = false) }
                }
            }
        }
    }

    fun delete() {
        val ruleId = _state.value.recurringId
        if (ruleId != 0L) {
            viewModelScope.launch {
                recurring.delete(ruleId)
                userMessages.show("Repeating transaction deleted. Transactions already added are kept.")
                _done.send(Unit)
            }
            return
        }
        val id = _state.value.editingId
        if (id == 0L) return
        viewModelScope.launch {
            val removed = transactions.delete(id)
            if (removed != null) {
                userMessages.show(
                    UserMessage("Transaction deleted", actionLabel = "Undo") {
                        runCatching { transactions.restore(removed) }
                            .onFailure { userMessages.show("Could not restore: ${it.message}") }
                    }
                )
            }
            _done.send(Unit)
        }
    }
}
