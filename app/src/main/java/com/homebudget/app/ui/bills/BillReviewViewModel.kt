package com.homebudget.app.ui.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.bill.BillRepository
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.bill.BillSplit
import com.homebudget.app.domain.bill.CategorySuggester
import com.homebudget.app.domain.bill.SplitGroup
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Bill
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BillStatus
import com.homebudget.app.domain.bill.SensitiveInfo
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import com.homebudget.app.domain.money.AmountParseResult
import com.homebudget.app.domain.money.AmountParser
import com.homebudget.app.domain.money.Money
import com.homebudget.app.domain.money.MoneyFormatter
import com.homebudget.app.ui.common.UserMessages
import com.homebudget.app.ui.common.UserMessage
import com.homebudget.app.ui.navigation.BillRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/** One editable line on the review page. [key] is a local id that stays stable while editing. */
data class BillLineUi(
    val key: Long,
    val description: String,
    val amountText: String,
    val categoryId: Long?,
    val included: Boolean = true,
    /** True while the category is the app's suggestion and the user has not changed it. */
    val suggested: Boolean = false,
)

data class BillReviewState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val billId: Long = 0,
    val status: BillStatus = BillStatus.DRAFT,
    val imageFile: String? = null,
    val merchant: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.NOON,
    val accountId: Long? = null,
    val totalText: String = "",
    val lines: List<BillLineUi> = emptyList(),
    val ocrText: String? = null,
    val sensitiveKinds: Set<SensitiveInfo.Kind> = emptySet(),
    val learnedCategories: Map<String, Long> = emptyMap(),
    val preserveLearnedKeys: Set<String> = emptySet(),
    val pendingCategoryChange: PendingCategoryChange? = null,
    val pendingPastCategoryChange: PendingPastCategoryChange? = null,
    val reading: Boolean = false,
    val readMessage: String? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val showProblems: Boolean = false,
    val transactionCount: Int = 0,
    val fractionDigits: Int = 2,
    val currencySymbol: String = "",
    val allAccounts: List<Account> = emptyList(),
    val allCategories: List<Category> = emptyList(),
) {
    val categoriesById: Map<Long, Category> get() = allCategories.associateBy { it.id }
    val account: Account? get() = allAccounts.firstOrNull { it.id == accountId }
    val selectableAccounts: List<Account> get() = allAccounts.filter { !it.archived || it.id == accountId }

    /** Active expense main groups and groups (plus anything already used on this bill). */
    val selectableCategories: List<Category>
        get() {
            val used = lines.mapNotNullTo(HashSet()) { it.categoryId }
            val byId = categoriesById
            val usedParents = used.mapNotNullTo(HashSet()) { byId[it]?.parentId }
            return allCategories.filter { c ->
                val parentActive = c.parentId?.let { byId[it]?.active } ?: true
                c.kind == CategoryKind.EXPENSE && ((c.active && parentActive) || c.id in used || c.id in usedParents)
            }
        }

    fun amountOf(line: BillLineUi): Long? =
        (AmountParser.parse(line.amountText, fractionDigits) as? AmountParseResult.Valid)?.minor

    fun amountError(line: BillLineUi): String? = when (AmountParser.parse(line.amountText, fractionDigits)) {
        is AmountParseResult.Valid -> null
        AmountParseResult.Empty -> "Required"
        AmountParseResult.TooManyDecimals -> "Too many decimals"
        AmountParseResult.TooLarge -> "Too large"
        AmountParseResult.NotPositive -> "Must be > 0"
        AmountParseResult.NotANumber -> "Not a number"
    }

    val total: Long? get() = (AmountParser.parse(totalText, fractionDigits) as? AmountParseResult.Valid)?.minor

    /** Lines as domain items; an invalid amount becomes 0 so that validation reports it. */
    val items: List<BillItem>
        get() = lines.mapIndexed { i, l ->
            BillItem(billId = billId, position = i, description = l.description, amount = amountOf(l) ?: 0, categoryId = l.categoryId, included = l.included)
        }

    val itemsTotal: Long get() = BillSplit.includedTotal(items)

    /** Bill total minus included items, when the bill total is known (e.g. tax or charges not itemised). */
    val difference: Long? get() = total?.let { it - itemsTotal }

    val split: List<SplitGroup> get() = BillSplit.summary(items, categoriesById)
    val postingCount: Int get() = BillSplit.postingLines(items).size
    val problems: List<String> get() = BillSplit.problems(items, accountId, categoriesById)
    val isPosted: Boolean get() = status == BillStatus.POSTED
}

data class PendingCategoryChange(
    val lineKey: Long,
    val categoryId: Long,
    val itemDescription: String,
    val learnedCategoryId: Long,
)

data class PendingPastCategoryChange(
    val itemDescription: String,
    val previousCategoryId: Long,
    val categoryId: Long,
    val foundEntries: Int,
)

@HiltViewModel
class BillReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bills: BillRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val settings: SettingsRepository,
    val images: BillImageStore,
    private val messages: UserMessages,
) : ViewModel() {

    private val billId = savedStateHandle.toRoute<BillRoute>().id
    private val _state = MutableStateFlow(BillReviewState(billId = billId))
    val state: StateFlow<BillReviewState> = _state.asStateFlow()

    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()

    private var nextKey = 1L

    init {
        viewModelScope.launch {
            combine(accounts.observeAccounts(), categories.observeCategories()) { a, c -> a to c }
                .collect { (a, c) -> _state.update { it.copy(allAccounts = a, allCategories = c) } }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val fmt = MoneyFormatter(settings.settings.first().currencyCode)
        val bill = bills.get(billId)
        if (bill == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val items = bills.items(billId)
        val learnedCategories = bills.learnedRules()
        val txCount = if (bill.status == BillStatus.POSTED) bills.transactionCount(billId) else 0
        _state.update {
            it.copy(
                loading = false,
                status = bill.status,
                imageFile = bill.imageFile,
                merchant = bill.merchant,
                date = bill.billDate.toLocalDate(),
                time = bill.billDate.toLocalTime(),
                accountId = bill.accountId,
                totalText = bill.total?.let(Money::toEditableText) ?: "",
                lines = items.map { item -> item.toLine() },
                ocrText = bill.ocrText?.let(SensitiveInfo::mask),
                sensitiveKinds = bill.ocrText?.let(SensitiveInfo::find).orEmpty(),
                learnedCategories = learnedCategories,
                transactionCount = txCount,
                fractionDigits = fmt.fractionDigits,
                currencySymbol = fmt.symbol,
            )
        }
        // A fresh photo bill: read it straight away.
        if (bill.status == BillStatus.DRAFT && bill.imageFile != null && bill.ocrText == null && items.isEmpty()) readText()
    }

    private fun BillItem.toLine() = BillLineUi(nextKey++, description, if (amount > 0) Money.toEditableText(amount) else "", categoryId, included)

    /** Reads the photo on the device and replaces the line items with what was found. */
    fun readText() {
        if (_state.value.reading) return
        viewModelScope.launch {
            _state.update { it.copy(reading = true, readMessage = null) }
            try {
                val reading = bills.read(billId)
                val r = reading.receipt
                _state.update { s ->
                    s.copy(
                        merchant = s.merchant.ifBlank { r.merchant.orEmpty() },
                        date = r.date ?: s.date,
                        time = r.time ?: s.time,
                        totalText = r.total?.let(Money::toEditableText) ?: s.totalText,
                        lines = reading.items.map { item -> item.toLine().copy(suggested = item.categoryId != null) },
                        ocrText = r.text,
                        sensitiveKinds = reading.sensitiveKinds,
                        dirty = true,
                        readMessage = when {
                            reading.items.isEmpty() && r.text.isBlank() ->
                                "No text was found. Try a sharper, well-lit photo, or add the items yourself."
                            reading.items.isEmpty() ->
                                "Text was read, but no item lines with prices were recognised. Add the items yourself."
                            else -> {
                                val suggested = reading.items.count { it.categoryId != null }
                                "Found ${reading.items.size} items; $suggested have a suggested group. Check each line before approving."
                            }
                        },
                    )
                }
            } catch (e: IOException) {
                _state.update { it.copy(readMessage = e.message ?: "The bill could not be read") }
            } catch (e: IntegrityException) {
                _state.update { it.copy(readMessage = e.message) }
            } finally {
                _state.update { it.copy(reading = false) }
            }
        }
    }

    private fun edit(block: (BillReviewState) -> BillReviewState) = _state.update { block(it).copy(dirty = true) }

    fun setMerchant(v: String) = edit { it.copy(merchant = v.take(100)) }
    fun setDate(v: LocalDate) = edit { it.copy(date = v) }
    fun setTime(v: LocalTime) = edit { it.copy(time = v) }
    fun setAccount(id: Long) = edit { it.copy(accountId = id) }
    fun setTotal(v: String) = edit { it.copy(totalText = v.take(20)) }

    private fun editLine(key: Long, block: (BillLineUi) -> BillLineUi) =
        edit { s -> s.copy(lines = s.lines.map { if (it.key == key) block(it) else it }) }

    fun setDescription(key: Long, v: String) = editLine(key) { it.copy(description = v.take(BillRepository.MAX_DESCRIPTION)) }
    fun setAmount(key: Long, v: String) = editLine(key) { it.copy(amountText = v.take(20)) }
    fun setCategory(key: Long, id: Long) {
        val s = _state.value
        val line = s.lines.firstOrNull { it.key == key } ?: return
        if (line.categoryId == id) return
        val learnedCategoryId = s.learnedCategories[CategorySuggester.key(line.description)]
        if (learnedCategoryId != null && learnedCategoryId != id) {
            _state.update {
                it.copy(
                    pendingCategoryChange = PendingCategoryChange(key, id, line.description, learnedCategoryId),
                )
            }
        } else {
            editLine(key) { it.copy(categoryId = id, suggested = false) }
        }
    }

    fun resolveCategoryChange(always: Boolean) {
        val state = _state.value
        val pending = state.pendingCategoryChange ?: return
        val line = state.lines.firstOrNull { it.key == pending.lineKey } ?: run {
            _state.update { it.copy(pendingCategoryChange = null) }
            return
        }
        val key = CategorySuggester.key(line.description)
        if (!always) {
            edit {
                it.copy(
                    lines = it.lines.map { current ->
                        if (current.key == pending.lineKey) current.copy(categoryId = pending.categoryId, suggested = false)
                        else current
                    },
                    preserveLearnedKeys = it.preserveLearnedKeys + key,
                    pendingCategoryChange = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                bills.rememberCategory(line.description, pending.categoryId)
                val foundEntries = bills.countPastCategoryMatches(line.description, pending.categoryId, excludeBillId = billId)
                edit {
                    it.copy(
                        lines = it.lines.map { current ->
                            if (current.key == pending.lineKey) current.copy(categoryId = pending.categoryId, suggested = false)
                            else current
                        },
                        learnedCategories = it.learnedCategories + (key to pending.categoryId),
                        pendingCategoryChange = null,
                        pendingPastCategoryChange = if (foundEntries > 0) {
                            PendingPastCategoryChange(
                                itemDescription = line.description,
                                previousCategoryId = pending.learnedCategoryId,
                                categoryId = pending.categoryId,
                                foundEntries = foundEntries,
                            )
                        } else {
                            null
                        },
                    )
                }
            } catch (e: IntegrityException) {
                messages.show(e.message ?: "Could not update the learned group")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun cancelPastCategoryChange() = _state.update { it.copy(pendingPastCategoryChange = null) }

    fun resolvePastCategoryChange() {
        val pending = _state.value.pendingPastCategoryChange ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val result = bills.changePastCategories(
                    pending.itemDescription,
                    pending.categoryId,
                    previousCategoryId = pending.previousCategoryId,
                    excludeBillId = billId,
                )
                val bulk = result.writeResult
                val message = when {
                    result.skippedClosedMonths > 0 && bulk.pendingApproval > 0 ->
                        "Change request sent for ${result.changedEntries} past entries; ${result.skippedClosedMonths} in closed months skipped."
                    result.skippedClosedMonths > 0 ->
                        "${result.changedEntries} changed, ${result.skippedClosedMonths} in closed months skipped. Only the Admin can change those."
                    bulk.pendingApproval > 0 -> "Change request sent for ${result.changedEntries} past entries."
                    else -> "Changed ${result.changedEntries} past entries."
                }
                val undoAction = if (bulk.applied > 0 || bulk.pendingApproval > 0) {
                    suspend {
                        bills.undoPastCategoryChange(bulk.bulkId)
                        Unit
                    }
                } else {
                    null
                }
                messages.show(UserMessage(message, if (undoAction == null) null else "Undo", undoAction))
                _state.update { it.copy(pendingPastCategoryChange = null, dirty = true) }
            } catch (e: IntegrityException) {
                messages.show(e.message ?: "Could not change past entries")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun cancelCategoryChange() = _state.update { it.copy(pendingCategoryChange = null) }
    fun setIncluded(key: Long, v: Boolean) = editLine(key) { it.copy(included = v) }
    fun removeLine(key: Long) = edit { s -> s.copy(lines = s.lines.filter { it.key != key }) }

    fun addLine(description: String = "", amount: Long? = null) = edit { s ->
        s.copy(lines = s.lines + BillLineUi(nextKey++, description, amount?.let(Money::toEditableText) ?: "", null))
    }

    /** Adds the unexplained part of the bill total (e.g. tax) as its own line. */
    fun addDifferenceLine() {
        val diff = _state.value.difference ?: return
        if (diff > 0) addLine("Tax / other charges", diff)
    }

    /** Applies [categoryId] to every included line that has no category yet. */
    fun applyToUnassigned(categoryId: Long) = edit { s ->
        s.copy(lines = s.lines.map { if (it.included && it.categoryId == null) it.copy(categoryId = categoryId, suggested = false) else it })
    }

    private fun currentBill(s: BillReviewState) = Bill(
        id = billId,
        merchant = s.merchant,
        billDate = LocalDateTime.of(s.date, s.time),
        total = s.total,
        accountId = s.accountId,
        status = s.status,
    )

    fun saveDraft(thenClose: Boolean = true) {
        val s = _state.value
        if (s.saving || s.isPosted) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                bills.saveDraft(currentBill(s), s.items)
                _state.update { it.copy(dirty = false) }
                messages.show("Bill saved as draft")
                if (thenClose) _done.send(Unit)
            } catch (e: IntegrityException) {
                messages.show(e.message ?: "Could not save the bill")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Validates, then adds (or replaces) the bill's expense transactions. */
    fun approve() {
        val s = _state.value
        if (s.saving) return
        if (s.problems.isNotEmpty()) {
            _state.update { it.copy(showProblems = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val n = bills.approve(currentBill(s), s.items, s.preserveLearnedKeys)
                messages.show(
                    if (s.isPosted) "Bill updated: $n ${if (n == 1) "expense" else "expenses"}"
                    else "Added $n ${if (n == 1) "expense" else "expenses"} from the bill"
                )
                _state.update { it.copy(dirty = false) }
                _done.send(Unit)
            } catch (e: IntegrityException) {
                _state.update { it.copy(showProblems = true) }
                messages.show(e.message ?: "Could not add the bill")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            val n = bills.delete(billId)
            messages.show(if (n > 0) "Bill and its $n ${if (n == 1) "expense" else "expenses"} deleted" else "Bill deleted")
            _done.send(Unit)
        }
    }
}
