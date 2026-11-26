package com.homebudget.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.data.backup.BackupRepository
import com.homebudget.app.data.bill.BillImageStore
import com.homebudget.app.data.bill.BillRepository
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.data.repository.AccountRepository
import com.homebudget.app.data.repository.BudgetRepository
import com.homebudget.app.data.repository.CategoryRepository
import com.homebudget.app.data.repository.DataMaintenanceRepository
import com.homebudget.app.data.repository.IntegrityException
import com.homebudget.app.data.repository.PurchaseRepository
import com.homebudget.app.data.repository.RecurringRepository
import com.homebudget.app.data.repository.TransactionRepository
import com.homebudget.app.data.settings.SettingsRepository
import com.homebudget.app.domain.backup.BackupDecodeResult
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.domain.backup.BackupValidator
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.OcrLine
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.BillItem
import com.homebudget.app.domain.model.BudgetLimit
import com.homebudget.app.domain.model.Frequency
import com.homebudget.app.domain.model.RecurringRule
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.money.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** Budgets, repeating transactions, purchases and backup v3 against an in-memory Room database. */
@RunWith(AndroidJUnit4::class)
class PlanningRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var images: BillImageStore
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository
    private lateinit var accounts: AccountRepository
    private lateinit var budgets: BudgetRepository
    private lateinit var recurring: RecurringRepository
    private lateinit var bills: BillRepository
    private lateinit var repos: TestRepositories
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val noOcr = object : BillTextRecognizer {
        override suspend fun recognize(image: File): List<OcrLine> = emptyList()
    }

    private class MemoryStorage : BackupStorage {
        val files = HashMap<String, ByteArray>()
        override suspend fun write(target: String, bytes: ByteArray) { files[target] = bytes }
        override suspend fun read(source: String): ByteArray = files[source] ?: throw java.io.IOException("missing")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedDefaultsCallback())
            .allowMainThreadQueries()
            .build()
        images = BillImageStore(context)
        repos = TestRepositories(db, clock)
        transactions = repos.transactions()
        categories = repos.categories()
        accounts = repos.accounts()
        budgets = BudgetRepository(db, repos.writer)
        recurring = RecurringRepository(db, transactions, clock, repos.writer, monthLocks = repos.monthLocks)
        bills = BillRepository(db, clock, images, noOcr, repos.writer, repos.expenseFamily)
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    private suspend fun cat(name: String) = categories.observeCategories().first().first { it.name == name }.id
    private suspend fun cashId() = accounts.observeAccounts().first().first().id
    private suspend fun bankId() = accounts.save(Account(name = "Bank", type = AccountType.SAVINGS))
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun later(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private fun repoAt(c: Clock): RecurringRepository {
        val r = TestRepositories(db, c)
        return RecurringRepository(db, r.transactions(), c, r.writer, monthLocks = r.monthLocks)
    }

    private suspend fun rentRule(start: String = "2026-09-20T09:00", amount: Long = 50_000) = RecurringRule(
        type = TransactionType.EXPENSE, amount = amount, accountId = cashId(), categoryId = cat("Groceries"),
        frequency = Frequency.MONTHLY, startAt = at(start),
    )

    private suspend fun expectIntegrity(block: suspend () -> Unit) {
        try {
            block()
            fail("expected IntegrityException")
        } catch (e: IntegrityException) { /* expected */ }
    }

    private val nov = YearMonth.of(2026, 11)
    private val oct = YearMonth.of(2026, 10)

    // ---- Budgets ----

    @Test
    fun budgetSaveRejectsInvalidInput() = runTest {
        val groceries = cat("Groceries")
        expectIntegrity { budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = 0)) }
        expectIntegrity { budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = -5)) }
        expectIntegrity { budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = Money.MAX_AMOUNT + 1)) }
        expectIntegrity { budgets.save(BudgetLimit(month = nov, categoryId = cat("Salary"), limit = 1_000)) }
        expectIntegrity { budgets.save(BudgetLimit(month = nov, categoryId = 99_999, limit = 1_000)) }
        assertTrue(budgets.observeForMonth(nov).first().isEmpty())
        // The maximum itself is allowed.
        budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = Money.MAX_AMOUNT))
        assertEquals(Money.MAX_AMOUNT, budgets.observeForMonth(nov).first().single().limit)
    }

    @Test
    fun budgetIsUniquePerMonthAndCategory() = runTest {
        val groceries = cat("Groceries")
        val id1 = budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = 10_000))
        val id2 = budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = 20_000))
        assertEquals(id1, id2)
        val list = budgets.observeForMonth(nov).first()
        assertEquals(1, list.size)
        assertEquals(20_000L, list.single().limit)

        // Another month or category is a separate budget.
        budgets.save(BudgetLimit(month = oct, categoryId = groceries, limit = 5_000))
        budgets.save(BudgetLimit(month = nov, categoryId = cat("Dining"), limit = 5_000))
        assertEquals(2, budgets.observeForMonth(nov).first().size)
        assertEquals(1, budgets.observeForMonth(oct).first().size)
    }

    @Test
    fun overallBudgetIsAlsoUniqueAndEditsKeepId() = runTest {
        val id1 = budgets.save(BudgetLimit(month = nov, categoryId = null, limit = 100_000))
        val id2 = budgets.save(BudgetLimit(month = nov, categoryId = null, limit = 120_000))
        assertEquals(id1, id2)
        assertEquals(listOf(120_000L), budgets.observeForMonth(nov).first().map { it.limit })

        val existing = budgets.observeForMonth(nov).first().single()
        budgets.save(existing.copy(limit = 130_000))
        assertEquals(listOf(130_000L), budgets.observeForMonth(nov).first().map { it.limit })
    }

    @Test
    fun editingABudgetOntoAnotherCategoryMergesInsteadOfDuplicating() = runTest {
        val groceries = cat("Groceries")
        val dining = cat("Dining")
        budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = 10_000))
        budgets.save(BudgetLimit(month = nov, categoryId = dining, limit = 3_000))
        val diningBudget = budgets.observeForMonth(nov).first().first { it.categoryId == dining }
        budgets.save(diningBudget.copy(categoryId = groceries, limit = 7_000))
        val list = budgets.observeForMonth(nov).first()
        assertEquals(1, list.size)
        assertEquals(groceries, list.single().categoryId)
        assertEquals(7_000L, list.single().limit)
    }

    @Test
    fun budgetDelete() = runTest {
        val id = budgets.save(BudgetLimit(month = nov, categoryId = cat("Groceries"), limit = 10_000))
        budgets.delete(id)
        assertTrue(budgets.observeForMonth(nov).first().isEmpty())
    }

    @Test
    fun budgetCopyAddsMissingAndSkipsExisting() = runTest {
        val groceries = cat("Groceries")
        val dining = cat("Dining")
        budgets.save(BudgetLimit(month = oct, categoryId = groceries, limit = 10_000))
        budgets.save(BudgetLimit(month = oct, categoryId = dining, limit = 4_000))
        budgets.save(BudgetLimit(month = oct, categoryId = null, limit = 50_000))
        budgets.save(BudgetLimit(month = nov, categoryId = groceries, limit = 99_000)) // already budgeted

        assertEquals(2, budgets.copy(oct, nov))

        val byCat = budgets.observeForMonth(nov).first().associate { it.categoryId to it.limit }
        assertEquals(mapOf(groceries to 99_000L, dining to 4_000L, null to 50_000L), byCat)
        assertTrue(budgets.observeForMonth(nov).first().all { it.month == nov })
        assertEquals(0, budgets.copy(oct, nov)) // idempotent
    }

    @Test
    fun observeLatestMonthBefore() = runTest {
        assertNull(budgets.observeLatestMonthBefore(nov).first())
        budgets.save(BudgetLimit(month = YearMonth.of(2026, 8), categoryId = null, limit = 1_000))
        budgets.save(BudgetLimit(month = oct, categoryId = null, limit = 1_000))
        budgets.save(BudgetLimit(month = YearMonth.of(2026, 12), categoryId = null, limit = 1_000))
        assertEquals(oct, budgets.observeLatestMonthBefore(nov).first())
        assertEquals(YearMonth.of(2026, 8), budgets.observeLatestMonthBefore(oct).first())
        assertNull(budgets.observeLatestMonthBefore(YearMonth.of(2026, 8)).first())
    }

    // ---- Recurring ----

    @Test
    fun ruleSaveValidatesLikeATransaction() = runTest {
        val base = rentRule()
        expectIntegrity { recurring.save(base.copy(categoryId = null)) }
        expectIntegrity { recurring.save(base.copy(categoryId = cat("Salary"))) } // wrong kind
        expectIntegrity { recurring.save(base.copy(categoryId = 99_999)) }
        expectIntegrity { recurring.save(base.copy(amount = 0)) }
        expectIntegrity { recurring.save(base.copy(accountId = 99_999)) }
        expectIntegrity { recurring.save(base.copy(interval = 0)) }
        expectIntegrity { recurring.save(base.copy(interval = 367)) }
        expectIntegrity {
            recurring.save(base.copy(type = TransactionType.TRANSFER, categoryId = null, toAccountId = cashId()))
        }
        expectIntegrity { recurring.save(base.copy(type = TransactionType.TRANSFER, categoryId = null, toAccountId = null)) }
        assertTrue(recurring.observeRules().first().isEmpty())
        assertTrue(transactions.observeAll().first().isEmpty())
    }

    @Test
    fun savingAPastRuleBackfillsEveryDueOccurrence() = runTest {
        val id = recurring.save(rentRule())

        val txns = transactions.observeAll().first().sortedBy { it.occurredAt }
        assertEquals(
            listOf(at("2026-09-20T09:00"), at("2026-10-20T09:00"), at("2026-11-20T09:00")),
            txns.map { it.occurredAt },
        )
        assertTrue(txns.all { it.recurringId == id && it.amount == 50_000L && it.type == TransactionType.EXPENSE })
        assertEquals(3, recurring.get(id)!!.postedCount)
    }

    @Test
    fun postDueTwiceDoesNotDuplicate() = runTest {
        recurring.save(rentRule())
        assertEquals(0, recurring.postDue())
        assertEquals(0, recurring.postDue())
        assertEquals(3, transactions.observeAll().first().size)
    }

    @Test
    fun laterRepositoryPostsOnlyNewOccurrences() = runTest {
        val id = recurring.save(rentRule())
        val next = repoAt(later("2027-01-25T10:00:00Z"))
        assertEquals(2, next.postDue()) // Dec 20 and Jan 20
        val txns = transactions.observeAll().first()
        assertEquals(5, txns.size)
        assertEquals(5, next.get(id)!!.postedCount)
        assertEquals(0, next.postDue())
        assertEquals(5, transactions.observeAll().first().size)
    }

    @Test
    fun ruleStartingInTheFuturePostsNothing() = runTest {
        val id = recurring.save(rentRule(start = "2026-12-01T09:00"))
        assertTrue(transactions.observeAll().first().isEmpty())
        assertEquals(0, recurring.get(id)!!.postedCount)
        assertEquals(0, recurring.postDue())
    }

    @Test
    fun occurrenceExactlyNowIsDue() = runTest {
        recurring.save(rentRule(start = "2026-11-20T10:00"))
        assertEquals(1, transactions.observeAll().first().size)
    }

    @Test
    fun changingBillWaitsForConfirmedAmountAndAdvancesWithTheExpense() = runTest {
        val id = recurring.save(rentRule(start = "2026-09-20T09:00").copy(amountChanges = true))
        assertEquals(0, recurring.postDue())
        assertTrue(transactions.observeAll().first().isEmpty())
        expectIntegrity { recurring.postDueOccurrence(id) }
        assertTrue(transactions.observeAll().first().isEmpty())

        recurring.postDueOccurrence(id, amountMinor = 64_500)

        val transaction = transactions.observeAll().first().single()
        assertEquals(64_500L, transaction.amount)
        assertEquals(id, transaction.recurringId)
        assertEquals(1, recurring.get(id)?.postedCount)
    }

    @Test
    fun editingAmountKeepsPostedCountButChangingStartRestartsCounting() = runTest {
        val id = recurring.save(rentRule())
        recurring.save(recurring.get(id)!!.copy(amount = 60_000))
        assertEquals(3, recurring.get(id)!!.postedCount)
        assertEquals(60_000L, recurring.get(id)!!.amount)
        assertEquals(3, transactions.observeAll().first().size) // nothing re-posted
        assertTrue(transactions.observeAll().first().all { it.amount == 50_000L }) // history untouched

        recurring.save(recurring.get(id)!!.copy(startAt = at("2026-11-20T09:00")))
        // Counting restarts: occurrence 0 (Nov 20 09:00) is due again and is posted once.
        assertEquals(1, recurring.get(id)!!.postedCount)
        assertEquals(4, transactions.observeAll().first().size)
    }

    @Test
    fun savingAnUnknownRuleIdFails() = runTest {
        expectIntegrity { recurring.save(rentRule().copy(id = 12_345)) }
    }

    @Test
    fun pausedRuleDoesNotPostAndResumeSkipsMissedOccurrences() = runTest {
        val id = recurring.save(rentRule())
        recurring.setActive(id, false)
        assertFalse(recurring.get(id)!!.active)

        val future = repoAt(later("2027-02-25T10:00:00Z"))
        assertEquals(0, future.postDue())
        assertEquals(3, transactions.observeAll().first().size)

        future.setActive(id, true)
        val resumed = future.get(id)!!
        assertTrue(resumed.active)
        assertEquals(6, resumed.postedCount) // Dec, Jan, Feb skipped
        assertEquals(0, future.postDue())
        assertEquals(3, transactions.observeAll().first().size)

        // The next occurrence (Mar 20) posts normally.
        val march = repoAt(later("2027-03-20T09:30:00Z"))
        assertEquals(1, march.postDue())
        assertEquals(at("2027-03-20T09:00"), transactions.observeAll().first().maxOf { it.occurredAt })
    }

    @Test
    fun resumingARuleThatWasNeverPausedChangesNothing() = runTest {
        val id = recurring.save(rentRule())
        recurring.setActive(id, true)
        assertEquals(3, recurring.get(id)!!.postedCount)
    }

    @Test
    fun deletingARuleKeepsItsTransactionsWithoutTheLink() = runTest {
        val id = recurring.save(rentRule())
        recurring.delete(id)
        assertNull(recurring.get(id))
        val txns = transactions.observeAll().first()
        assertEquals(3, txns.size)
        assertTrue(txns.all { it.recurringId == null })
        assertEquals(0, recurring.postDue())
    }

    @Test
    fun accountInUseByARuleCannotBeDeleted() = runTest {
        val bank = bankId()
        val id = recurring.save(rentRule(start = "2026-12-01T09:00").copy(accountId = bank))
        assertFalse(accounts.deleteIfUnused(bank))
        recurring.delete(id)
        assertTrue(accounts.deleteIfUnused(bank))
    }

    @Test
    fun transferRulePostsTransfers() = runTest {
        val bank = bankId()
        val id = recurring.save(
            RecurringRule(
                type = TransactionType.TRANSFER, amount = 1_000, accountId = cashId(), toAccountId = bank,
                frequency = Frequency.WEEKLY, startAt = at("2026-11-06T08:00"),
            )
        )
        val txns = transactions.observeAll().first()
        assertEquals(3, txns.size) // Nov 6, 13, 20
        assertTrue(txns.all { it.type == TransactionType.TRANSFER && it.toAccountId == bank && it.categoryId == null && it.recurringId == id })
    }

    @Test
    fun ruleThatCanNoLongerBePostedIsPausedWithoutPartialPosting() = runTest {
        val bank = bankId()
        val id = recurring.save(
            RecurringRule(
                type = TransactionType.TRANSFER, amount = 1_000, accountId = cashId(), toAccountId = bank,
                frequency = Frequency.MONTHLY, startAt = at("2026-12-01T09:00"),
            )
        )
        // Corrupt the rule so a due occurrence fails validation (source == destination).
        db.openHelper.writableDatabase.execSQL("UPDATE recurring_rules SET to_account_id = account_id WHERE id = $id")
        val future = repoAt(later("2027-01-25T10:00:00Z"))
        assertEquals(0, future.postDue())
        assertFalse(future.get(id)!!.active)
        assertTrue(transactions.observeAll().first().isEmpty())
    }

    // ---- Data maintenance ----

    @Test
    fun clearAllDataDeletesRulesAndTheirTransactions() = runTest {
        recurring.save(rentRule())
        budgets.save(BudgetLimit(month = nov, categoryId = null, limit = 1_000))
        repos.maintenance(images).clearAllData()
        assertTrue(recurring.observeRules().first().isEmpty())
        assertTrue(transactions.observeAll().first().isEmpty())
        assertTrue(budgets.observeForMonth(nov).first().isEmpty())
        assertEquals(0, recurring.postDue())
        assertEquals(1, accounts.observeAccounts().first().size) // defaults restored
    }

    // ---- Purchases ----

    @Test
    fun purchasedLinesAreIncludedLinesOfPostedBillsOnly() = runTest {
        val veg = cat("Vegetables")
        val billDate = LocalDateTime.of(2026, 11, 18, 19, 5)

        val posted = bills.createDraft(null, cashId())
        bills.approve(
            bills.get(posted)!!.copy(merchant = "Fresh Mart", billDate = billDate),
            listOf(
                BillItem(position = 0, description = "Tomato", amount = 3_000, categoryId = veg),
                BillItem(position = 1, description = "Misread", amount = 99_900, categoryId = null, included = false),
            ),
        )
        val draft = bills.createDraft(null, cashId())
        bills.saveDraft(
            bills.get(draft)!!.copy(merchant = "Other", billDate = billDate),
            listOf(BillItem(position = 0, description = "Onion", amount = 2_000, categoryId = veg)),
        )
        assertEquals(1, bills.items(draft).size)

        val lines = PurchaseRepository(db).observePurchasedLines().first()
        assertEquals(1, lines.size)
        assertEquals("Tomato", lines.single().description)
        assertEquals(3_000L, lines.single().amount)
        assertEquals("Fresh Mart", lines.single().merchant)
        assertEquals(billDate.toLocalDate(), lines.single().date)
    }

    // ---- Backup ----

    private fun backupRepo(storage: BackupStorage = MemoryStorage(), name: String = "s.preferences_pb"): BackupRepository {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = dsScope) { File(tmp.root, name) })
        return repos.backup(storage, settings, images)
    }

    @Test
    fun snapshotIncludesRulesAndTransactionLinks() = runTest {
        val id = recurring.save(rentRule())
        val snapshot = backupRepo().createSnapshot()
        assertEquals(8, snapshot.formatVersion)
        assertEquals(1, snapshot.recurringRules.size)
        val r = snapshot.recurringRules.single()
        assertEquals(id, r.id)
        assertEquals("MONTHLY", r.frequency)
        assertEquals(3, r.postedCount)
        assertEquals(50_000L, r.amount)
        assertEquals("2026-09-20T09:00", r.startAt)
        assertEquals(3, snapshot.transactions.count { it.recurringId == id })
        assertNull(BackupValidator.validate(snapshot))
    }

    @Test
    fun restoreRoundTripKeepsRulesAndLinks() = runTest {
        val storage = MemoryStorage()
        val backup = backupRepo(storage)
        val id = recurring.save(rentRule())
        recurring.setActive(id, false)
        val rulesBefore = recurring.observeRules().first()
        val txBefore = transactions.observeAll().first()

        backup.exportTo("f")
        val file = (backup.inspect("f") as BackupDecodeResult.Valid).file
        assertEquals(1, file.recurringRules.size)

        recurring.delete(id) // unlink transactions, then restore must relink them
        assertTrue(transactions.observeAll().first().all { it.recurringId == null })
        backup.restore(file)

        assertEquals(rulesBefore, recurring.observeRules().first())
        assertEquals(txBefore, transactions.observeAll().first())
        assertTrue(transactions.observeAll().first().all { it.recurringId == id })
        assertFalse(recurring.get(id)!!.active)
        assertEquals(0, recurring.postDue()) // paused rule stays paused after restore
    }

    @Test
    fun backupRoundTripKeepsChangingAmountRuleFlag() = runTest {
        val storage = MemoryStorage()
        val backup = backupRepo(storage)
        val id = recurring.save(rentRule(start = "2027-01-20T09:00").copy(amountChanges = true))

        backup.exportTo("variable-bill")
        val file = (backup.inspect("variable-bill") as BackupDecodeResult.Valid).file
        assertEquals(8, file.formatVersion)
        assertTrue(file.recurringRules.single().amountChanges)

        recurring.delete(id)
        backup.restore(file)

        assertTrue(recurring.get(id)!!.amountChanges)
    }

    @Test
    fun validatorRejectsBrokenRules() = runTest {
        recurring.save(rentRule())
        val snapshot = backupRepo().createSnapshot()
        assertNull(BackupValidator.validate(snapshot))
        fun rules(f: (com.homebudget.app.domain.backup.BackupRecurringRule) -> com.homebudget.app.domain.backup.BackupRecurringRule) =
            snapshot.copy(recurringRules = snapshot.recurringRules.map(f))

        assertNotNull(BackupValidator.validate(rules { it.copy(frequency = "HOURLY") }))
        assertNotNull(BackupValidator.validate(rules { it.copy(interval = 0) }))
        assertNotNull(BackupValidator.validate(rules { it.copy(interval = 367) }))
        assertNotNull(BackupValidator.validate(rules { it.copy(postedCount = -1) }))
        assertNotNull(BackupValidator.validate(rules { it.copy(amount = 0) }))
        assertNotNull(BackupValidator.validate(rules { it.copy(accountId = 99_999) }))
        val salary = cat("Salary")
        assertNotNull(BackupValidator.validate(rules { it.copy(categoryId = salary) })) // wrong kind
        assertNotNull(BackupValidator.validate(rules { it.copy(categoryId = null) }))
        assertNotNull(BackupValidator.validate(rules { it.copy(startAt = "not a date") }))
        assertNotNull(BackupValidator.validate(rules { it.copy(type = "BOGUS") }))
        assertNotNull(BackupValidator.validate(snapshot.copy(recurringRules = snapshot.recurringRules + snapshot.recurringRules))) // duplicate id
        assertNotNull(BackupValidator.validate(snapshot.copy(transactions = snapshot.transactions.map { it.copy(recurringId = 99_999) })))
        assertNotNull(BackupValidator.validate(snapshot.copy(recurringRules = emptyList()))) // transactions now dangle
    }

    @Test
    fun backupWithoutRulesStillRestoresAndClearsExistingRules() = runTest {
        val storage = MemoryStorage()
        val backup = backupRepo(storage)
        recurring.save(rentRule())
        val v2 = """
            {"format":"homebudget.backup","formatVersion":2,"exportedAtEpochMillis":1,"currencyCode":"INR",
             "accounts":[{"id":1,"name":"Cash","type":"CASH","openingBalance":0,"includeInTotal":true,"archived":false,"sortOrder":0,"createdAt":1,"updatedAt":1}],
             "categories":[{"id":1,"name":"Groceries","kind":"EXPENSE","iconKey":"groceries","colorArgb":4281236786,"sortOrder":0,"active":true,"createdAt":1,"updatedAt":1}],
             "transactions":[{"id":1,"type":"EXPENSE","amount":500,"accountId":1,"categoryId":1,"occurredAt":"2026-11-01T10:00:00","createdAt":1,"updatedAt":1}],
             "budgets":[],"bills":[],"billItems":[],"itemRules":[]}
        """.trimIndent()
        storage.files["v2"] = v2.toByteArray()
        val result = backup.inspect("v2")
        assertTrue(result.toString(), result is BackupDecodeResult.Valid)
        val file = (result as BackupDecodeResult.Valid).file
        assertTrue(file.recurringRules.isEmpty())

        backup.restore(file)

        assertTrue(recurring.observeRules().first().isEmpty())
        assertEquals(listOf(500L), transactions.observeAll().first().map { it.amount })
        assertNull(transactions.observeAll().first().single().recurringId)
    }
}
